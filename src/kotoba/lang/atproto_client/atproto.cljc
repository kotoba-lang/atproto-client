(ns kotoba.lang.atproto-client.atproto
  "AT Protocol client facade -- CLJC port of this repo's ../../../../src/atproto.ts.

  TS's atproto.ts re-exports @atproto/api's AtpAgent/AtpBaseClient class +
  Bluesky lexicon *type* re-exports (AppBskyActorDefs etc.) alongside two
  executing helpers: createAgent(opts) and xrpc(agent, method, nsid, body,
  opts). The class re-export and the lexicon type re-exports are
  TypeScript-only concepts (a class instance to wrap, and compile-time
  types) with no Clojure equivalent -- a plain Clojure map already serves
  as the 'typed record' role, and there is nothing to port for either.
  This namespace ports only the two functions that actually execute code:

    - `create-agent` -- the Clojure 'agent' is a plain immutable map
      {:service <url> :headers {...}} rather than a stateful AtpAgent
      class instance. Session state (when present) is threaded in by
      callers as an extra :session key (see kotoba.lang.atproto-client.pds/
      get-agent) instead of being mutated in place.
    - `xrpc` -- generic XRPC call helper: GET query-params or POST JSON
      body against `{service}/xrpc/{nsid}`, with header injection
      (agent defaults merged with per-call opts, plus an Authorization
      bearer header when the agent carries a session) and a thrown
      ex-info on any non-2xx response, mirroring the original's
      `if (!response.success) throw new Error(...)`.

  JVM/babashka branch: babashka.http-client + cheshire, synchronous,
  returns plain values. CLJS branch: browser-native fetch, returns a
  js/Promise, matching the original TS functions all being async."
  (:require #?(:clj [babashka.http-client :as http])
            #?(:clj [cheshire.core :as json])
            [clojure.string :as str]))

;; ─── Shared (pure, portable) helpers ─────────────────────────────────────

(defn- strip-trailing-slashes [s]
  (str/replace s #"/+$" ""))

(defn- normalize-method
  "Accepts either a keyword (:get/:post) or a string (\"GET\"/\"POST\",
  case-insensitive) -- the original TS signature is `\"GET\" | \"POST\"`."
  [method]
  (cond
    (keyword? method) method
    (string? method) (keyword (str/lower-case method))
    :else (throw (ex-info (str "[kotoba.lang.atproto-client] invalid xrpc method: " (pr-str method))
                           {:method method}))))

(defn- qs-encode
  "Stringify a query param value the way JS `String(value)` would
  (booleans -> \"true\"/\"false\", numbers -> decimal, keywords -> name)."
  [v]
  (cond
    (keyword? v) (name v)
    :else (str v)))

(defn- url-encode-component [s]
  #?(:clj (java.net.URLEncoder/encode ^String s "UTF-8")
     :cljs (js/encodeURIComponent s)))

(defn- query-string [params]
  (->> params
       (remove (fn [[_ v]] (nil? v)))
       (map (fn [[k v]]
              (str (url-encode-component (name k)) "=" (url-encode-component (qs-encode v)))))
       (str/join "&")))

(defn- xrpc-url
  "Build `{service}/xrpc/{nsid}`, appending `params` as a query string
  when non-empty (used for GET requests -- POST requests send `params`
  as a JSON body instead, so `params` is nil for those callers)."
  [service nsid params]
  (let [base (str (strip-trailing-slashes service) "/xrpc/" nsid)
        qs (when (seq params) (query-string params))]
    (if (seq qs) (str base "?" qs) base)))

(defn- auth-header
  "Authorization header derived from the agent's session, when present."
  [agent]
  (when-let [jwt (get-in agent [:session :accessJwt])]
    {"Authorization" (str "Bearer " jwt)}))

(defn- merged-headers [agent opts]
  (merge (:headers agent) (auth-header agent) (:headers opts)))

;; ─── Agent Factory ────────────────────────────────────────────────────────

(defn create-agent
  "Factory for an agent map: {:service <url> :headers {...}}.

  Mirrors createAgent(opts) from atproto.ts (custom header injection) --
  minus the AtpAgent class instance itself, which has no Clojure
  equivalent: here the 'agent' is a plain immutable map threaded through
  `xrpc` (and, for authenticated calls, carrying an extra :session key --
  see kotoba.lang.atproto-client.pds/get-agent).

  opts: {:service <url> :headers? {<header-name> <value>}}

  Example:
    (create-agent {:service \"https://pds.etzhayyim.com\"
                    :headers {\"atproto-proxy\" \"did:web:yoro.etzhayyim.com#appview\"}})"
  [opts]
  {:service (:service opts)
   :headers (or (:headers opts) {})})

;; ─── XRPC Helper ──────────────────────────────────────────────────────────

#?(:clj
   (defn xrpc
     "Generic XRPC call: GET query-params or POST JSON body against
     `{(:service agent)}/xrpc/{nsid}`. Throws ex-info on any non-2xx
     response (mirrors `if (!response.success) throw new Error(...)`).
     Returns the parsed JSON response body (or nil for an empty body),
     i.e. the equivalent of the original's `response.data`.

     method: :get/:post (or \"GET\"/\"POST\")
     body:   for :get, becomes URL query params; for :post, a JSON body.
     opts:   {:headers? {...} :timeout? <ms>}"
     ([agent method nsid] (xrpc agent method nsid nil nil))
     ([agent method nsid body] (xrpc agent method nsid body nil))
     ([agent method nsid body opts]
      (let [m (normalize-method method)
            headers (merged-headers agent opts)
            url (if (= m :get)
                  (xrpc-url (:service agent) nsid body)
                  (xrpc-url (:service agent) nsid nil))
            resp (case m
                   :get (http/get url {:headers headers :throw false :timeout (:timeout opts)})
                   :post (http/post url {:headers (assoc headers "Content-Type" "application/json")
                                          :body (when body (json/generate-string body))
                                          :throw false
                                          :timeout (:timeout opts)}))
            status (:status resp)]
        (if (<= 200 status 299)
          (when (seq (:body resp))
            (json/parse-string (:body resp) true))
          (throw (ex-info (str "[kotoba.lang.atproto-client] xrpc " nsid " failed: " status " " (:body resp))
                           {:status status :nsid nsid :body (:body resp)})))))))

#?(:cljs
   (defn- safe-parse-json [text]
     (try
       (js->clj (js/JSON.parse text) :keywordize-keys true)
       (catch :default _ nil))))

#?(:cljs
   (defn xrpc
     "CLJS counterpart of the :clj `xrpc` above -- see its docstring.
     Returns a js/Promise (matching the original TS function being async)."
     ([agent method nsid] (xrpc agent method nsid nil nil))
     ([agent method nsid body] (xrpc agent method nsid body nil))
     ([agent method nsid body opts]
      (let [m (normalize-method method)
            headers (cond-> (merged-headers agent opts)
                      (= m :post) (assoc "Content-Type" "application/json"))
            url (if (= m :get)
                  (xrpc-url (:service agent) nsid body)
                  (xrpc-url (:service agent) nsid nil))
            init (cond-> {:method (str/upper-case (name m)) :headers headers}
                   (= m :post) (assoc :body (js/JSON.stringify (clj->js (or body {})))))]
        (-> (js/fetch url (clj->js init))
            (.then (fn [res]
                     (-> (.text res)
                         (.then (fn [text]
                                  (let [parsed (when (seq text) (safe-parse-json text))]
                                    (if (.-ok res)
                                      parsed
                                      (throw (js/Error. (str "[kotoba.lang.atproto-client] xrpc " nsid " failed: "
                                                              (.-status res) " " text)))))))))))))))
