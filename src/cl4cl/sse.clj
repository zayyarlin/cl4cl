(ns cl4cl.sse
  "Server-sent events as data: a transducer from lines to events."
  (:require [clojure.string :as str]))

(defn- field
  "Split an SSE line into [name value]. A line without a colon is a field
  with an empty value; one leading space of the value is dropped."
  [line]
  (let [i (str/index-of line ":")]
    (if i
      [(subs line 0 i) (let [v (subs line (inc i))]
                         (cond-> v (str/starts-with? v " ") (subs 1)))]
      [line ""])))

(defn events
  "A stateful transducer from the lines of an SSE stream to event maps
  {:event name, :data string}. :event is absent for unnamed events.
  Multiple data lines are joined with newlines. Comment lines and fields
  other than event and data are ignored, and an event that is still
  incomplete when the input ends is discarded, as the SSE standard says."
  []
  (fn [rf]
    (let [pending (volatile! nil)]
      (fn
        ([] (rf))
        ([acc] (rf acc))
        ([acc line]
         (let [line (cond-> line (str/ends-with? line "\r") (subs 0 (dec (count line))))]
           (cond
             (str/blank? line)
             (let [{:keys [data] :as event} @pending]
               (vreset! pending nil)
               (if data
                 (rf acc (assoc event :data (str/join "\n" data)))
                 acc))

             (str/starts-with? line ":")
             acc

             :else
             (let [[k v] (field line)]
               (case k
                 "event" (vswap! pending assoc :event v)
                 "data" (vswap! pending update :data (fnil conj []) v)
                 nil)
               acc))))))))
