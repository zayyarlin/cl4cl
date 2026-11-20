(ns cl4cl.http
  "The only namespace that touches the network. Sends request maps with
  java.net.http and retries failures according to a policy map."
  (:require [cl4cl.error :as error]
            [clojure.string :as str])
  (:import [java.io IOException]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn http-client
  "A java.net.http client. One instance is meant to be shared."
  ^HttpClient [{:keys [connect-timeout-ms]}]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis connect-timeout-ms))
      (.build)))

(defn- build ^HttpRequest [{:keys [method url headers body timeout-ms]}]
  (let [builder (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers]
      (.header builder k v))
    (when timeout-ms
      (.timeout builder (Duration/ofMillis timeout-ms)))
    (.method builder
             (str/upper-case (name method))
             (if body
               (HttpRequest$BodyPublishers/ofString body)
               (HttpRequest$BodyPublishers/noBody)))
    (.build builder)))

(defn- headers-map
  "Response headers as a map of lower-case name to value. Repeated
  headers are joined with commas."
  [^HttpResponse response]
  (into {}
        (map (fn [[k vs]] [(str/lower-case k) (str/join "," vs)]))
        (.map (.headers response))))

(defn send!
  "Send a request map {:method :url :headers :body :timeout-ms} and return
  {:status :headers :body}. :body is a string, or an open InputStream when
  as is :stream; the caller must then close it. A request that gets no
  response throws a :cl4cl.error/connection ex-info."
  [^HttpClient http-client request as]
  (try
    (let [handler (case as
                    :string (HttpResponse$BodyHandlers/ofString)
                    :stream (HttpResponse$BodyHandlers/ofInputStream))
          response (.send http-client (build request) handler)]
      {:status (.statusCode response)
       :headers (headers-map response)
       :body (.body response)})
    (catch IOException e
      (throw (error/connection e)))))

(defn backoff-ms
  "How long to wait before retry number attempt (from 1): the server's
  retry-after if it sent one of at most :max-retry-after-ms (default
  60000), else exponential backoff from :base-ms, capped at :cap-ms,
  scaled by jitter, a number in [0, 1). A longer retry-after is ignored
  rather than obeyed, so a response cannot park the caller for hours."
  [{:keys [base-ms cap-ms max-retry-after-ms] :or {max-retry-after-ms 60000}} attempt e jitter]
  (let [retry-after (:retry-after-ms (ex-data e))]
    (if (and retry-after (<= 0 retry-after max-retry-after-ms))
      retry-after
      (let [ceiling (min cap-ms (* base-ms (bit-shift-left 1 (dec attempt))))]
        (long (* ceiling (+ 0.75 (* 0.25 jitter))))))))

(defn with-retry
  "Call the thunk f, retrying while it throws a retryable cl4cl error, up
  to :max extra attempts. :sleep, a function of milliseconds, is optional
  and exists so tests need not wait."
  [{:keys [max sleep] :or {sleep #(Thread/sleep (long %))} :as policy} f]
  (loop [attempt 1]
    (let [result (try
                   {:value (f)}
                   (catch clojure.lang.ExceptionInfo e
                     (if (and (error/retryable? e) (<= attempt max))
                       {:retry e}
                       (throw e))))]
      (if-let [e (:retry result)]
        (do (sleep (backoff-ms policy attempt e (rand)))
            (recur (inc attempt)))
        (:value result)))))
