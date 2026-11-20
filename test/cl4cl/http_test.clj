(ns cl4cl.http-test
  (:require [cl4cl.error :as error]
            [cl4cl.http :as http]
            [clojure.test :refer [deftest is]])
  (:import [java.io ByteArrayInputStream]))

(defn- failing
  "A thunk that throws the given errors in turn, then returns :ok. The
  number of calls made is in the atom it is paired with."
  [& errors]
  (let [calls (atom 0)
        remaining (atom errors)]
    [calls (fn []
             (swap! calls inc)
             (if-let [e (first @remaining)]
               (do (swap! remaining rest) (throw e))
               :ok))]))

(defn- err [type & {:as data}] (ex-info "x" (assoc data :type type)))

(defn- policy [slept]
  {:max 2 :base-ms 100 :cap-ms 150 :sleep #(swap! slept conj %)})

(deftest retryable-errors-are-retried
  (let [slept (atom [])
        [calls f] (failing (err ::error/overloaded) (err ::error/rate-limit))]
    (is (= :ok (http/with-retry (policy slept) f)))
    (is (= 3 @calls))
    (is (= 2 (count @slept)))))

(deftest other-errors-are-not-retried
  (let [slept (atom [])
        [calls f] (failing (err ::error/invalid-request))]
    (is (thrown? clojure.lang.ExceptionInfo (http/with-retry (policy slept) f)))
    (is (= 1 @calls))
    (is (empty? @slept))))

(deftest retries-stop-at-max
  (let [slept (atom [])
        [calls f] (apply failing (repeat 5 (err ::error/api)))]
    (is (thrown? clojure.lang.ExceptionInfo (http/with-retry (policy slept) f)))
    (is (= 3 @calls))))

(deftest foreign-exceptions-propagate
  (let [[calls f] (failing (IllegalStateException. "x"))]
    (is (thrown? IllegalStateException (http/with-retry (policy (atom [])) f)))
    (is (= 1 @calls))))

(deftest backoff
  (let [p {:base-ms 100 :cap-ms 150}
        e (err ::error/api)]
    (is (= 75 (http/backoff-ms p 1 e 0.0)))
    (is (<= 99 (http/backoff-ms p 1 e 0.999) 100))
    (is (= 112 (http/backoff-ms p 2 e 0.0)) "capped at :cap-ms")
    (is (= 2000 (http/backoff-ms p 1 (err ::error/rate-limit :retry-after-ms 2000) 0.5))
        "the server's retry-after wins")
    (is (= 75 (http/backoff-ms p 1 (err ::error/rate-limit :retry-after-ms 86400000) 0.0))
        "unless it is longer than a minute")
    (is (= 75 (http/backoff-ms p 1 (err ::error/rate-limit :retry-after-ms -5000) 0.0))
        "or negative")
    (is (= 90000 (http/backoff-ms (assoc p :max-retry-after-ms 120000) 1
                                  (err ::error/rate-limit :retry-after-ms 90000) 0.0))
        "and the limit is part of the policy")))

(deftest idle-timeout-times-the-peer-not-the-caller
  (let [closed (atom false)
        body (proxy [ByteArrayInputStream] [(.getBytes "ab" "UTF-8")]
               (close [] (reset! closed true)))]
    (with-open [in (http/idle-timeout body 50)]
      (is (= (int \a) (.read in)))
      (Thread/sleep 200)
      (is (= (int \b) (.read in)) "a caller that is slow to read is not a silent peer")
      (is (= -1 (.read in))))
    (is @closed)))
