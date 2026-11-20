(ns cl4cl.integration-test
  "Calls the real API. Excluded from `lein test`; run with
  `lein test :integration`. Needs ANTHROPIC_API_KEY in .env or the
  environment."
  (:require [cl4cl.core :as core]
            [cl4cl.dev.env :as env]
            [cl4cl.error :as error]
            [clojure.test :refer [deftest is]]))

(def ^:private body
  {:model "claude-opus-5-5"
   :max_tokens 2048
   :messages [{:role "user" :content "Reply with the single word: pong"}]})

(defn- client [] (core/client {:api-key (env/api-key)}))

(defn- text [message]
  (apply str (keep :text (:content message))))

(deftest ^:integration create
  (let [message (core/create (client) body)]
    (is (= "message" (:type message)))
    (is (= "end_turn" (:stop_reason message)))
    (is (re-find #"(?i)pong" (text message)))
    (is (pos? (get-in message [:usage :output_tokens])))
    (is (string? (:cl4cl/request-id (meta message))))))

(deftest ^:integration stream
  (let [events (into [] (core/stream (client) body))
        message (core/final-message events)]
    (is (= "message_start" (:type (first events))))
    (is (= "message_stop" (:type (peek events))))
    (is (= "end_turn" (:stop_reason message)))
    (is (re-find #"(?i)pong" (text message)))))

(deftest ^:integration count-tokens
  (is (pos? (:input_tokens (core/count-tokens (client) (dissoc body :max_tokens))))))

(deftest ^:integration errors
  (let [data (try (core/create (client) (assoc body :model "not-a-model"))
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= ::error/not-found (:type data)))
    (is (= 404 (:status data)))))
