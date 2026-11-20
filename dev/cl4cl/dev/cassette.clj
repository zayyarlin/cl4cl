(ns cl4cl.dev.cassette
  "Record real API exchanges once, replay them forever without a key.

  A scenario is data: a name, an operation and a request body. Recording
  runs it against the real API and writes a cassette, an EDN file holding
  the HTTP request cl4cl sent, the raw HTTP response, and the value cl4cl
  returned. Replaying serves the raw response from a local HTTP server, so
  the whole client runs, sockets included, and must send the same request
  and return the same value.

  Record with `lein record` (all scenarios) or `lein record NAME...`."
  (:require [cl4cl.core :as core]
            [cl4cl.dev.env :as env]
            [cl4cl.http :as http]
            [cl4cl.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayInputStream InputStream]
           [java.net InetSocketAddress]))

(def directory "test/cassettes")

;; Recordings are made with Claude Haiku 5.5 to keep them cheap.
(def ^:private model "claude-haiku-5-5")

(def ^:private weather-tool
  {:name "get_weather"
   :description "Get the current weather for a city."
   :input_schema {:type "object"
                  :properties {:city {:type "string" :description "City name"}}
                  :required ["city"]}})

(def ^:private save-tool
  {:name "save_note"
   :description "Save a note."
   :input_schema {:type "object"
                  :properties {:title {:type "string"} :text {:type "string"}}
                  :required ["title" "text"]}})

(defn- prompt [text] [{:role "user" :content text}])

(def scenarios
  "What gets recorded. :op is :create, :stream or :count-tokens."
  [{:name "create-text"
    :op :create
    :body {:model model :max_tokens 1024
           :messages (prompt "Reply with the single word: pong")}}
   {:name "stream-text-unicode"
    :op :stream
    :body {:model model :max_tokens 1024
           :messages (prompt "Repeat this line exactly, with nothing else: héllo wörld ✓ 日本語 🙂 a/b \"q\"")}}
   {:name "create-tool-use"
    :op :create
    :body {:model model :max_tokens 1024 :tools [weather-tool]
           :messages (prompt "Use the get_weather tool to check the weather in Paris.")}}
   {:name "stream-tool-use"
    :op :stream
    :body {:model model :max_tokens 1024 :tools [weather-tool]
           :messages (prompt "Use the get_weather tool to check the weather in Paris and in Tokyo.")}}
   {:name "stream-thinking"
    :op :stream
    :body {:model model :max_tokens 4096
           :thinking {:type "adaptive" :display "summarized"}
           :output_config {:effort "high"}
           :messages (prompt "Three people split a bill of 97.50 so that each pays 2.50 more than the one before. What does each pay? Reason it out, then answer in one line.")}}
   {:name "stream-tool-use-cut-off"
    :op :stream
    :body {:model model :max_tokens 60 :tools [save-tool]
           :thinking {:type "disabled"}
           :messages (prompt "Call save_note with the title 'Essay' and a 300 word essay about the sea as the text. Do not write anything before the tool call.")}}
   {:name "create-system-and-turns"
    :op :create
    :body {:model model :max_tokens 1024
           :system [{:type "text" :text "You answer in exactly one word."}]
           :messages [{:role "user" :content "Name a primary colour."}
                      {:role "assistant" :content [{:type "text" :text "Red"}]}
                      {:role "user" :content [{:type "text" :text "Another."}]}]}}
   {:name "count-tokens"
    :op :count-tokens
    :body {:model model :tools [weather-tool]
           :messages (prompt "Use the get_weather tool to check the weather in Paris.")}}
   {:name "create-error-not-found"
    :op :create
    :body {:model "not-a-model" :max_tokens 16 :messages (prompt "Hi")}}
   {:name "create-error-invalid-request"
    :op :create
    :body {:model model :messages (prompt "Hi")}}
   {:name "stream-error-not-found"
    :op :stream
    :body {:model "not-a-model" :max_tokens 16 :messages (prompt "Hi")}}])

(defn run
  "Run a scenario with a client and describe what cl4cl gave back, as
  plain data: {:value v} or {:error ex-data}. For a stream the value is
  {:events [...] :message m :block-meta [...]}."
  [client {:keys [op body]}]
  (try
    {:value
     (case op
       :create       (core/create client body)
       :count-tokens (core/count-tokens client body)
       :stream       (let [events (into [] (core/stream client body))
                           message (core/final-message events)]
                       {:events events
                        :message message
                        :block-meta (mapv #(not-empty (meta %)) (:content message))}))}
    (catch clojure.lang.ExceptionInfo e
      {:error (ex-data e)})))

;; --- files ---

(defn file [name] (io/file directory (str name ".edn")))

(defn load-cassette [name]
  (edn/read-string (slurp (file name))))

(defn- save! [name cassette]
  (io/make-parents (file name))
  (spit (file name)
        (binding [*print-namespace-maps* false]
          (with-out-str (pprint/pprint cassette)))))

;; --- recording ---

(def ^:private kept-response-headers
  "Response headers worth replaying. The rest identify the account or
  the moment and are dropped."
  ["content-type" "request-id" "retry-after"])

(defn- scrub-request [request]
  (-> request
      (update :headers #(cond-> %
                          (% "x-api-key") (assoc "x-api-key" "REDACTED")
                          (% "authorization") (assoc "authorization" "REDACTED")))
      (update :url #(str/replace % #"^https?://[^/]+" ""))))

(defn record!
  "Run a scenario against the real API and write its cassette."
  [{:keys [name] :as scenario}]
  (let [client (core/client {:api-key (env/api-key) :retry {:max 0 :base-ms 0 :cap-ms 0}})
        exchange (atom nil)
        send! http/send!
        result (with-redefs [http/send!
                             (fn [http-client request as]
                               (let [response (send! http-client request as)
                                     text (if (= :stream as)
                                            (with-open [in ^InputStream (:body response)]
                                              (String. (.readAllBytes in) "UTF-8"))
                                            (:body response))]
                                 (reset! exchange
                                         {:request (scrub-request request)
                                          :response (-> response
                                                        (update :headers select-keys kept-response-headers)
                                                        (assoc :body text))})
                                 (assoc response :body (if (= :stream as)
                                                         (ByteArrayInputStream. (.getBytes text "UTF-8"))
                                                         text))))]
                 (run client scenario))]
    (save! name (assoc @exchange :scenario name :result result))
    name))

(defn record-all!
  "Record the named scenarios, or all of them."
  [& names]
  (let [wanted (if (seq names) (set names) (constantly true))]
    (mapv record! (filter (comp wanted :name) scenarios))))

(defn -main [& names]
  (run! println (apply record-all! names))
  (shutdown-agents))

;; --- replaying ---

(defn- respond!
  "Write a recorded response. The body goes out in small pieces, cut
  without regard for lines or characters, as a network would."
  [^HttpExchange exchange {:keys [status headers body]}]
  (let [bytes (.getBytes ^String body "UTF-8")]
    (doseq [[k v] headers]
      (.add (.getResponseHeaders exchange) k v))
    (.sendResponseHeaders exchange status 0)
    (with-open [out (.getResponseBody exchange)]
      (doseq [start (range 0 (alength bytes) 7)]
        (.write out bytes (int start) (int (min 7 (- (alength bytes) start))))
        (.flush out)))))

(defn replay
  "Run a scenario against a local server that answers with the cassette's
  recorded response. Returns {:request what-the-server-received
  :result what-cl4cl-returned}, shaped like the cassette's own keys."
  [scenario {:keys [response]}]
  (let [received (atom nil)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (reset! received
                                {:method (keyword (str/lower-case (.getRequestMethod exchange)))
                                 :url (str (.getRequestURI exchange))
                                 :headers (into {} (for [[k vs] (.getRequestHeaders exchange)]
                                                     [(str/lower-case k) (str/join "," vs)]))
                                 :body (slurp (.getRequestBody exchange))})
                        (respond! exchange response))))
    (.start server)
    (try
      (let [client (core/client {:api-key "REDACTED"
                                 :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
                                 :retry {:max 0 :base-ms 0 :cap-ms 0}})
            result (run client scenario)]
        {:request @received :result result})
      (finally
        (.stop server 0)))))

(defn comparable-request
  "The parts of an HTTP request that cl4cl decides: the method, path,
  parsed body and the headers it sets. Headers the JDK adds are left out."
  [{:keys [method url headers body]}]
  {:method method
   :url url
   :headers (select-keys headers ["anthropic-version" "anthropic-beta" "content-type"
                                  "x-api-key" "authorization"])
   :body (some-> body json/read-str)})
