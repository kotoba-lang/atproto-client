(ns kotoba.lang.atproto-client.pds-test
  "JVM-only: exercises kotoba.lang.atproto-client.{atproto,pds}'s :clj
  branch against a real, dependency-free mock PDS HTTP server
  (com.sun.net.httpserver.HttpServer, part of the JDK -- no mock-server
  library needed), mirroring the XRPC wire shapes of this repo's own
  test/fake-pds.mjs (etzhayyim-sdk's shared TS test fixture for the same
  two modules) as closely as this port's actual surface requires, plus a
  few endpoints fake-pds.mjs doesn't implement (createAccount,
  createSession/login, _health) that this port also covers.

  Plain .clj, not .cljc, since this test infrastructure only makes sense
  on the JVM; the source under test stays .cljc for CLJS/browser
  portability. Run with `clojure -M:test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [kotoba.lang.atproto-client.atproto :as atproto]
            [kotoba.lang.atproto-client.pds :as pds])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.io ByteArrayOutputStream]))

;; ─── Mock PDS server (mirrors test/fake-pds.mjs's endpoint set + adds a
;;     few this port also exercises) ─────────────────────────────────────

(def ^:private fake-cid
  "bafyreialr2vrqpzh4ay4r3jqfd76nlpchdfpyrhqbfgsodwxqndvonbymq")

(defn- slurp-bytes [^java.io.InputStream is]
  (let [out (ByteArrayOutputStream.)]
    (.transferTo is out)
    (.toByteArray out)))

(defn- json-response! [^HttpExchange exchange status data]
  (let [bytes (.getBytes (json/generate-string data) "UTF-8")]
    (.getResponseHeaders exchange) ;; touch to ensure it's initialized (mirrors real servers)
    (.sendResponseHeaders exchange status (count bytes))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- read-body [^HttpExchange exchange]
  (let [raw (String. (slurp-bytes (.getRequestBody exchange)) "UTF-8")]
    (when (seq raw)
      (try (json/parse-string raw true) (catch Exception _ nil)))))

(defn- query-params [^HttpExchange exchange]
  (let [q (.getQuery (.getRequestURI exchange))]
    (if (str/blank? q)
      {}
      (into {} (map (fn [pair]
                      (let [[k v] (str/split pair #"=" 2)]
                        [k (java.net.URLDecoder/decode v "UTF-8")]))
                    (str/split q #"&"))))))

(defn- auth-bearer [^HttpExchange exchange]
  (let [h (.getFirst (.getRequestHeaders exchange) "Authorization")]
    (when h (str/replace h #"(?i)^Bearer\s+" ""))))

;; In-memory record store, keyed by AT URI -- same shape as fake-pds.mjs.
(defonce ^:private records (atom {}))
;; sessions: accessJwt -> {:did :handle}
(defonce ^:private sessions (atom {}))

(defn- new-tid []
  (str "t" (Long/toString (System/nanoTime) 36)))

(def ^:private default-did "did:web:fake.etzhayyim.test")
(def ^:private default-handle "fake.etzhayyim.test")

(defn- mock-pds-handler [^HttpExchange exchange]
  (let [method (.getRequestMethod exchange)
        path (.getPath (.getRequestURI exchange))
        key (str method " " path)]
    (try
      (case key
        "GET /xrpc/_health"
        (json-response! exchange 200 {:ok true})

        "GET /xrpc/_echo-header"
        (json-response! exchange 200 {:value (.getFirst (.getRequestHeaders exchange) "X-Test-Header")})

        "GET /xrpc/com.atproto.server.getSession"
        (let [jwt (auth-bearer exchange)
              s (get @sessions jwt {:did default-did :handle default-handle})]
          (json-response! exchange 200 {:did (:did s) :handle (:handle s)
                                         :email "fake@etzhayyim.test" :emailConfirmed true :active true}))

        "POST /xrpc/com.atproto.server.createSession"
        (let [body (read-body exchange)]
          (if (and (:identifier body) (= (:password body) "correct-horse"))
            (let [jwt (str "fake-access-" (new-tid))]
              (swap! sessions assoc jwt {:did default-did :handle default-handle})
              (json-response! exchange 200 {:did default-did :handle default-handle
                                             :accessJwt jwt :refreshJwt (str "fake-refresh-" (new-tid))
                                             :active true}))
            (json-response! exchange 401 {:error "AuthenticationRequired" :message "Invalid identifier or password"})))

        "POST /xrpc/com.atproto.server.createAccount"
        (let [body (read-body exchange)]
          (if (and (:handle body) (:email body) (:password body))
            (let [jwt (str "fake-access-" (new-tid))
                  did (str "did:web:" (:handle body))]
              (swap! sessions assoc jwt {:did did :handle (:handle body)})
              (json-response! exchange 200 {:did did :handle (:handle body)
                                             :accessJwt jwt :refreshJwt (str "fake-refresh-" (new-tid))}))
            (json-response! exchange 400 {:error "InvalidRequest" :message "handle/email/password required"})))

        "POST /xrpc/com.atproto.repo.createRecord"
        (let [body (read-body exchange)]
          (if (and (:repo body) (:collection body))
            (let [rkey (or (:rkey body) (new-tid))
                  uri (str "at://" (:repo body) "/" (:collection body) "/" rkey)]
              (swap! records assoc uri {:uri uri :cid fake-cid :value (:record body)})
              (json-response! exchange 200 {:uri uri :cid fake-cid
                                             :commit {:cid fake-cid :rev (new-tid)}
                                             :validationStatus "valid"}))
            (json-response! exchange 400 {:error "InvalidRequest" :message "repo + collection required"})))

        "GET /xrpc/com.atproto.repo.getRecord"
        (let [q (query-params exchange)
              uri (str "at://" (get q "repo") "/" (get q "collection") "/" (get q "rkey"))
              r (get @records uri)]
          (if r
            (json-response! exchange 200 r)
            (json-response! exchange 400 {:error "InvalidRequest" :message "Record not found"})))

        "GET /xrpc/com.atproto.repo.listRecords"
        (let [q (query-params exchange)
              repo (get q "repo")
              collection (get q "collection")
              prefix (str "at://" repo "/" collection "/")
              matched (->> (vals @records)
                           (filter #(str/starts-with? (:uri %) prefix)))
              matched (if (= "true" (get q "reverse")) (reverse matched) matched)
              limit (if-let [l (get q "limit")] (Long/parseLong l) 50)]
          (json-response! exchange 200 {:records (vec (take limit matched))}))

        ;; did:web resolution target -- served on the SAME mock server;
        ;; the success-path test redirects kotoba.lang.atproto-client.pds's
        ;; private fetch-json to this server's base URL (see
        ;; resolve-pds-success-test) since resolve-pds hardcodes an
        ;; https:// scheme this JDK-native plain-HTTP mock can't serve
        ;; directly.
        "GET /.well-known/did.json"
        (json-response! exchange 200
                         {:id default-did
                          :service [{:id "#atproto_pds"
                                     :type "AtprotoPersonalDataServer"
                                     :serviceEndpoint "https://real-pds.etzhayyim.test"}]})

        (json-response! exchange 404 {:error "MethodNotFound" :message key}))
      (catch Exception e
        (json-response! exchange 400 {:error "BadRequest" :message (str e)})))))

(def ^:dynamic *base-url* nil)
(def ^:private server (atom nil))

(defn- with-mock-pds [f]
  (reset! records {})
  (reset! sessions {})
  (let [s (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext s "/" (reify HttpHandler (handle [_ ex] (mock-pds-handler ex))))
    (.start s)
    (reset! server s)
    (try
      (binding [*base-url* (str "http://127.0.0.1:" (.getPort (.getAddress s)))]
        (f))
      (finally
        (.stop s 0)))))

(use-fixtures :each with-mock-pds)

;; ─── atproto/xrpc + create-agent ────────────────────────────────────────

(deftest create-agent-test
  (let [agent (atproto/create-agent {:service "https://pds.etzhayyim.com"
                                      :headers {"atproto-proxy" "did:web:yoro.etzhayyim.com#appview"}})]
    (is (= "https://pds.etzhayyim.com" (:service agent)))
    (is (= "did:web:yoro.etzhayyim.com#appview" (get (:headers agent) "atproto-proxy")))))

(deftest xrpc-get-test
  (let [agent (atproto/create-agent {:service *base-url*})
        res (atproto/xrpc agent :get "_health")]
    (is (true? (:ok res)))))

(deftest xrpc-post-test
  (let [agent (atproto/create-agent {:service *base-url*})
        res (atproto/xrpc agent :post "com.atproto.repo.createRecord"
                           {:repo "did:web:x.test" :collection "com.example.post"
                            :record {:$type "com.example.post" :text "hi"}})]
    (is (= fake-cid (:cid res)))
    (is (str/starts-with? (:uri res) "at://did:web:x.test/com.example.post/"))))

(deftest xrpc-header-injection-test
  (testing "agent-level default headers reach the server"
    (let [agent (atproto/create-agent {:service *base-url* :headers {"X-Test-Header" "agent-default"}})
          res (atproto/xrpc agent :get "_echo-header")]
      (is (= "agent-default" (:value res)))))
  (testing "per-call opts headers override agent defaults"
    (let [agent (atproto/create-agent {:service *base-url* :headers {"X-Test-Header" "agent-default"}})
          res (atproto/xrpc agent :get "_echo-header" nil {:headers {"X-Test-Header" "call-override"}})]
      (is (= "call-override" (:value res))))))

(deftest xrpc-string-method-test
  (testing "accepts \"GET\"/\"POST\" strings, not just keywords"
    (let [agent (atproto/create-agent {:service *base-url*})]
      (is (true? (:ok (atproto/xrpc agent "GET" "_health")))))))

(deftest xrpc-throws-on-non-2xx-test
  (let [agent (atproto/create-agent {:service *base-url*})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"com\.atproto\.repo\.createRecord failed: 400"
                           (atproto/xrpc agent :post "com.atproto.repo.createRecord" {})))))

;; ─── pds: login / createAccount / getAgent ─────────────────────────────

(deftest login-test
  (let [session (pds/login {:service *base-url* :identifier "fake.etzhayyim.test" :password "correct-horse"})]
    (is (= default-did (:did session)))
    (is (str/starts-with? (:accessJwt session) "fake-access-"))
    (is (str/starts-with? (:refreshJwt session) "fake-refresh-"))))

(deftest login-failure-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"createSession failed: 401"
                         (pds/login {:service *base-url* :identifier "x" :password "wrong"}))))

(deftest create-account-test
  (let [acct (pds/create-account {:service *base-url* :handle "newbie.test"
                                   :email "newbie@test.com" :password "hunter2"})]
    (is (= "did:web:newbie.test" (:did acct)))
    (is (= "newbie.test" (:handle acct)))
    (is (str/starts-with? (:accessJwt acct) "fake-access-"))))

(deftest get-agent-via-handle-password-test
  (let [agent (pds/get-agent {:service *base-url* :handle "fake.etzhayyim.test" :password "correct-horse"})]
    (is (= default-did (get-in agent [:session :did])))
    (is (some? (get-in agent [:session :accessJwt])))))

(deftest get-agent-via-session-resume-test
  (let [{:keys [accessJwt refreshJwt]} (pds/login {:service *base-url* :identifier "fake.etzhayyim.test" :password "correct-horse"})
        agent (pds/get-agent {:service *base-url*
                               :session {:did default-did :handle default-handle
                                         :accessJwt accessJwt :refreshJwt refreshJwt}})]
    (is (= default-did (get-in agent [:session :did])))
    (is (= accessJwt (get-in agent [:session :accessJwt])))))

;; ─── pds: record CRUD ───────────────────────────────────────────────────

(defn- authed-agent []
  (pds/get-agent {:service *base-url* :handle "fake.etzhayyim.test" :password "correct-horse"}))

(deftest create-get-list-records-test
  (let [agent (authed-agent)
        did "did:web:writer.test"
        {:keys [uri cid]} (pds/create-record agent did "com.example.post" {:text "hello kotoba"})]
    (is (= fake-cid cid))
    (is (str/starts-with? uri (str "at://" did "/com.example.post/")))

    (testing "get-record reads it back"
      (let [rkey (last (str/split uri #"/"))
            got (pds/get-record agent did "com.example.post" rkey)]
        (is (= uri (:uri got)))
        (is (= "hello kotoba" (get-in got [:value :text])))
        (is (= "com.example.post" (get-in got [:value :$type])))))

    (testing "get-record returns nil for a missing rkey"
      (is (nil? (pds/get-record agent did "com.example.post" "does-not-exist"))))

    (testing "list-records finds it"
      (let [{:keys [records]} (pds/list-records agent did "com.example.post")]
        (is (= 1 (count records)))
        (is (= uri (:uri (first records))))))))

(deftest create-record-with-explicit-rkey-test
  (let [agent (authed-agent)
        did "did:web:writer2.test"
        {:keys [uri]} (pds/create-record agent did "com.example.post" {:text "pinned"} "myrkey")]
    (is (= (str "at://" did "/com.example.post/myrkey") uri))))

(deftest list-records-limit-and-reverse-test
  (let [agent (authed-agent)
        did "did:web:writer3.test"
        _ (dotimes [n 3]
            (pds/create-record agent did "com.example.post" {:text (str "post-" n)} (str "rkey" n)))
        {:keys [records]} (pds/list-records agent did "com.example.post" {:limit 2 :reverse true})]
    (is (= 2 (count records)))))

;; ─── pds: health ────────────────────────────────────────────────────────

(deftest health-true-test
  (is (true? (pds/health *base-url*))))

(deftest health-false-on-unreachable-test
  (is (false? (pds/health "http://127.0.0.1:1"))))

;; ─── pds: resolve-pds (did:web) ─────────────────────────────────────────
;;
;; resolve-pds hardcodes an https:// scheme (a 1:1 port of the original
;; TS, which does the same); this JDK-native mock only serves plain HTTP.
;; We exercise resolve-pds's real orchestration logic (did:web prefix
;; check, URL construction from the did string, did.json fetch, service
;; selection, and all 3 error paths) end-to-end by redirecting its
;; private `fetch-json` to this mock server (success path) or to a
;; canned response (error paths) -- a deliberate, documented test seam
;; (see pds.cljc's `fetch-json` docstring), not a workaround for a bug in
;; the port.
;;
;; `fetch-json` is intentionally `defn-` (private) in pds.cljc, so it
;; can't be named as a bare `pds/fetch-json` symbol from this namespace
;; (the compiler rejects that with \"var ... is not public\", and
;; `with-redefs`'s own binding-name position rejects a `#'ns/var` form
;; outright). `alter-var-root` on the `#'pds/fetch-json` Var itself
;; sidesteps both restrictions -- it operates on the Var as a value, not
;; via symbol resolution -- which is why the helper below uses it
;; instead of `with-redefs`.

(defn- with-stubbed-fetch-json
  "Temporarily replace pds.cljc's private `fetch-json` with
  `(make-stub original-fetch-json)`, run `thunk`, then restore it."
  [make-stub thunk]
  (let [v #'pds/fetch-json
        orig @v]
    (alter-var-root v (constantly (make-stub orig)))
    (try (thunk) (finally (alter-var-root v (constantly orig))))))

(deftest resolve-pds-success-test
  (with-stubbed-fetch-json
    (fn [orig-fetch-json]
      (fn [url]
        (let [path (str/replace url #"^https://[^/]+" "")]
          (orig-fetch-json (str *base-url* path)))))
    #(is (= "https://real-pds.etzhayyim.test" (pds/resolve-pds default-did)))))

(deftest resolve-pds-no-service-entry-test
  (with-stubbed-fetch-json
    (fn [_orig] (fn [_url] {:ok true :status 200 :body {:id default-did :service []}}))
    #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"no AtprotoPersonalDataServer service entry"
                           (pds/resolve-pds default-did)))))

(deftest resolve-pds-unreachable-test
  (with-stubbed-fetch-json
    (fn [_orig] (fn [_url] {:ok false :status 404 :body nil}))
    #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"did\.json not reachable"
                           (pds/resolve-pds default-did)))))

(deftest resolve-pds-unsupported-method-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did method not yet supported"
                         (pds/resolve-pds "did:plc:abc123"))))
