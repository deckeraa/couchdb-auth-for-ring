(ns com.stronganchortech.couchdb-auth-for-ring
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [clojure.walk :refer [keywordize-keys]]
            [ring.util.request :as request]
            [ring.util.response :as response]
            [ring.util.json-response :refer [json-response]]
            [clj-http.client :as http]))


;; TODO before library release
;; - support url configuration through environment variables
;; - test cookie-check-handler
;; - implement password handling for create-user-handler via environment variables

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
                    {cookie-name (update v :expires #(.toString %))} ;; the :expires attr also needs changed frmo a java.util.Date to a string
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

(defn cookie-check
  "Checks the cookies in a request against CouchDB. Returns [{:name :roles} new_cookie] if it's valid, false otherwise.
  Note that
    1) A new cookie being issued does not invalidate old cookies.
    2) New cookies won't always be issued. It takes about a minute after getting a cookie before
       CouchDB will give you a new cookie."
  [cookie-value]
  (let [
        resp (http/get "http://localhost:5984/_session" {:as :json
                                                         :headers {"Cookie" (str "AuthSession=" cookie-value)}
                                                         :content-type :json
                                                         })]
    (if (nil? (get-in resp [:body :userCtx :name]))
      false
      [(get-in resp [:body :userCtx])
       (remove-cookie-attrs-not-supported-by-ring (:cookies resp))])))

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
          _ (println "Login request for " (:user params))
          resp (http/post "http://localhost:5984/_session" {:as :json
                                                            :content-type :json
                                                            :form-params {:name     (:user params)
                                                                          :password (:pass params)}})]
      (assoc 
       (json-response {:name (get-in resp [:body :name]) :roles (get-in resp [:body :roles])})
       :cookies (as-> (:cookies resp) $
                  (remove-cookie-attrs-not-supported-by-ring $)
                  ;; (set-cookies-flag $ :secure true)
                  ;; (set-cookies-flag $ :same-site :strict)
                 )))
    (catch Exception e
      (json-response false))))

(defn create-user-handler [req username roles]
  (try
    (let [params (get-body req)
          name (:user params)]
      (println "create-user-handler: " params)
      (if (nil? (re-find #"^\w+$" name)) ; sanitize the name
        (assoc (json-response :invalid-user-name) :status 400)
        (let [resp (http/put
                    (str "http://localhost:5984/_users/org.couchdb.user:" name)
                    {:as :json
                                        ;                     :basic-auth [(:username db) (:password db)]
                     :basic-auth ["admin" "test"] ;; TODO actual password management
                     :content-type :json
                     :form-params {:name     name
                                   :password (:pass params)
                                   :roles []
                                   :type :user}})]
          (println "create-user resp: " resp)
          (if (= 201 (:status resp))
            (do
              (let [login-resp (http/post "http://localhost:5984/_session" {:as :json
                                                            :content-type :json
                                                            :form-params {:name     (:user params)
                                                                          :password (:pass params)}})]
                (assoc 
                 (json-response true)
                 :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies login-resp)) ;; set the CouchDB cookie on the ring response
                 )))
            (assoc (json-response false) :status 400) ;; don't want to leak any info useful to attackers, no keeping this very non-descript
            ))))
    (catch Exception e
      (println "create-user-handler exception: " e)
      (assoc (json-response false) :status 400))))

(defn change-password-handler [req username roles]
  (try
    (let [params (get-body req)
          cookie-value (get-in req [:cookies "AuthSession" :value])]
      (let [old-user
            (:body (http/get (str "http://localhost:5984/_users/org.couchdb.user:" username)
                             {:as :json
                              :headers {"Cookie" (str "AuthSession=" cookie-value)}}))
            new-user (as-> old-user $
                       (assoc $ :password (:pass params)))]
        ;; change the password and then re-authenticate since the old cookie is no longer considered valid by CouchDB
        (let [change-resp
              (http/put (str "http://localhost:5984/_users/org.couchdb.user:" username)
                        {:as :json
                         :headers {"Cookie" (str "AuthSession=" cookie-value)}
                         :content-type :json
                         :form-params new-user})
              new-login (http/post "http://localhost:5984/_session"
                                   {:as :json
                                    :content-type :json
                                    :form-params {:name     username
                                                  :password (:pass params)}})]
          (assoc 
           (json-response true)
           :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies new-login)) ;; set the CouchDB cookie on the ring response
           ))
        ))
    (catch Exception e
      (json-response false))))

(defn logout-handler [req username roles]
  (try
    (let [resp (http/delete "http://localhost:5984/_session" {:as :json})]
      (assoc 
       (json-response {:logged-out true})
       :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies resp)) ;; set the CouchDB cookie on the ring response
       )
      )
    (catch Exception e
      (json-response {:logged-out false}))))
