(ns cl4cl.spec-test
  "Ties cl4cl to the upstream spec. Each item in spec/implemented.edn must
  exist in the spec and pass the `verify` method for its kind."
  (:require [cl4cl.dev.checklist :as checklist]
            [cl4cl.dev.spec :as spec]
            [clojure.test :refer [deftest is testing]]))

(def spec (delay (spec/load-spec)))
(def items (delay (spec/items @spec)))

(defmulti verify
  "Does cl4cl implement this spec item? Dispatches on the item's :kind.
  Receives the parsed spec and the item, and returns truthy on success."
  (fn [_spec item] (:kind item)))

(defmethod verify :default [_ _] false)

(deftest spec-parses
  (testing "every kind of item is found, so an upstream format change is noticed"
    (let [found (set (map :kind @items))]
      (doseq [[kind] checklist/kinds]
        (is (found kind) (str "no items of kind " kind)))))
  (testing "well-known items are present"
    (let [keys (set (map spec/item-key @items))]
      (is (every? keys [[:endpoint "POST /v1/messages"]
                        [:request-param "max_tokens"]
                        [:stream-event "message_start"]
                        [:stream-event "ping"]
                        [:stream-delta "text_delta"]
                        [:response-block "tool_use"]
                        [:stop-reason "end_turn"]
                        [:error-type "overloaded_error"]])))))

(deftest implemented-items-are-in-the-spec
  (let [keys (set (map spec/item-key @items))]
    (doseq [k (checklist/load-implemented)]
      (is (keys k) (str k " is marked implemented but is not in the spec")))))

(deftest implemented-items-verify
  (let [implemented (checklist/load-implemented)]
    (doseq [item @items
            :when (implemented (spec/item-key item))]
      (is (verify @spec item) (str (spec/item-key item) " does not verify")))))

(deftest checklist-is-current
  (is (= (checklist/current) (slurp checklist/checklist-file))
      "doc/spec-checklist.md is stale; run `bb spec:checklist`"))
