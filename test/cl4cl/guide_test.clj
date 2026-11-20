(ns cl4cl.guide-test
  "Keeps the guide and the docstrings honest about the public API."
  (:require [cl4cl.batches]
            [cl4cl.core :as core]
            [cl4cl.error]
            [cl4cl.json]
            [cl4cl.stream]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private public-namespaces
  '[cl4cl.core cl4cl.stream cl4cl.batches cl4cl.error cl4cl.json])

(def ^:private aliases
  "The aliases the guide's examples use."
  {"claude" "cl4cl.core" "stream" "cl4cl.stream" "batches" "cl4cl.batches"
   "error" "cl4cl.error" "json" "cl4cl.json"})

(defn- code-blocks [markdown]
  (map second (re-seq #"(?s)```clojure\n(.*?)```" markdown)))

(deftest every-public-var-has-a-docstring
  (doseq [n public-namespaces
          [sym v] (ns-publics n)]
    (is (not (str/blank? (:doc (meta v)))) (str n "/" sym " has no docstring"))))

(deftest the-guide-covers-every-function
  (let [guide (core/guide)]
    (doseq [n '[cl4cl.core cl4cl.batches]
            [sym v] (ns-publics n)
            :when (fn? @v)]
      (is (re-find (re-pattern (str "\\b" (java.util.regex.Pattern/quote (name sym)) "\\b")) guide)
          (str "the guide does not mention " n "/" sym)))))

(deftest the-guide-names-only-things-that-exist
  (doseq [[_ alias sym] (re-seq #"(?<![:\w.])(claude|stream|batches|error|json)/([a-z][a-z0-9?!*-]*)" (core/guide))]
    (is (ns-resolve (symbol (aliases alias)) (symbol sym))
        (str "the guide mentions " alias "/" sym ", which does not exist")))
  (doseq [[_ kw] (re-seq #"::error/([a-z-]+)" (core/guide))]
    (is (isa? (keyword "cl4cl.error" kw) :cl4cl.error/error)
        (str "the guide mentions ::error/" kw ", which is not an error type"))))

(deftest the-guide-examples-are-readable-clojure
  (let [blocks (code-blocks (core/guide))]
    (is (seq blocks))
    (doseq [block blocks]
      (is (seq (binding [*default-data-reader-fn* tagged-literal]
                 (read-string {:read-cond :allow} (str "[" (str/replace block "::error/" ":cl4cl.error/") "\n]"))))
          (str "unreadable example:\n" block)))))
