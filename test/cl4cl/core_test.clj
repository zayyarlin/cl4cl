(ns cl4cl.core-test
  (:require [cl4cl.core :as core]
            [cl4cl.error :as error]
            [cl4cl.http :as http]
            [cl4cl.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [clojure.lang ExceptionInfo]
           [java.io ByteArrayInputStream]))

(def client
  (core/client {:api-key "k" :retry {:max 2 :base-ms 0 :cap-ms 0}}))

(defn- body-stream
  "An InputStream over text that records when it is closed."
  [text closed]
  (proxy [ByteArrayInputStream] [(.getBytes ^String text "UTF-8")]
    (close [] (reset! closed true))))

(defn- sse [& events]
  (str/join (for [e events] (str "event: " (:type e) "\ndata: " (json/write-str e) "\n\n"))))

(def ^:private hello
  (sse {:type "message_start" :message {:id "msg_1" :type "message" :content [] :stop_reason nil
                                        :usage {:input_tokens 3}}}
       {:type "content_block_start" :index 0 :content_block {:type "text" :text ""}}
       {:type "ping"}
       {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Hé"}}
       {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "llo"}}
       {:type "content_block_stop" :index 0}
       {:type "message_delta" :delta {:stop_reason "end_turn"} :usage {:output_tokens 2}}
       {:type "message_stop"}))

(defmacro ^:private with-responses
  "Run body with the network replaced by a function of [request as]."
  [f & body]
  `(with-redefs [http/send! (fn [_# request# as#] (~f request# as#))]
     ~@body))

(deftest request-is-data
  (let [r (core/request client {:model "m"} {:betas ["b2"] :headers {"x-extra" "1"}})]
    (is (= :post (:method r)))
    (is (= "https://api.anthropic.com/v1/messages" (:url r)))
    (is (= {"anthropic-version" "2026-10-21" "content-type" "application/json"
            "x-api-key" "k" "anthropic-beta" "b2" "x-extra" "1"}
           (:headers r)))
    (is (= {:model "m"} (json/read-str (:body r)))))
  (testing "client and call betas combine without repeats"
    (is (= "a,b" (get-in (core/request (assoc client :betas ["a"]) {} {:betas ["a" "b"]})
                         [:headers "anthropic-beta"]))))
  (testing "an auth token is a bearer header"
    (let [headers (:headers (core/request (core/client {:auth-token "t"}) {}))]
      (is (= "Bearer t" (headers "authorization")))
      (is (not (contains? headers "x-api-key")))))
  (testing "a request without a body, with a query"
    (let [r (core/request client nil {:method :get :path "/v1/x" :query {:limit 2 :after_id "a b&c" :skip nil}})]
      (is (= :get (:method r)))
      (is (= "https://api.anthropic.com/v1/x?limit=2&after_id=a+b%26c" (:url r)))
      (is (not (contains? r :body)))
      (is (not (contains? (:headers r) "content-type")))))
  (testing "a trailing slash on the base URL is harmless"
    (is (= "http://h/v1/messages" (:url (core/request (assoc client :base-url "http://h/") {}))))))

(deftest response-carries-http-details-as-metadata
  (let [r (core/response {:status 200 :headers {"request-id" "req_9"} :body "{\"id\":\"msg_1\"}"})]
    (is (= {:id "msg_1"} r))
    (is (= {:cl4cl/status 200 :cl4cl/request-id "req_9" :cl4cl/headers {"request-id" "req_9"}}
           (meta r)))))

(deftest error-responses-throw-data
  (testing "retry-after and a request id from the header"
    (let [d (try (core/response {:status 429
                                 :headers {"retry-after" "1.5" "request-id" "req_h"}
                                 :body "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow\"}}"})
                 (catch ExceptionInfo e (ex-data e)))]
      (is (= ::error/rate-limit (:type d)))
      (is (= 1500 (:retry-after-ms d)))
      (is (= "req_h" (:request-id d)))))
  (testing "a body that is not JSON"
    (let [d (try (core/response {:status 502 :headers {} :body "<html>Bad Gateway</html>"})
                 (catch ExceptionInfo e (ex-data e)))]
      (is (isa? (:type d) ::error/retryable))
      (is (isa? (:type d) ::error/unknown))
      (is (= "<html>Bad Gateway</html>" (:body d)))))
  (testing "an unknown error type on a status that is not worth retrying"
    (let [d (try (core/response {:status 418 :headers {} :body "{\"type\":\"error\",\"error\":{\"type\":\"teapot\"}}"})
                 (catch ExceptionInfo e (ex-data e)))]
      (is (= ::error/unknown (:type d)))
      (is (not (isa? (:type d) ::error/retryable))))))

(deftest create-owns-the-stream-flag
  (let [sent (atom nil)]
    (with-responses (fn [request _] (reset! sent request) {:status 200 :headers {} :body "{}"})
      (core/create client {:model "m" :stream true}))
    (is (= {:model "m"} (json/read-str (:body @sent))))))

(deftest create-retries
  (let [calls (atom 0)]
    (with-responses (fn [_ _]
                      (if (< (swap! calls inc) 3)
                        {:status 529 :headers {} :body "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}"}
                        {:status 200 :headers {} :body "{\"id\":\"msg_1\"}"}))
      (is (= {:id "msg_1"} (core/create client {}))))
    (is (= 3 @calls))))

(deftest stream-is-a-reducible-of-events
  (let [closed (atom false)
        sent (atom [])]
    (with-responses (fn [request as]
                      (swap! sent conj [request as])
                      {:status 200 :headers {} :body (body-stream hello closed)})
      (let [events (core/stream client {:model "m"})]
        (testing "nothing is sent until it is reduced"
          (is (empty? @sent)))
        (testing "events arrive as data, without pings"
          (is (= ["message_start" "content_block_start" "content_block_delta" "content_block_delta"
                  "content_block_stop" "message_delta" "message_stop"]
                 (into [] (map :type) events))))
        (is @closed)
        (testing "the request asks for a stream and has no total timeout"
          (let [[request as] (first @sent)]
            (is (= :stream as))
            (is (= {:model "m" :stream true} (json/read-str (:body request))))
            (is (not (contains? request :timeout-ms)))))
        (testing "it folds into the message create would return"
          (is (= {:id "msg_1" :type "message" :stop_reason "end_turn"
                  :content [{:type "text" :text "Héllo"}]
                  :usage {:input_tokens 3 :output_tokens 2}}
                 (core/final-message events))))
        (testing "each reduction is a new request"
          (is (= 2 (count @sent))))))))

(deftest stream-closes-when-reduction-stops-early
  (let [closed (atom false)]
    (with-responses (fn [_ _] {:status 200 :headers {} :body (body-stream hello closed)})
      (is (= ["message_start" "content_block_start"]
             (into [] (comp (map :type) (take 2)) (core/stream client {})))))
    (is @closed)))

(deftest stream-closes-when-the-reducing-function-throws
  (let [closed (atom false)]
    (with-responses (fn [_ _] {:status 200 :headers {} :body (body-stream hello closed)})
      (is (thrown? IllegalStateException
                   (run! (fn [_] (throw (IllegalStateException. "x"))) (core/stream client {})))))
    (is @closed)))

(deftest stream-errors
  (testing "a non-200 response throws the API's error, and is retried when retryable"
    (let [calls (atom 0)]
      (with-responses (fn [_ _]
                        (swap! calls inc)
                        {:status 529 :headers {}
                         :body (body-stream "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}" (atom nil))})
        (is (= ::error/overloaded
               (try (run! identity (core/stream client {}))
                    (catch ExceptionInfo e (:type (ex-data e)))))))
      (is (= 3 @calls))))
  (testing "an error event after the stream starts is thrown and not retried"
    (let [calls (atom 0)
          closed (atom false)
          body (sse {:type "message_start" :message {:content []}}
                    {:type "error" :error {:type "overloaded_error" :message "Overloaded"}})]
      (with-responses (fn [_ _] (swap! calls inc) {:status 200 :headers {} :body (body-stream body closed)})
        (is (= ::error/overloaded
               (try (core/final-message (core/stream client {}))
                    (catch ExceptionInfo e (:type (ex-data e)))))))
      (is (= 1 @calls))
      (is @closed))))
