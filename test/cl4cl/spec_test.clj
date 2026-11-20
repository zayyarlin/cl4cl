(ns cl4cl.spec-test
  "Ties cl4cl to the upstream spec. Each item in spec/implemented.edn must
  exist in the spec and pass the `verify` method for its kind. The inputs
  come from `spec/sample`, so they follow the spec as it changes."
  (:require [cl4cl.core :as core]
            [cl4cl.dev.checklist :as checklist]
            [cl4cl.dev.spec :as spec]
            [cl4cl.error :as error]
            [cl4cl.http :as http]
            [cl4cl.json :as json]
            [cl4cl.stream :as stream]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [clojure.lang ExceptionInfo]
           [java.io ByteArrayInputStream]))

(def spec (delay (spec/load-spec)))
(def items (delay (spec/items @spec)))
(def reference (delay (:messages @spec)))

(def client (core/client {:api-key "test-key"}))

;; --- building inputs from the spec ---

(defn- sample-type [title]
  (spec/sample @reference (spec/domain-type @reference title)))

(defn- sample-variant [title id]
  (some->> (spec/variant (spec/domain-type @reference title) id)
           (spec/sample @reference)))

(defn- sample-param [endpoint id]
  (some->> (:nodes (spec/section @reference endpoint "Body parameters"))
           (filter #(= id (:name %)))
           first
           (spec/sample @reference)))

(defn- block-events
  "The events that deliver one content block at an index. Text, thinking
  and tool input arrive as deltas, as they do from the API."
  [index block]
  (let [delta (fn [d] {:type "content_block_delta" :index index :delta d})
        [start deltas]
        (case (:type block)
          "text"     [(assoc block :text "")
                      [(delta {:type "text_delta" :text (:text block)})]]
          "thinking" [(assoc block :thinking "" :signature "")
                      [(delta {:type "thinking_delta" :thinking (:thinking block)})
                       (delta {:type "signature_delta" :signature (:signature block)})]]
          ("tool_use" "server_tool_use")
          [(assoc block :input {})
           [(delta {:type "input_json_delta" :partial_json (json/write-str (:input block))})]]
          [block []])]
    (concat [{:type "content_block_start" :index index :content_block start}]
            deltas
            [{:type "content_block_stop" :index index}])))

(def ^:private final-fields [:stop_reason :stop_sequence :stop_details :container])

(defn- message->events
  "The event stream the API would send for a message."
  [message]
  (concat [{:type "message_start"
            :message (merge message
                            {:content []}
                            (zipmap final-fields (repeat nil))
                            {:usage (select-keys (:usage message) [:input_tokens])})}]
          (mapcat block-events (range) (:content message))
          [{:type "message_delta"
            :delta (select-keys message final-fields)
            :usage (:usage message)}
           {:type "message_stop"}]))

(defn- sse-lines [events]
  (mapcat (fn [e] [(str "event: " (:type e)) (str "data: " (json/write-str e)) ""])
          events))

(defn- accumulated [events]
  (transduce (stream/payloads) stream/accumulate (sse-lines events)))

(defn- returned [message]
  (core/response {:status 200 :headers {} :body (json/write-str message)}))

(defn- survives?
  "Does a message come back unchanged both as a response body and as a stream?"
  [message]
  (and (= message (returned message))
       (= message (accumulated (message->events message)))))

(defn- wire
  "The JSON body of an HTTP request map, read back as data."
  [http-request]
  (json/read-str (:body http-request)))

(defn- passes-through? [body opts]
  (= body (wire (core/request client body opts))))

(defn- thrown-data [http-response]
  (try (core/response http-response)
       nil
       (catch ExceptionInfo e (ex-data e))))

(defn- error-response [status error]
  {:status status
   :headers {}
   :body (json/write-str {:type "error" :error error :request_id "req_1"})})

(defn- requests-sent
  "The HTTP request maps that calling f sends, with the network stubbed out."
  [f]
  (let [sent (atom [])]
    (with-redefs [http/send! (fn [_ request as]
                               (swap! sent conj request)
                               {:status 200
                                :headers {}
                                :body (case as
                                        :string "{}"
                                        :stream (ByteArrayInputStream. (byte-array 0)))})]
      (f))
    @sent))

;; --- verify: one method per kind of spec item ---

(defmulti verify
  "Does cl4cl implement this spec item? Dispatches on the item's :kind and
  returns truthy on success."
  :kind)

(defmethod verify :default [_] false)

(defmethod verify :api-version [{:keys [id]}]
  (= id (get-in (core/request client {}) [:headers "anthropic-version"])))

(def ^:private endpoint-calls
  "The public functions that call each endpoint."
  {"POST /v1/messages"              [#(core/create client {})
                                     #(run! identity (core/stream client {}))]
   "POST /v1/messages/count_tokens" [#(core/count-tokens client {})]})

(defmethod verify :endpoint [{:keys [id]}]
  (let [[verb path] (str/split id #" ")
        calls (endpoint-calls id)]
    (and (seq calls)
         (every? (fn [call]
                   (let [[{:keys [method url]}] (requests-sent call)]
                     (and (= verb (str/upper-case (name method)))
                          (= (str (:base-url client) path) url))))
                 calls))))

(defmethod verify :request-header [{:keys [id]}]
  (= "v" (get-in (core/request client {} {:headers {id "v"}}) [:headers id])))

(defmethod verify :request-param [{:keys [id]}]
  (when-some [v (sample-param "Create a Message" id)]
    (passes-through? {(keyword id) v} {})))

(defmethod verify :count-tokens-param [{:keys [id]}]
  (when-some [v (sample-param "Count tokens in a Message" id)]
    (passes-through? {(keyword id) v} {:path "/v1/messages/count_tokens"})))

(defmethod verify :request-block [{:keys [id]}]
  (when-some [block (sample-variant "Content Block Param" id)]
    (passes-through? {:messages [{:role "user" :content [block]}]} {})))

(defmethod verify :tool [{:keys [id]}]
  (when-some [tool (sample-variant "Tool Union" id)]
    (passes-through? {:tools [tool]} {})))

(defmethod verify :response-field [{:keys [id]}]
  (let [message (sample-type "Message")]
    (and (contains? message (keyword id))
         (survives? message))))

(defmethod verify :response-block [{:keys [id]}]
  (when-some [block (sample-variant "Content Block" id)]
    (survives? (assoc (sample-type "Message") :content [block]))))

(defmethod verify :stop-reason [{:keys [id]}]
  (survives? (assoc (sample-type "Message") :stop_reason id)))

(defmethod verify :stream-event [{:keys [id]}]
  (case id
    "ping"  (empty? (into [] (stream/payloads) (sse-lines [{:type "ping"}])))
    "error" (= ::error/overloaded
               (try (accumulated [{:type "error"
                                   :error {:type "overloaded_error" :message "Overloaded"}}])
                    (catch ExceptionInfo e (:type (ex-data e)))))
    ;; Any other event must reach the caller as the spec describes it, and
    ;; must be one of the events that a full message is rebuilt from.
    (let [event (sample-variant "Raw Message Stream Event" id)
          message (sample-type "Message")
          events (message->events message)]
      (and event
           (= [event] (into [] (stream/payloads) (sse-lines [event])))
           (some #(= id (:type %)) events)
           (= message (accumulated events))))))

(def ^:private delta-effects
  "For each delta type: the block it applies to, a function that splits
  the spec's sample delta into the deltas to send, and the block that
  must result."
  (let [twice (fn [d] [d d])
        input {:a [1 "/"] :b {:c nil}}]
    {"text_delta"       {:block {:type "text" :text ""}
                         :deltas twice
                         :expect (fn [d] {:type "text" :text (str (:text d) (:text d))})}
     "thinking_delta"   {:block {:type "thinking" :thinking "" :signature ""}
                         :deltas twice
                         :expect (fn [d] {:type "thinking" :signature ""
                                          :thinking (str (:thinking d) (:thinking d))})}
     "signature_delta"  {:block {:type "thinking" :thinking "t" :signature ""}
                         :deltas vector
                         :expect (fn [d] {:type "thinking" :thinking "t" :signature (:signature d)})}
     "citations_delta"  {:block {:type "text" :text "t"}
                         :deltas twice
                         :expect (fn [d] {:type "text" :text "t" :citations [(:citation d) (:citation d)]})}
     "input_json_delta" {:block {:type "tool_use" :id "toolu_1" :name "f" :input {}}
                         :deltas (fn [d]
                                   (let [text (json/write-str input)]
                                     (for [part (split-at 7 text)]
                                       (assoc d :partial_json (apply str part)))))
                         :expect (fn [d]
                                   (when (contains? d :partial_json)
                                     {:type "tool_use" :id "toolu_1" :name "f" :input input}))}}))

(defmethod verify :stream-delta [{:keys [id]}]
  (let [delta (sample-variant "Raw Content Block Delta" id)
        {:keys [block deltas expect]} (delta-effects id)]
    (and delta block
         (= (expect delta)
            (-> (accumulated
                 (concat [{:type "message_start" :message {:content []}}
                          {:type "content_block_start" :index 0 :content_block block}]
                         (for [d (deltas delta)]
                           {:type "content_block_delta" :index 0 :delta d})
                         [{:type "content_block_stop" :index 0}]))
                (get-in [:content 0]))))))

(defmethod verify :error-type [{:keys [id status]}]
  (let [data (thrown-data (error-response status {:type id :message "m"}))]
    (and (= id (:error-type data))
         (= status (:status data))
         (= "m" (:message data))
         (= "req_1" (:request-id data))
         (isa? (:type data) ::error/error)
         (not (isa? (:type data) ::error/unknown)))))

(defmethod verify :error-code [{:keys [id status]}]
  (let [data (thrown-data (error-response status {:type "invalid_request_error"
                                                  :message "m"
                                                  :details {:error_code id}}))]
    (and (= id (:error-code data))
         (= status (:status data)))))

(defn verified
  "The keys of every spec item that verifies, in spec order. The contents
  of spec/implemented.edn should equal this."
  []
  (vec (for [item @items :when (verify item)] (spec/item-key item))))

;; --- tests ---

(deftest spec-parses
  (testing "every kind of item is found, so an upstream format change is noticed"
    (let [found (set (map :kind @items))]
      (doseq [[kind] checklist/kinds]
        (is (found kind) (str "no items of kind " kind)))))
  (testing "well-known items are present"
    (let [keys (set (map spec/item-key @items))]
      (is (every? keys [[:endpoint "POST /v1/messages"]
                        [:request-param "max_tokens"]
                        [:stream-event "message_start"]
                        [:stream-event "ping"]
                        [:stream-delta "text_delta"]
                        [:response-block "tool_use"]
                        [:stop-reason "end_turn"]
                        [:error-type "overloaded_error"]])))))

(deftest implemented-items-are-in-the-spec
  (let [keys (set (map spec/item-key @items))]
    (doseq [k (checklist/load-implemented)]
      (is (keys k) (str k " is marked implemented but is not in the spec")))))

(deftest implemented-items-verify
  (let [implemented (checklist/load-implemented)]
    (doseq [item @items
            :when (implemented (spec/item-key item))]
      (is (verify item) (str (spec/item-key item) " does not verify")))))

(deftest checklist-is-current
  (is (= (checklist/current) (slurp checklist/checklist-file))
      "doc/spec-checklist.md is stale; run `bb spec:checklist`"))

(deftest nothing-in-the-spec-breaks-a-stream
  (testing "every event, delta and block the spec lists, implemented or not, and ones it does not"
    (let [of-kind (fn [kind] (map :id (filter #(= kind (:kind %)) @items)))
          blocks (keep #(sample-variant "Content Block" %) (of-kind :response-block))
          deltas (keep #(sample-variant "Raw Content Block Delta" %) (of-kind :stream-delta))
          events (concat
                  [{:type "message_start" :message (assoc (sample-type "Message") :content [])}]
                  (mapcat block-events (range) blocks)
                  ;; every delta against every block, matching or not
                  (for [i (range (count blocks)), d deltas]
                    {:type "content_block_delta" :index i :delta d})
                  [{:type "content_block_delta" :index 0 :delta {:type "delta_from_the_future" :x 1}}
                   {:type "content_block_delta" :index 99 :delta (first deltas)}
                   {:type "content_block_start" :index (count blocks)
                    :content_block {:type "block_from_the_future"}}
                   {:type "event_from_the_future" :x 1}
                   {:type "ping"}
                   {:type "message_stop"}])
          message (accumulated events)]
      (is (= (inc (count blocks)) (count (:content message))))
      (is (= "message" (:type message))))))

(defn- parses? [line]
  (or (not (str/starts-with? line "data:"))
      (try (json/read-str (subs line (count "data:"))) true
           (catch Exception _ false))))

(defn- sse-examples
  "The complete stream transcripts in the streaming guide. A transcript
  whose JSON the guide abbreviates with ... cannot be replayed and is
  left out."
  []
  (->> (str/split (slurp (str spec/upstream "/streaming.md")) #"```sse Response\n")
       rest
       (map #(first (str/split % #"\n\s*```")))
       (map #(map str/triml (str/split-lines %)))
       (filter #(every? parses? %))))

(deftest documented-streams-accumulate
  (let [examples (sse-examples)]
    (is (<= 3 (count examples)) "too few replayable transcripts in streaming.md")
    (doseq [lines examples
            :let [message (transduce (stream/payloads) stream/accumulate lines)]]
      (is (= "message" (:type message)))
      (is (string? (:stop_reason message)))
      (is (seq (:content message)))
      (doseq [block (:content message)]
        (is (string? (:type block)))
        (when (#{"tool_use" "server_tool_use"} (:type block))
          (is (map? (:input block)) "tool input is parsed"))))))

(deftest verify-rejects-what-cl4cl-does-not-handle
  (doseq [item [{:kind :stream-delta :id "delta_from_the_future"}
                {:kind :stream-event :id "event_from_the_future"}
                {:kind :error-type :id "error_from_the_future" :status 418}
                {:kind :request-param :id "not_a_param"}
                {:kind :response-block :id "not_a_block"}
                {:kind :endpoint :id "GET /v1/not_an_endpoint"}
                {:kind :api-version :id "2099-01-01"}
                {:kind :kind-from-the-future :id "x"}]]
    (is (not (verify item)) (str (spec/item-key item) " should not verify"))))
