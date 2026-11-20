(ns cl4cl.stream
  "Message stream events as data, and the fold that turns them back into
  the message a non-streaming call would have returned."
  (:require [cl4cl.error :as error]
            [cl4cl.json :as json]
            [cl4cl.sse :as sse]))

(defn payloads
  "A transducer from the lines of a Messages SSE stream to event payloads
  such as {:type \"content_block_delta\" :index 0 :delta {...}}. Pings are
  dropped, and an error event is thrown as the ex-info it describes."
  []
  (comp (sse/events)
        (map (comp json/read-str :data))
        (remove #(= "ping" (:type %)))
        (map (fn [payload]
               (if (= "error" (:type payload))
                 (throw (error/from-event payload))
                 payload)))))

(defmulti apply-delta
  "Fold a content block delta into its block. Dispatches on the delta's
  :type; a type with no method leaves the block unchanged."
  (fn [_block delta] (:type delta)))

(defmethod apply-delta :default [block _] block)

(defmethod apply-delta "text_delta" [block {:keys [text]}]
  (update block :text str text))

(defmethod apply-delta "thinking_delta" [block {:keys [thinking]}]
  (update block :thinking str thinking))

(defmethod apply-delta "signature_delta" [block {:keys [signature]}]
  (assoc block :signature signature))

(defmethod apply-delta "citations_delta" [block {:keys [citation]}]
  (update block :citations (fnil conj []) citation))

;; Tool input arrives as fragments of one JSON document. They are kept as
;; metadata on the block until the block stops, so the block itself always
;; has the shape the API documents.
(defmethod apply-delta "input_json_delta" [block {:keys [partial_json]}]
  (vary-meta block update ::partial-json str partial_json))

(defn- finish-block
  "Parse the tool input collected for a block, if any. Input that cannot
  be read, because max_tokens cut the tool call short or because it is
  nested too deeply, is left on the block's metadata under ::partial-json
  and :input stays as it was."
  [block]
  (let [partial (::partial-json (meta block))]
    (if (seq partial)
      (try
        (-> block
            (assoc :input (json/read-str partial))
            (vary-meta dissoc ::partial-json))
        (catch Exception _ block))
      block)))

(defn- update-block [message index f & args]
  (if (get-in message [:content index])
    (apply update-in message [:content index] f args)
    message))

(defn- start-block [message index block]
  (let [content (vec (:content message))
        padded (into content (repeat (- index (count content)) nil))]
    (assoc message :content (assoc padded index block))))

(defn accumulate
  "A reducing function that folds event payloads into a message. Use it
  with reduce and an init of nil, or with transduce. Unknown event types
  are ignored."
  ([] nil)
  ([message] message)
  ([message {:keys [type index] :as event}]
   (case type
     "message_start"       (:message event)
     "content_block_start" (start-block message index (:content_block event))
     "content_block_delta" (update-block message index apply-delta (:delta event))
     "content_block_stop"  (update-block message index finish-block)
     "message_delta"       (cond-> (merge message (:delta event))
                             (:usage event) (update :usage merge (:usage event)))
     message)))
