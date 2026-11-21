(ns cl4cl.tools
  "The tool loop: call the API, run the tools Claude asks for, send the
  results back, and repeat until it answers. Built only on cl4cl.core's
  and cl4cl.message's public functions.

    (def handlers {\"get_weather\" (fn [{:keys [city]}] (lookup city))})
    (run client body handlers)             ; the final message

  body is an ordinary request body with the tools declared in :tools.
  handlers maps a tool's name to a function of its input.

  Each layer is public, for when the one above does not fit: `run` is
  the last of `turns`, `turns` repeats `step` while `pending?`, and
  `step` is one request followed by `results`, which is `call` for each
  tool use. A harness that needs to approve calls, run them in parallel
  or change the conversation between turns replaces the layer it needs
  and keeps the rest."
  (:require [cl4cl.core :as core]
            [cl4cl.json :as json]
            [cl4cl.message :as message]))

(defn- blocks? [value]
  (and (sequential? value) (seq value) (every? #(and (map? %) (:type %)) value)))

(defn- content
  "What a handler returned, as a tool_result's :content."
  [value]
  (if (or (string? value) (blocks? value)) value (json/write-str value)))

(defn call
  "Run the handler for one tool_use block and return its tool_result
  block. handlers is a map, or any function, from a tool's name to a
  function of its input, a map with keyword keys.

  A string or a vector of content blocks that the handler returns
  becomes the result's :content as it is; any other value is written as
  JSON. A handler that throws an Exception, or a name with no handler,
  gives a result with :is_error true and the message as its content, so
  that Claude can see the failure and react. An InterruptedException is
  rethrown."
  [handlers {:keys [id name input]}]
  (let [result {:type "tool_result" :tool_use_id id}]
    (if-let [handler (handlers name)]
      (try
        (assoc result :content (content (handler input)))
        (catch InterruptedException e
          (throw e))
        (catch Exception e
          (assoc result :is_error true :content (or (ex-message e) (str (class e))))))
      (assoc result :is_error true :content (str "Unknown tool: " name)))))

(defn results
  "The tool_result blocks answering every tool_use block of a message, in
  order, each made by `call`, one after another."
  [handlers message]
  (mapv #(call handlers %) (message/tool-uses message)))

(defn pending?
  "Is this message a step on the way and not the answer? True when its
  :stop_reason is \"tool_use\" or \"pause_turn\"."
  [message]
  (contains? #{"tool_use" "pause_turn"} (:stop_reason message)))

(defn step
  "One turn of the loop: create a message from body and, if it stopped
  to use tools, run them. Returns the message, as `create` does. Its
  metadata has :cl4cl.tools/body, the request body that continues the
  conversation: body with the message appended and, after it, one user
  message holding every tool result. Send that body next while the
  message is `pending?`.

  opts are as for cl4cl.core/create, and also:

    :create   a function [client body opts] that returns the message,
              in place of cl4cl.core/create, for example one that
              streams the reply and folds it with
              cl4cl.stream/accumulate
    :results  a function [handlers message] that returns the
              tool_result blocks, in place of `results`, for example
              one that asks for approval or runs the calls in parallel"
  ([client body handlers] (step client body handlers {}))
  ([client body handlers opts]
   (let [create (:create opts core/create)
         answer (:results opts results)
         message (create client body (dissoc opts :create :results))
         blocks (when (= "tool_use" (:stop_reason message))
                  (not-empty (answer handlers message)))]
     (vary-meta message assoc ::body (cond-> (message/reply body message)
                                       blocks (message/say blocks))))))

(defn turns
  "Every message of the tool loop, from the first reply to the answer:
  `step` repeated from body while the message is `pending?`. Returns a
  value that is both reducible and seqable. Nothing is sent until it is
  consumed, a sequence over it sends each request as it is reached, and
  each reduction starts the loop again. The loop has no limit of its
  own; stop it with `take` or `reduced`.

    (into [] (take 10) (turns client body handlers))

  opts are as for `step`."
  ([client body handlers] (turns client body handlers {}))
  ([client body handlers opts]
   (iteration #(step client % handlers opts)
              :initk body
              :kf #(when (pending? %) (::body (meta %))))))

(defn run
  "Run the tool loop to its end and return the final message, the last
  of `turns`. Its metadata has :cl4cl.tools/body, the whole conversation
  as a request body, this message included, ready for the next user turn
  with `cl4cl.message/say`. Check
  :stop_reason as after `create`: the loop also ends on \"max_tokens\"
  and \"refusal\".

  opts are as for `step`."
  ([client body handlers] (run client body handlers {}))
  ([client body handlers opts]
   (reduce (fn [_ message] message) nil (turns client body handlers opts))))
