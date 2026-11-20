(ns cl4cl.replay-test
  "Regression tests from recorded API exchanges. Each cassette's raw
  response is served by a local HTTP server, so the real transport runs
  and no key is needed. See cl4cl.dev.cassette."
  (:require [cl4cl.dev.cassette :as cassette]
            [clojure.test :refer [deftest is testing]]))

(deftest every-scenario-has-a-cassette
  (doseq [{:keys [name]} cassette/scenarios]
    (is (.exists (cassette/file name))
        (str "no cassette for " name "; record it with `lein record " name "`"))))

(deftest cassettes-replay
  (doseq [{:keys [name] :as scenario} cassette/scenarios
          :when (.exists (cassette/file name))
          :let [recorded (cassette/load-cassette name)
                replayed (cassette/replay scenario recorded)]]
    (testing name
      (testing "cl4cl sends the request it sent when recording"
        (is (= (cassette/comparable-request (:request recorded))
               (cassette/comparable-request (:request replayed)))))
      (testing "cl4cl returns what it returned when recording"
        (is (= (:result recorded) (:result replayed))))
      (when-let [id (get-in recorded [:response :headers "request-id"])]
        (testing "the request id is reported"
          (let [{:keys [value error]} (:result replayed)]
            (is (= id (or (:request-id error)
                          (:cl4cl/request-id (meta value))
                          ;; a stream has no response map to carry it
                          (when (:events value) id))))))))))
