(ns cl4cl.json
  "JSON to and from Clojure data, with keys that round-trip.

  Object keys are read as keywords. A keyword is written as its full text,
  namespace included, so a key such as \"a/b\" in a tool input survives
  being read as :a/b and written back."
  (:require [clojure.data.json :as json]))

(defn- key->str [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn write-str [x]
  (json/write-str x :key-fn key->str :escape-slash false))

(defn read-str [s]
  (json/read-str s :key-fn keyword))
