(ns cl4cl.message-test
  (:require [cl4cl.message :as message]
            [cl4cl.stream :as stream]
            [clojure.test :refer [deftest is testing]])
  (:import [clojure.lang ExceptionInfo]
           [java.util Base64]))

(def ^:private reply
  {:type "message" :role "assistant" :stop_reason "tool_use"
   :content [{:type "thinking" :thinking "Hm." :signature "s"}
             {:type "text" :text "Let me "}
             {:type "server_tool_use" :id "srv_1" :name "web_search" :input {}}
             {:type "tool_use" :id "t1" :name "a" :input {:x 1}}
             {:type "text" :text "check."}
             {:type "tool_use" :id "t2" :name "b" :input {}}]})

(deftest text-joins-the-text-blocks
  (is (= "Let me check." (message/text reply)))
  (is (= "" (message/text {:content [{:type "tool_use" :id "t" :name "a" :input {}}]})))
  (is (= "" (message/text {}))))

(deftest tool-uses-are-the-callers-to-answer
  (is (= ["t1" "t2"] (map :id (message/tool-uses reply))))
  (is (vector? (message/tool-uses reply)))
  (is (= [] (message/tool-uses {:content [{:type "text" :text "Hi"}]}))))

(deftest data-reads-the-text-as-json
  (is (= {:answer "42" :a/b [1 2]}
         (message/data {:content [{:type "text" :text "{\"answer\": \"42\","}
                                  {:type "text" :text " \"a/b\": [1, 2]}"}]})))
  (is (thrown? Exception (message/data {:content [{:type "text" :text "{\"answer\": "}]}))))

(deftest reply-and-say-append-turns
  (let [body {:model "m" :messages [{:role "user" :content "Hi"}]}]
    (testing "the reply's content goes back unchanged"
      (is (= {:model "m"
              :messages [{:role "user" :content "Hi"}
                         {:role "assistant" :content (:content reply)}]}
             (message/reply body reply))))
    (testing "a user turn is a string or blocks"
      (is (= {:role "user" :content "More."}
             (peek (:messages (message/say body "More.")))))
      (is (= {:role "user" :content [{:type "tool_result" :tool_use_id "t1" :content "1"}]}
             (peek (:messages (message/say body [{:type "tool_result" :tool_use_id "t1" :content "1"}]))))))
    (testing "they thread"
      (is (= ["user" "assistant" "user"]
             (map :role (:messages (-> body (message/reply reply) (message/say "More.")))))))
    (testing "messages that are a list, or missing, still grow at the end"
      (is (= ["user" "assistant" "user"]
             (map :role (:messages (-> (update body :messages seq) (message/reply reply) (message/say "More."))))))
      (is (= ["user"] (map :role (:messages (message/say {} "Hi"))))))
    (testing "the reply's own fields and metadata are not sent back"
      (is (= #{:role :content}
             (set (keys (peek (:messages (message/reply body (with-meta reply {:cl4cl/status 200})))))))))))

(deftest image-and-document-blocks
  (let [bytes (byte-array [1 2 3 -1])
        decode #(vec (.decode (Base64/getDecoder) ^String %))]
    (is (= {:type "image" :source {:type "base64" :media_type "image/png" :data "AQID/w=="}}
           (message/image bytes "image/png")))
    (is (= {:type "base64" :media_type "application/pdf"}
           (dissoc (:source (message/document bytes)) :data)))
    (is (= "document" (:type (message/document bytes))))
    (is (= [1 2 3 -1] (decode (get-in (message/document bytes) [:source :data]))))))

(deftest text-deltas-are-the-text-of-a-stream
  (is (= ["Hel" "lo"]
         (into [] (stream/text-deltas)
               [{:type "message_start" :message {:content []}}
                {:type "content_block_delta" :index 0 :delta {:type "thinking_delta" :thinking "Hm"}}
                {:type "content_block_delta" :index 1 :delta {:type "text_delta" :text "Hel"}}
                {:type "content_block_delta" :index 1 :delta {:type "text_delta" :text "lo"}}
                {:type "content_block_delta" :index 2 :delta {:type "input_json_delta" :partial_json "{"}}
                {:type "message_stop"}]))))
