(ns cl4cl.tools-test
  (:require [cl4cl.core :as core]
            [cl4cl.http :as http]
            [cl4cl.json :as json]
            [cl4cl.message :as message]
            [cl4cl.tools :as tools]
            [clojure.test :refer [deftest is testing]]))

(defn- tool-use [id name input]
  {:type "tool_use" :id id :name name :input input})

(defn- reply [stop-reason & content]
  {:type "message" :role "assistant" :stop_reason stop-reason :content (vec content)})

(defn- scripted
  "A :create function that answers with each reply in turn and records
  the bodies and opts it was given."
  [calls & replies]
  (let [remaining (atom replies)]
    (fn [_client body opts]
      (swap! calls conj {:body body :opts opts})
      (let [[next-reply] @remaining]
        (swap! remaining rest)
        (or next-reply (throw (ex-info "The script has no more replies" {})))))))

(def ^:private body
  {:model "m" :max_tokens 100 :messages [{:role "user" :content "Weather?"}]})

(def ^:private handlers
  {"get_weather" (fn [{:keys [city]}] {:city city :celsius 21})})

(deftest call-turns-a-tool-use-into-a-tool-result
  (testing "a value is written as JSON"
    (is (= {:type "tool_result" :tool_use_id "t1" :content "{\"city\":\"Paris\",\"celsius\":21}"}
           (tools/call handlers (tool-use "t1" "get_weather" {:city "Paris"}))))
    (is (= "[1,2]" (:content (tools/call {"f" (constantly [1 2])} (tool-use "t" "f" {})))))
    (is (= "null" (:content (tools/call {"f" (constantly nil)} (tool-use "t" "f" {}))))))
  (testing "a string or a vector of content blocks is the content as it is"
    (is (= "18°C" (:content (tools/call {"f" (constantly "18°C")} (tool-use "t" "f" {})))))
    (let [blocks [{:type "text" :text "See the chart."} {:type "image" :source {:type "url" :url "u"}}]]
      (is (= blocks (:content (tools/call {"f" (constantly blocks)} (tool-use "t" "f" {})))))))
  (testing "a handler that throws is an error result"
    (is (= {:type "tool_result" :tool_use_id "t" :is_error true :content "No such city"}
           (tools/call {"f" (fn [_] (throw (ex-info "No such city" {})))} (tool-use "t" "f" {}))))
    (is (= "class java.lang.NullPointerException"
           (:content (tools/call {"f" (fn [_] (throw (NullPointerException.)))} (tool-use "t" "f" {}))))))
  (testing "so is a tool with no handler"
    (is (= {:type "tool_result" :tool_use_id "t" :is_error true :content "Unknown tool: nope"}
           (tools/call handlers (tool-use "t" "nope" {})))))
  (testing "handlers may be any function of the name"
    (is (= "f!" (:content (tools/call (fn [name] (fn [_] (str name "!"))) (tool-use "t" "f" {}))))))
  (testing "an interrupt is not swallowed"
    (is (thrown? InterruptedException
                 (tools/call {"f" (fn [_] (throw (InterruptedException.)))} (tool-use "t" "f" {}))))))

(deftest run-loops-until-the-answer
  (let [calls (atom [])
        asks (reply "tool_use"
                    {:type "text" :text "Checking."}
                    (tool-use "t1" "get_weather" {:city "Paris"})
                    (tool-use "t2" "get_weather" {:city "Rome"}))
        answer (with-meta (reply "end_turn" {:type "text" :text "Both 21°C."})
                 {:cl4cl/request-id "req_2"})
        final (tools/run :client body handlers {:create (scripted calls asks answer) :betas ["b"]})
        after-tools (into (:messages body)
                          [{:role "assistant" :content (:content asks)}
                           {:role "user"
                            :content [{:type "tool_result" :tool_use_id "t1"
                                       :content "{\"city\":\"Paris\",\"celsius\":21}"}
                                      {:type "tool_result" :tool_use_id "t2"
                                       :content "{\"city\":\"Rome\",\"celsius\":21}"}]}])]
    (is (= answer final))
    (testing "every result goes back in one user message"
      (is (= [(:messages body) after-tools] (map (comp :messages :body) @calls))))
    (testing "the rest of the body is sent each time"
      (is (= [(dissoc body :messages) (dissoc body :messages)]
             (map #(dissoc (:body %) :messages) @calls))))
    (testing "per-call options reach create, without the loop's own"
      (is (= [{:betas ["b"]} {:betas ["b"]}] (map :opts @calls))))
    (testing "the final message carries the conversation and keeps its own metadata"
      (is (= (conj after-tools {:role "assistant" :content (:content answer)})
             (:messages (::tools/body (meta final)))))
      (is (= "req_2" (:cl4cl/request-id (meta final)))))))

(deftest the-loop-follows-the-stop-reason
  (testing "a paused turn is sent back with nothing added"
    (let [calls (atom [])
          paused (reply "pause_turn" {:type "server_tool_use" :id "s1" :name "web_search" :input {}})
          answer (reply "end_turn" {:type "text" :text "Done."})]
      (is (= answer (tools/run :client body handlers {:create (scripted calls paused answer)})))
      (is (= (conj (:messages body) {:role "assistant" :content (:content paused)})
             (:messages (:body (second @calls)))))))
  (testing "a tool call cut short by max_tokens is not run"
    (let [calls (atom [])
          ran (atom false)
          cut (reply "max_tokens" (tool-use "t1" "f" {}))]
      (is (= cut (tools/run :client body {"f" (fn [_] (reset! ran true))} {:create (scripted calls cut)})))
      (is (false? @ran))
      (is (= 1 (count @calls)))))
  (testing "pending? is what keeps it going"
    (is (= [true true false false false]
           (map #(tools/pending? {:stop_reason %})
                ["tool_use" "pause_turn" "end_turn" "max_tokens" "refusal"])))))

(deftest turns-are-reducible-and-seqable
  (let [asks #(reply "tool_use" (tool-use "t" "get_weather" {:city "Paris"}))
        answer (reply "end_turn" {:type "text" :text "21°C."})]
    (testing "every message, in order"
      (is (= ["tool_use" "tool_use" "end_turn"]
             (into [] (map :stop_reason)
                   (tools/turns :client body handlers {:create (scripted (atom []) (asks) (asks) answer)})))))
    (testing "take stops the loop, and no further request is made"
      (let [calls (atom [])]
        (is (= 2 (count (into [] (take 2)
                              (tools/turns :client body handlers
                                           {:create (apply scripted calls (repeatedly 5 asks))})))))
        (is (= 2 (count @calls)))))
    (testing "nothing is sent until it is consumed, and a sequence sends as it goes"
      (let [calls (atom [])
            turns (tools/turns :client body handlers {:create (scripted calls (asks) answer)})]
        (is (= 0 (count @calls)))
        (is (= "tool_use" (:stop_reason (first turns))))
        (is (= 1 (count @calls)))))))

(deftest step-is-one-turn
  (let [calls (atom [])
        asks (reply "tool_use" (tool-use "t1" "get_weather" {:city "Paris"}))
        message (tools/step :client body handlers {:create (scripted calls asks)})]
    (is (= asks message))
    (is (= 1 (count @calls)))
    (is (= ["user" "assistant" "user"] (map :role (:messages (::tools/body (meta message))))))))

(deftest results-can-be-replaced
  (let [calls (atom [])
        asks (reply "tool_use" (tool-use "t1" "get_weather" {:city "Paris"}))
        answer (reply "end_turn" {:type "text" :text "No."})
        deny (fn [_handlers message]
               (mapv #(hash-map :type "tool_result" :tool_use_id (:id %) :is_error true :content "Denied")
                     (message/tool-uses message)))]
    (tools/run :client body handlers {:create (scripted calls asks answer) :results deny})
    (is (= [{:type "tool_result" :tool_use_id "t1" :is_error true :content "Denied"}]
           (:content (peek (:messages (:body (second @calls)))))))))

(deftest create-is-the-default
  (let [client (core/client {:api-key "k" :retry {:max 0 :base-ms 0 :cap-ms 0}})
        sent (atom [])
        replies (atom [(reply "tool_use" (tool-use "t1" "get_weather" {:city "Paris"}))
                       (reply "end_turn" {:type "text" :text "21°C."})])]
    (with-redefs [http/send! (fn [_ request _]
                               (swap! sent conj request)
                               (let [[next-reply] @replies]
                                 (swap! replies rest)
                                 {:status 200 :headers {} :body (json/write-str next-reply)}))]
      (is (= "21°C." (message/text (tools/run client body handlers {:betas ["b"]})))))
    (is (= 2 (count @sent)))
    (is (= ["b" "b"] (map #(get-in % [:headers "anthropic-beta"]) @sent)))
    (is (= "tool_result" (-> (second @sent) :body json/read-str :messages peek :content first :type)))))
