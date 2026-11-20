(ns cl4cl.sse-test
  (:require [cl4cl.sse :as sse]
            [clojure.test :refer [deftest is]]))

(defn- events [& lines] (into [] (sse/events) lines))

(deftest named-events
  (is (= [{:event "a" :data "1"} {:event "b" :data "2"}]
         (events "event: a" "data: 1" "" "event: b" "data: 2" ""))))

(deftest data-lines-join-with-newlines
  (is (= [{:data "x\ny"}] (events "data: x" "data: y" ""))))

(deftest only-one-leading-space-is-dropped
  (is (= [{:data " x"} {:data "y"}] (events "data:  x" "" "data:y" ""))))

(deftest comments-and-other-fields-are-ignored
  (is (= [{:data "x"}] (events ": keep-alive" "id: 7" "retry: 10" "data: x" ""))))

(deftest carriage-returns-are-stripped
  (is (= [{:event "a" :data "x"}] (events "event: a\r" "data: x\r" "\r"))))

(deftest blank-lines-without-data-emit-nothing
  (is (= [] (events "" "event: a" "" ""))))

(deftest an-unfinished-event-is-discarded
  (is (= [{:data "x"}] (events "data: x" "" "data: y"))))

(deftest each-use-has-its-own-state
  (let [xf (sse/events)]
    (is (= [] (into [] xf ["data: x"])))
    (is (= [{:data "y"}] (into [] xf ["data: y" ""])))))
