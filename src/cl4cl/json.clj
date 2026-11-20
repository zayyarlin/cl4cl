(ns cl4cl.json
  "JSON to and from Clojure data, with keys that round-trip.

  Object keys are read as keywords. A keyword is written as its full text,
  namespace included, so a key such as \"a/b\" in a tool input survives
  being read as :a/b and written back."
  (:require [clojure.data.json :as json]))

(defn- key->str [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- too-deep
  "The failure for JSON nested more deeply than the stack allows. It is an
  ex-info, because a StackOverflowError is not an Exception and would get
  past a caller's (catch Exception ...), and the nesting of a tool input
  is the model's choice."
  [action]
  (ex-info (str "JSON is nested too deeply to " action) {:type :cl4cl.error/unknown}))

(defn write-str
  "Data to JSON text. A keyword key keeps its namespace. Data nested too
  deeply to write throws an ex-info of type :cl4cl.error/unknown."
  [x]
  (try
    (json/write-str x :key-fn key->str :escape-slash false)
    (catch StackOverflowError _ (throw (too-deep "write")))))

(defn read-str
  "JSON text to data, with object keys as keywords. Text nested too deeply
  to read throws an ex-info of type :cl4cl.error/unknown."
  [s]
  (try
    (json/read-str s :key-fn keyword)
    (catch StackOverflowError _ (throw (too-deep "read")))))
