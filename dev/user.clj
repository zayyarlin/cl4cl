(ns user
  "Where `lein repl` starts. Conveniences for trying cl4cl by hand.

    (ask \"What is a transducer?\")     ; one request, returns the message
    (say \"Write a haiku about parens\") ; streams the text as it arrives
    (text *1)                          ; the text of a message
    (use-model! \"claude-opus-5-5\")   ; switch model for later calls
    (ask \"Hi\" {:model \"claude-sonnet-5-5\"}) ; or for one call

  These are development helpers, not part of the library. The API key is read from .env (ANTHROPIC_API_KEY=...) or the environment."
  (:require [cl4cl.core :as claude]
            [cl4cl.dev.env :as env]
            [cl4cl.stream :as stream]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]))

(def default-model
  "Claude Haiku 5.5, to keep experiments cheap. CL4CL_MODEL in .env overrides it."
  "claude-haiku-5-5")

(defonce ^:private chosen-model (atom nil))

(defn model
  "The model the helpers use: the one set with `use-model!`, else
  CL4CL_MODEL from .env, else `default-model`."
  []
  (or @chosen-model (env/setting "CL4CL_MODEL") default-model))

(defn use-model!
  "Use this model for later calls. (use-model! nil) goes back to the default."
  [id]
  (reset! chosen-model id)
  (model))

(defn client
  "A client using the key in .env or the environment."
  []
  (claude/client (when-let [k (env/api-key)] {:api-key k})))

(defn body
  "A request body for a single user prompt. Extra keys are merged in:
  (body \"Hi\" {:max_tokens 100 :system \"Be brief.\"})."
  ([prompt] (body prompt {}))
  ([prompt extra]
   (merge {:model (model)
           :max_tokens 16000
           :messages [{:role "user" :content prompt}]}
          extra)))

(defn text
  "The text of a message, without thinking or tool blocks."
  [message]
  (str/join (keep #(when (= "text" (:type %)) (:text %)) (:content message))))

(defn ask
  "Send a prompt and return the whole message."
  ([prompt] (ask prompt {}))
  ([prompt extra]
   (claude/create (client) (body prompt extra))))

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
                            (claude/stream (client) (body prompt (merge {:max_tokens 64000} extra))))]
     (println)
     message)))
