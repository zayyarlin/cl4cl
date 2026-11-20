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
      (testing "cl4cl sends the requests it sent when recording"
        (is (= (map (comp cassette/comparable-request :request) (:exchanges recorded))
               (map cassette/comparable-request (:requests replayed)))))
      (testing "cl4cl returns what it returned when recording"
        (is (= (:result recorded) (:result replayed))))
      (when-let [id (get-in recorded [:exchanges 0 :response :headers "request-id"])]
        (let [{:keys [value error]} (:result replayed)]
          (when (or error (:cl4cl/status (meta value)))
            (testing "the request id is reported"
              (is (= id (or (:request-id error) (:cl4cl/request-id (meta value))))))))))))
