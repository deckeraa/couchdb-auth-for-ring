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

1) Issue users the auth cookie using the provided login handler.
TODO code example

2) Wrap your handlers with wrap-cookie-auth and add the usernames and roles parameters
TODO code example

3) (Optional) Have clients call the refresh endpoint to stay logged-in.

4) (Optional) Use the provided logout handler to let clients logout.

TODO example app core.clj

## How does it work?

TODO diagram

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
