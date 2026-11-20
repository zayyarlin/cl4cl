(ns cl4cl.json-test
  (:require [cl4cl.json :as json]
            [clojure.test :refer [deftest is]]))

(deftest keys-are-read-as-keywords
  (is (= {:max_tokens 1 :a {:b [1 nil true "s"]}}
         (json/read-str "{\"max_tokens\":1,\"a\":{\"b\":[1,null,true,\"s\"]}}"))))

(deftest keys-round-trip
  (doseq [text ["{\"a/b\":1}" "{\"a/b/c\":1}" "{\"with space\":1}" "{\"$ref\":\"#/x\"}" "{\"1\":1}"]]
    (is (= text (json/write-str (json/read-str text))))))

(deftest string-keys-are-written-as-they-are
  (is (= "{\"a/b\":1}" (json/write-str {"a/b" 1}))))

(deftest slashes-are-not-escaped
  (is (= "\"a/b\"" (json/write-str "a/b"))))
