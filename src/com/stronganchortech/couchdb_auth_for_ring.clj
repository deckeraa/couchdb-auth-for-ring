(ns com.stronganchortech.couchdb-auth-for-ring
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [clojure.walk :refer [keywordize-keys]]
            [ring.util.request :as request]
            [ring.util.response :as response]
            [ring.util.json-response :refer [json-response]]
            [clj-http.client :as http])
  (:import
   (java.util Base64)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

(def couch-url (or (System/getenv "COUCHDB_AUTH_FOR_RING_DB_URL") "http://localhost:5984"))
(def couch-username (System/getenv "COUCHDB_AUTH_FOR_RING_DB_USERNAME"))
(def couch-password (System/getenv "COUCHDB_AUTH_FOR_RING_DB_PASSWORD"))

;; use secure (i.e. cookies only get sent over https by default
;; I considered whether this was overly burdensome on the dev experience since tools like Figwheel
;; are typically used over http. However, devs will need to set the username and password
;; environment variables anyhow, so I'm leaning towards having this having the most stringent
;; default security in prod vs. making dev setup a little easier.
(def use-secure-cookies? (case (System/getenv "COUCHDB_AUTH_FOR_RING_SECURE_COOKIE_FLAG")
                           "true" true
                           "false" false
                           true))

(def same-site-flag (case (System/getenv "COUCHDB_AUTH_FOR_RING_SAME_SITE_COOKIE_FLAG")
                      "strict" :strict
                      "lax"    :lax
                      "none"   :none
                      :strict))

;; Friendly reminder: the http-only cookie flag means that Javascript can't read the cookie.
;; Cookies with http-only set can still be sent over https.
;; https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie
(def http-only? (case (System/getenv "COUCHDB_AUTH_FOR_RING_HTTP_ONLY_COOKIE_FLAG")
                  "true" true
                  "false" false
                  false)) ;; off by default because I find it very useful to be able to determine if the user is logged in to my Reagent apps without needing to make a web call. If your Javascript never needs to know about this cookie you can turn it on as an additional security measure.

(defn get-body [req]
  (-> req
      (request/body-string)
      (json/read-str)
      (keywordize-keys)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Cookies
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- remove-cookie-attrs-not-supported-by-ring
  "CouchDB sends back cookie attributes like :version that ring can't handle. This removes those."
  [cookies]
  ;; CouchDB sends a cookies map that looks something like
  ;; {AuthSession {:discard false, :expires #inst "2020-04-21T19:51:08.000-00:00", :path /, :secure false, :value YWxwaGE6NUU5RjQ5RkM6MXHV10hKUXVSuaY8GcMOZ2wFfeA, :version 0}}
  (apply merge
         (map (fn [[cookie-name v]]
                (let [v (select-keys v [:value :domain :path :secure :http-only :max-age :same-site :expires])]
                  (if (:expires v)
                    {cookie-name (update v :expires #(.toString %))} ;; the :expires attr needs changed from a java.util.Date to a string
                    {cookie-name v})))
              cookies)))

(defn- set-cookies-flag
  "Sets a flag on all cookies in the cookies map.
  See test-set-cookies-flag for an example of the structure of the cookies map."
  [cookies flag flag-value]
  (apply merge
         (map (fn [[cookie-name v]]
                {cookie-name (assoc v flag flag-value)})
              cookies)))

(deftest test-set-cookies-flag
  (is (= (set-cookies-flag {"AuthSession"
                                   {:value "YWxwaGE6NUVBQ0E1OUM6PNpD6s2El_yHqe2MNL-eOTGvkMQ"
                                    :path "/"
                                    :secure false
                                    :expires "Fri May 01 18:41:32 CDT 2020"}}
                                  :secure
                                  true)
         {"AuthSession"
                                   {:value "YWxwaGE6NUVBQ0E1OUM6PNpD6s2El_yHqe2MNL-eOTGvkMQ"
                                    :path "/"
                                    :secure true
                                    :expires "Fri May 01 18:41:32 CDT 2020"}}
         )))

(defn- process-cookies [cookies]
  (-> cookies
      (remove-cookie-attrs-not-supported-by-ring)
      (set-cookies-flag :same-site same-site-flag)
      (set-cookies-flag :secure use-secure-cookies?)
      (set-cookies-flag :http-only http-only?)))

(defn cookie-check
  "Checks the cookies in a request against CouchDB. Returns [{:name :roles} new_cookie] if it's valid, false otherwise.
  Note that
    1) A new cookie being issued does not invalidate old cookies.
    2) New cookies won't always be issued. It takes about a minute after getting a cookie before
       CouchDB will give you a new cookie."
  [cookie-value]
  (let [
        resp (http/get (str couch-url "/_session") {:as :json
                                                 :headers {"Cookie" (str "AuthSession=" cookie-value)}
                                                 :content-type :json
                                                 })]
    (if (nil? (get-in resp [:body :userCtx :name]))
      false
      [(get-in resp [:body :userCtx])
       (process-cookies (:cookies resp))])))

(defn cookie-check-from-req
  "Same as cookie-check, but takes in a Ring request rather than a cookie value directly."
  [req]
  (let [cookie-value (get-in req [:cookies "AuthSession" :value])]
    (cookie-check cookie-value)))

(defn cookie-check-handler
  "Checks a users cookie for freshness and passes back a newer cookie if the
  current cookie is still valid but CouchDB has a fresher cookie available."
  [req]
  (let [cookie-value (get-in req [:cookies "AuthSession" :value])]
    (if-let [[userCtxt new-cookie] (cookie-check cookie-value)]
      (assoc (json-response userCtxt) :cookies new-cookie)
      (json-response false))))

(defn default-not-authorized-fn [req]
  (assoc 
   (response/content-type (response/response "Not authorized") "text/html")
   :status 401))

(defn wrap-cookie-auth
  "Ring middleware that pass a handler the req and the username if the user's cookie is valid.
  Returns the results of not-authorized-fn (or, by default, a 'not authorized' response)
  otherwise.
  Not authorized-fn takes in a single argument which is the Ring request."
  ([handler]
   (wrap-cookie-auth handler default-not-authorized-fn))
  ([handler not-authorized-fn]
   (fn [req]
     (let [cookie-check-val (cookie-check-from-req req)]
       (if (not cookie-check-val)
         ;; if the cookie check didn't pass, send back the not authorized response
         (not-authorized-fn req)
         ;; otherwise, call the handler
         (let [username (get-in cookie-check-val [0 :name])
               roles    (get-in cookie-check-val [0 :roles])]
           (handler req username roles)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ring Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn login-handler [req]
  (try
    (let [params (get-body req)
          resp (http/post (str couch-url "/_session") {:as :json
                                                       :content-type :json
                                                       :form-params {:name     (:user params)
                                                                     :password (:pass params)}})]
      (assoc 
       (json-response {:name (get-in resp [:body :name]) :roles (get-in resp [:body :roles])})
       :cookies (process-cookies (:cookies resp))))
    (catch Exception e
      (json-response false))))

(defn proxy-login-handler [secret username comma-delimited-roles]
  (println "proxy-login-handler" secret username comma-delimited-roles)
  (try
    (let [secret-bytes (.getBytes secret)
          _ (println (type secret-bytes) secret-bytes)
          mac (doto (Mac/getInstance "HmacSHA256") (.init (SecretKeySpec. secret-bytes "HmacSHA256")))
          hash (.doFinal mac (.getBytes "foo" "UTF-8"))
          _ (println "hash: " hash (.toString hash))
          encoded-hash (.. (Base64/getUrlEncoder) withoutPadding (encodeToString hash))
          _ (println "encoded-hash: " encoded-hash)
          params {:headers {:X-Auth-CouchDB-UserName username
                            :X-Auth-CouchDB-Roles comma-delimited-roles
                            :X-Auth-CouchDB-Token encoded-hash}
                  :accept :json
                  :content-type :json
}
          _ (println "params: " params)
          resp (http/get (str couch-url "/_session") params)]
      (println "resp: " resp)
      (assoc 
       (json-response {:name (get-in resp [:body :name]) :roles (get-in resp [:body :roles])})
       :cookies (process-cookies (:cookies resp))))
    (catch Exception e
      (println "Caught exception: " e)
      (json-response false)))
  ;; (try
  ;;   (let [params (get-body req)
  ;;         resp (http/post (str couch-url "/_session") {:as :json
  ;;                                                      :content-type :json
  ;;                                                      :form-params {:name     (:user params)
  ;;                                                                    :password (:pass params)}})]
  ;;     (assoc 
  ;;      (json-response {:name (get-in resp [:body :name]) :roles (get-in resp [:body :roles])})
  ;;      :cookies (process-cookies (:cookies resp))))
  ;;   (catch Exception e
  ;;     (json-response false)))
  )

(defn create-user
  ([name password]
   (create-user name password nil nil))
  ([name password roles]
   (create-user name password roles nil))
  ([name password roles extra-info]
   (if (or (nil? name)
           (nil? (re-find #"^\w+$" name))) ; sanitize the name
     false
     (let [resp (http/put
                 (str couch-url "/_users/org.couchdb.user:" name)
                 {:as :json
                  :basic-auth [couch-username couch-password]
                  :content-type :json
                  :form-params (merge
                                (or extra-info {})
                                {:name     name
                                 :password password
                                 :roles (or roles [])
                                 :type :user})})]
       (= 201 (:status resp))))))

(defn create-user-handler [req username roles]
  (try
    (let [params (get-body req)
          name  (:user params)
          pass  (:pass params)
          roles (:roles params)
          extra-info (:extra-info params)]
      (if (create-user name pass roles extra-info)
        (json-response true)
        (assoc (json-response false) :status 400)))
    (catch Exception e
      (println "create-user-handler exception: " e)
      (assoc (json-response false) :status 400))))

(defn change-password-handler [req username roles]
  (try
    (let [params (get-body req)
          cookie-value (get-in req [:cookies "AuthSession" :value])]
      (let [old-user
            (:body (http/get (str couch-url "/_users/org.couchdb.user:" username)
                             {:as :json
                              :headers {"Cookie" (str "AuthSession=" cookie-value)}}))
            new-user (as-> old-user $
                       (assoc $ :password (:pass params)))]
        ;; change the password and then re-authenticate since the old cookie is no longer considered valid by CouchDB
        (let [change-resp
              (http/put (str couch-url "/_users/org.couchdb.user:" username)
                        {:as :json
                         :headers {"Cookie" (str "AuthSession=" cookie-value)}
                         :content-type :json
                         :form-params new-user})
              new-login (http/post (str couch-url "/_session")
                                   {:as :json
                                    :content-type :json
                                    :form-params {:name     username
                                                  :password (:pass params)}})]
          (assoc 
           (json-response true)
           :cookies (process-cookies (:cookies new-login)) ;; set the CouchDB cookie on the ring response
           ))
        ))
    (catch Exception e
      (json-response false))))

(defn logout-handler [req username roles]
  (try
    (let [resp (http/delete (str couch-url "/_session") {:as :json})]
      (assoc 
       (json-response {:logged-out true})
       :cookies (process-cookies (:cookies resp))
       )
      )
    (catch Exception e
      (json-response {:logged-out false}))))
