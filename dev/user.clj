(ns user
  "Where `lein repl` starts. Conveniences for trying cl4cl by hand.

    (ask \"What is a transducer?\")     ; one request, returns the message
    (say \"Write a haiku about parens\") ; streams the text as it arrives
    (text *1)                          ; the text of a message

  The API key is read from .env (ANTHROPIC_API_KEY=...) or the environment."
  (:require [cl4cl.core :as claude]
            [cl4cl.dev.env :as env]
            [cl4cl.stream :as stream]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]))

(def model "claude-opus-5-5")

(defn client
  "A client using the key in .env or the environment."
  []
  (claude/client (when-let [k (env/api-key)] {:api-key k})))

(defn body
  "A request body for a single user prompt. Extra keys are merged in:
  (body \"Hi\" {:max_tokens 100 :system \"Be brief.\"})."
  ([prompt] (body prompt {}))
  ([prompt extra]
   (merge {:model model
           :max_tokens 16000
           ;; If a safety classifier declines the request, let the API
           ;; re-run it on a fallback model. Needs the beta in `opts`.
           :fallbacks "default"
           :messages [{:role "user" :content prompt}]}
          extra)))

(def opts {:betas ["server-side-fallback-2026-07-01"]})

(defn text
  "The text of a message, without thinking or tool blocks."
  [message]
  (str/join (keep #(when (= "text" (:type %)) (:text %)) (:content message))))

(defn ask
  "Send a prompt and return the whole message."
  ([prompt] (ask prompt {}))
  ([prompt extra]
   (claude/create (client) (body prompt extra) opts)))

(defn- print-text-delta [event]
  (when (= "text_delta" (get-in event [:delta :type]))
    (print (get-in event [:delta :text]))
    (flush))
  event)

(defn say
  "Send a prompt, print the reply as it streams, and return the message."
  ([prompt] (say prompt {}))
  ([prompt extra]
   (let [message (transduce (map print-text-delta)
                            stream/accumulate
                            (claude/stream (client) (body prompt (merge {:max_tokens 64000} extra)) opts))]
     (println)
     message)))
