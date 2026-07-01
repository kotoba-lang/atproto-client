(ns kotoba.lang.atproto-client.pds
  "PDS session + record read/write helpers -- CLJC port of this repo's
  ../../../../src/pds.ts.

  Built entirely on kotoba.lang.atproto-client.atproto/xrpc (the generic
  XRPC call helper): every function below issues exactly the XRPC calls
  the original TS made via @atproto/api's typed AtpAgent methods
  (agent.login, agent.resumeSession, agent.com.atproto.server.*,
  agent.com.atproto.repo.*) -- this port has no generated typed client,
  so those typed methods are re-expressed directly as `xrpc` calls against
  their underlying NSIDs:

    login/createAccount        -> com.atproto.server.{createSession,createAccount}
    get-agent (session resume) -> com.atproto.server.getSession (verify)
    create-record/get-record/
      list-records              -> com.atproto.repo.{createRecord,getRecord,listRecords}

  Because there's no AtpAgent class instance, the 'agent' is a plain
  immutable map {:service <url> :headers {...} :session? {:did :handle
  :accessJwt :refreshJwt}} (see kotoba.lang.atproto-client.atproto/
  create-agent). Request/response maps keep the *same camelCase keyword
  keys as the wire JSON* (:accessJwt, :refreshJwt, :inviteCode, ...)
  rather than kebab-casing them -- this is a deliberate, low-risk choice
  for a wire-protocol facade port: it avoids inventing a naming-translation
  layer between Clojure call sites and the raw XRPC JSON shapes callers
  may also need to inspect/forward elsewhere in the kotoba ecosystem.

  Scoped out (see README \"Clojure/CLJC port\" section for the full list):
  the original AtpAgent transparently retries a request once against
  com.atproto.server.refreshSession on a 401/ExpiredToken response; this
  port does not reimplement that automatic-refresh retry loop -- callers
  needing long-lived sessions should re-`login`/re-`get-agent` themselves
  on auth failure.

  JVM/babashka + CLJS split only where actual network I/O is needed
  (`resolve-pds`'s internal `fetch-json`, and `health`'s reuse of `xrpc`);
  every other function here is portable .cljc with no reader conditionals,
  since it's expressed purely in terms of `xrpc`."
  (:require [kotoba.lang.atproto-client.atproto :as atproto]
            [clojure.string :as str]
            #?(:clj [babashka.http-client :as http])
            #?(:clj [cheshire.core :as json])))

(def default-pds
  "Default PDS service endpoint, matching pds.ts's DEFAULT_PDS."
  "https://pds.etzhayyim.com")

;; ─── Session ──────────────────────────────────────────────────────────────

(defn create-account
  "Create a new account on the PDS. Returns {:did :handle :accessJwt :refreshJwt}.

  opts: {:service? :handle :email :password :inviteCode?}"
  [opts]
  (let [service (or (:service opts) default-pds)
        agent (atproto/create-agent {:service service})
        body (cond-> {:handle (:handle opts) :email (:email opts) :password (:password opts)}
               (:inviteCode opts) (assoc :inviteCode (:inviteCode opts)))
        res (atproto/xrpc agent :post "com.atproto.server.createAccount" body)]
    (select-keys res [:did :handle :accessJwt :refreshJwt])))

(defn login
  "Log in with an existing identifier (handle or DID) + password.
  Returns {:did :handle :accessJwt :refreshJwt}.

  opts: {:service? :identifier :password}

  Wire call: POST com.atproto.server.createSession -- what @atproto/api's
  AtpAgent.login() calls under the hood."
  [opts]
  (let [service (or (:service opts) default-pds)
        agent (atproto/create-agent {:service service})
        res (atproto/xrpc agent :post "com.atproto.server.createSession"
                           {:identifier (:identifier opts) :password (:password opts)})]
    (select-keys res [:did :handle :accessJwt :refreshJwt])))

(defn get-agent
  "Get an authenticated agent ready for record operations. Pass either an
  existing :session OR a :handle/:password pair to log in.

  cfg: {:service?} & ({:handle :password} | {:session {:did :handle :accessJwt :refreshJwt}})

  When resuming an existing session, this verifies it against
  com.atproto.server.getSession (mirroring AtpAgent.resumeSession's
  server-side verification) -- but does NOT reimplement AtpAgent's
  automatic 401 -> refreshSession retry loop; see namespace docstring."
  [cfg]
  (let [service (or (:service cfg) default-pds)]
    (if-let [session (:session cfg)]
      (let [agent0 (assoc (atproto/create-agent {:service service}) :session session)
            verified (atproto/xrpc agent0 :get "com.atproto.server.getSession")]
        (assoc agent0 :session (merge session (select-keys verified [:did :handle]))))
      (let [session (login {:service service :identifier (:handle cfg) :password (:password cfg)})]
        (assoc (atproto/create-agent {:service service}) :session session)))))

;; ─── Records ──────────────────────────────────────────────────────────────

(defn create-record
  "Create an AT Record. Returns {:uri :cid}.

  The PDS validator requires $type matching the collection NSID -- merged
  into the record body automatically, mirroring the TS helper."
  ([agent did collection record] (create-record agent did collection record nil))
  ([agent did collection record rkey]
   (let [body (cond-> {:repo did :collection collection
                        :record (assoc record :$type collection)}
                rkey (assoc :rkey rkey))
         res (atproto/xrpc agent :post "com.atproto.repo.createRecord" body)]
     (select-keys res [:uri :cid]))))

(defn get-record
  "Read a single record by AT URI components. Returns {:uri :cid :value},
  or nil if the record doesn't exist (mirrors the TS helper's
  not-found -> null translation instead of propagating that error)."
  [agent did collection rkey]
  (try
    (let [res (atproto/xrpc agent :get "com.atproto.repo.getRecord"
                             {:repo did :collection collection :rkey rkey})]
      (select-keys res [:uri :cid :value]))
    (catch #?(:clj Exception :cljs :default) e
      (let [msg (or (ex-message e) "")]
        (if (or (str/includes? msg "not found") (str/includes? msg "Record not found"))
          nil
          (throw e))))))

(defn list-records
  "List records in a collection with cursor pagination.
  Returns {:records [{:uri :cid :value} ...] :cursor?}.

  opts: {:limit? :cursor? :reverse?}"
  ([agent did collection] (list-records agent did collection {}))
  ([agent did collection opts]
   (let [query (cond-> {:repo did :collection collection :limit (or (:limit opts) 50)}
                 (:cursor opts) (assoc :cursor (:cursor opts))
                 (contains? opts :reverse) (assoc :reverse (:reverse opts)))
         res (atproto/xrpc agent :get "com.atproto.repo.listRecords" query)]
     {:records (mapv #(select-keys % [:uri :cid :value]) (:records res))
      :cursor (:cursor res)})))

;; ─── Liveness ─────────────────────────────────────────────────────────────

(defn health
  "Quick PDS liveness check. Returns true if the configured service
  responds with a 2xx at /xrpc/_health (built via `xrpc`'s ordinary URL
  construction -- \"_health\" is just an nsid-shaped path segment here),
  false on any error (mirroring the TS helper's try/catch -> false)."
  ([] (health nil))
  ([service]
   (let [agent (atproto/create-agent {:service (or service default-pds)})]
     (try
       (atproto/xrpc agent :get "_health")
       true
       (catch #?(:clj Exception :cljs :default) _ false)))))

;; ─── did:web resolution ───────────────────────────────────────────────────

(defn- did-web->url
  "did:web -> its `.well-known/did.json` URL, mirroring the original TS
  resolvePds's host derivation 1:1 -- including its own documented
  limitation ('v0.1 = did:web only'): every literal ':' after the
  `did:web:` prefix is replaced with '/' with no percent-decoding, so a
  did:web identifying a host by IP:port (where the port's ':' would need
  to survive as a literal port separator) is not resolvable via this
  helper; only bare-domain / domain+path-segment did:web identifiers
  work. Not fixing this here keeps the port behavior-identical to
  upstream; see README."
  [did]
  (let [host (str/replace (subs did (count "did:web:")) ":" "/")]
    (str "https://" host "/.well-known/did.json")))

(defn- pick-pds-service
  "Find the AtprotoPersonalDataServer service entry in a did.json doc,
  mirroring the TS helper's 3-way match (type, or either #atproto_pds
  id spelling)."
  [doc did]
  (->> (:service doc)
       (filter (fn [s] (or (= (:type s) "AtprotoPersonalDataServer")
                            (= (:id s) "#atproto_pds")
                            (= (:id s) (str did "#atproto_pds")))))
       first))

#?(:clj
   (defn- fetch-json
     "Plain GET + JSON parse of an arbitrary (non-XRPC) URL. Private and
     network-only -- exercised directly in tests via `with-redefs` so the
     `resolve-pds` orchestration logic below (did:web prefix check, URL
     construction, service selection, error messages) can be tested
     end-to-end against a local JDK-native mock server without needing a
     trusted-TLS setup for the hardcoded `https://` scheme."
     [url]
     (let [resp (http/get url {:throw false})]
       {:ok (<= 200 (:status resp) 299)
        :status (:status resp)
        :body (when (seq (:body resp))
                (try (json/parse-string (:body resp) true)
                     (catch Exception _ nil)))})))

#?(:cljs
   (defn- fetch-json [url]
     (-> (js/fetch url)
         (.then (fn [res]
                  (-> (.text res)
                      (.then (fn [text]
                               {:ok (.-ok res)
                                :status (.-status res)
                                :body (when (seq text)
                                        (try (js->clj (js/JSON.parse text) :keywordize-keys true)
                                             (catch :default _ nil)))}))))))))

#?(:clj
   (defn resolve-pds
     "Resolve a DID to its PDS endpoint via its DID document.
     v0.1: handles did:web only (most common for etzhayyim setups)."
     [did]
     (if-not (str/starts-with? did "did:web:")
       (throw (ex-info (str "[kotoba.lang.atproto-client/pds] resolvePds: did method not yet supported: "
                             did " (v0.1 = did:web only; did:plc next)")
                        {:did did}))
       (let [url (did-web->url did)
             {:keys [ok body]} (fetch-json url)]
         (when-not ok
           (throw (ex-info (str "[kotoba.lang.atproto-client/pds] resolvePds: did.json not reachable at " url)
                            {:did did :url url})))
         (if-let [pds (pick-pds-service body did)]
           (:serviceEndpoint pds)
           (throw (ex-info (str "[kotoba.lang.atproto-client/pds] resolvePds: no AtprotoPersonalDataServer service entry in " url)
                            {:did did :url url})))))))

#?(:cljs
   (defn resolve-pds
     "CLJS counterpart of the :clj `resolve-pds` above -- see its
     docstring. Returns a js/Promise resolving to the PDS endpoint string."
     [did]
     (if-not (str/starts-with? did "did:web:")
       (js/Promise.reject
        (js/Error. (str "[kotoba.lang.atproto-client/pds] resolvePds: did method not yet supported: "
                         did " (v0.1 = did:web only; did:plc next)")))
       (let [url (did-web->url did)]
         (-> (fetch-json url)
             (.then (fn [{:keys [ok body]}]
                      (if-not ok
                        (throw (js/Error. (str "[kotoba.lang.atproto-client/pds] resolvePds: did.json not reachable at " url)))
                        (if-let [pds (pick-pds-service body did)]
                          (:serviceEndpoint pds)
                          (throw (js/Error. (str "[kotoba.lang.atproto-client/pds] resolvePds: no AtprotoPersonalDataServer service entry in " url))))))))))))
