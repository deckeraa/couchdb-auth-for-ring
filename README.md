# couchdb-auth-for-ring

`couchdb-auth-for-ring` lets you use CouchDB's [security model][https://docs.couchdb.org/en/latest/intro/security.html] and [_users database][https://docs.couchdb.org/en/latest/intro/security.html#authentication-database] to handle authentication and user management for a [Ring][https://github.com/ring-clojure/ring] app.

CouchDB issues AuthSession cookies to its users.
`couchdb-auth-for-ring` reuses this cookie to perform authentication on Ring handlers as well.

This has the following advantages:
# User management is handled entirely in CouchDB.
# Technical details such as password hashword and salting, roles, etc. are all handled by CouchDB.
# `couchdb-auth-for-ring` is stateless with respect to the Ring server. You can bounce your Ring server and users will still remain logged in, and scaling out to multiple Ring servers is seamless.
# Your app can be a more secure method for letting clients interact with CouchDB versus having clients connect to CouchDB directly.
# Avoids [CORS][https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS] issues by allowing you to serve web assets and database assets from the same server.

It also has the following caveats:
# User management is handled entirely in CouchDB.
  - There is only one _users database per CouchDB install. This means you can't setup different users on a per-database basis. There are workarounds to this such as creating user roles that have different database access.
  - [Cookie timeout][https://docs.couchdb.org/en/latest/config/auth.html#config-couch-httpd-auth] is configured per CouchDB install. `couchdb-auth-for-ring` supplies client code TODO that you can place in your scripts to have clients refresh their cookies.

## Usage

1) Issue users the auth cookie using the provided `login-handler`.

Example cURL usage (your username and password will be different):

```
curl -v -X POST localhost:3000/login -H "Content-Type: application/json" --data '{"user": "admin", "pass":  "test"}'
Note: Unnecessary use of -X or --request, POST is already inferred.
*   Trying 127.0.0.1...
* TCP_NODELAY set
* Connected to localhost (127.0.0.1) port 3000 (#0)
> POST /login HTTP/1.1
> Host: localhost:3000
> User-Agent: curl/7.58.0
> Accept: */*
> Content-Type: application/json
> Content-Length: 34
> 
* upload completely sent off: 34 out of 34 bytes
< HTTP/1.1 200 OK
< Date: Thu, 14 May 2020 10:54:33 GMT
< Content-Type: application/json
< Set-Cookie: AuthSession=YWRtaW46NUVCRDIzNjk6yaHN78pyCRvwcpGlyrczoI-yXWo;Path=/;Expires=Mon May 18 09:54:33 CDT 2020
< Content-Length: 35
< Server: Jetty(9.4.12.v20180830)
< 
* Connection #0 to host localhost left intact
{"name":"admin","roles":["_admin"]}
```

2) Wrap your handlers with wrap-cookie-auth and add the usernames and roles parameters
```
    ;; (auth/wrap-cookie-auth secret) will return a new function that takes in req
    ;; The handler that you pass into wrap-cookie-auth needs to take in three parameters:
    ;; - req -- the Ring request
    ;; - username -- the username looked up from CouchDB. This value comes from CouchDB, not the client.
    ;; - roles -- the roles array looked up from CouchDB. This value comes from CouchDB, not the client. 
(defn secret [req username roles]
  (println "secret: " req)
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (str "Hello " username ", only logged-in users can see this.")})
```

Example cURL usage. Without the cookie issued via `login-handler`:
```
curl -X POST localhost:3000/secret -H "Content-Type: application/json" --data '{"user": "admin", "pass":  "test"}'
Not authorized
```
With the cookie:
```
curl -X POST localhost:3000/secret -H "Content-Type: application/json" --data '{"user": "admin", "pass":  "test"}' -H "Cookie: AuthSession=YWRtaW46NUVCRDIzNjk6yaHN78pyCRvwcpGlyrczoI-yXWo"
Hello admin, only logged-in users can see this.
```

3) (Optional) Have clients call the refresh handler `endpoint` to get a new cookie and stayed logged-in for longer than the CouchDB session timeout.

```
curl -v localhost:3000/refresh -H "Cookie: AuthSession=YWRtaW46NUVCRDIzNjk6yaHN78pyCRvwcpGlyrczoI-yXWo"
*   Trying 127.0.0.1...
* TCP_NODELAY set
* Connected to localhost (127.0.0.1) port 3000 (#0)
> GET /refresh HTTP/1.1
> Host: localhost:3000
> User-Agent: curl/7.58.0
> Accept: */*
> Cookie: AuthSession=YWRtaW46NUVCRDIzNjk6yaHN78pyCRvwcpGlyrczoI-yXWo
> 
< HTTP/1.1 200 OK
< Date: Thu, 14 May 2020 11:17:07 GMT
< Content-Type: application/json
< Content-Length: 35
< Server: Jetty(9.4.12.v20180830)
< 
* Connection #0 to host localhost left intact
{"name":"admin","roles":["_admin"]}
```

Sometimes calling this endpoint will not give you a new cookie back.
This is due to the underlying behavior of [_session][https://docs.couchdb.org/en/stable/api/server/authn.html#get--_session] in CouchDB.

This handler is also useful for retrieving username and role information for the client.

4) (Optional) Use the provided `logout-handler` to let clients logout.
All this does is unset the cookie on the client. CouchDB doesn't have a concept of logging out.

```
curl -v -X POST localhost:3000/logout -H "Content-Type: application/json" -H "Cookie: AuthSession=YWRtaW46NUVCRDIzNjk6yaHN78pyCRvwcpGlyrczoI-yXWo"
*   Trying 127.0.0.1...
* TCP_NODELAY set
* Connected to localhost (127.0.0.1) port 3000 (#0)
> POST /logout HTTP/1.1
> Host: localhost:3000
> User-Agent: curl/7.58.0
> Accept: */*
> Content-Type: application/json
> Cookie: AuthSession=YWRtaW46NUVCRDIzNjk6yaHN78pyCRvwcpGlyrczoI-yXWo
> 
< HTTP/1.1 200 OK
< Date: Thu, 14 May 2020 11:13:34 GMT
< Content-Type: application/json
< Set-Cookie: AuthSession=;Path=/
< Content-Length: 19
< Server: Jetty(9.4.12.v20180830)
< 
* Connection #0 to host localhost left intact
{"logged-out":true}
```

5) (Optional) Use the provided `create-user-handler` to allow creation of new users.

```
curl localhost:3000/create-user -H "Content-Type: application/json" --data '{"user": "sample_user", "pass": "sample-password"}' -H "Cookie: AuthSession=YWRtaW46NUVCRDIzNjk6yaHN78pyCRvwcpGlyrczoI-yXWo"
true
```

If you want to restrict the creation of new users to only be an action that certain roles such
as admins can take, simply wrap create-user-handler and check the roles parameter.

```
(defn strict-create-user-handler [req username roles]
 (if (contains? (set roles) "_admin")
   (couchdb-auth-for-ring/create-user-handler req username roles)
   (couchdb-auth-for-ring/default-not-authorized-fn req)))
```

## Example application
TODO paste in example app core.clj

## How does it work?

TODO diagram

## Troubleshooting
### It's not sending a cookie back.
Check to make sure you are using the [wrap-cookies][https://github.com/ring-clojure/ring/wiki/Cookies] middleware.
If wrap-cookies is not used, Ring will not send back cookies, even though the handlers are setting the :cookies key.

## License

Copyright © 2020 Aaron Decker

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.