(ns cl4cl.http
  "The only namespace that touches the network. Sends request maps with
  java.net.http and retries failures according to a policy map."
  (:require [cl4cl.error :as error]
            [clojure.string :as str])
  (:import [java.io IOException InputStream]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers HttpTimeoutException]
           [java.time Duration]
           [java.util.concurrent ExecutionException ScheduledFuture
            ScheduledThreadPoolExecutor ThreadFactory TimeUnit TimeoutException]))

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

(def ^:private timer
  "One daemon thread that checks every open body for idleness. The first
  body with an idle timeout starts it."
  (delay
    (doto (ScheduledThreadPoolExecutor.
           1 (reify ThreadFactory
               (newThread [_ runnable]
                 (doto (Thread. runnable "cl4cl-idle-timer") (.setDaemon true)))))
      (.setRemoveOnCancelPolicy true))))

(defn idle-timeout
  "Wrap an open response body so that a read which waits more than
  timeout-ms for data throws an HttpTimeoutException, where the JDK's
  stream would block for as long as the peer stays silent. Only the wait
  for data is timed, so a caller that is slow to come back for more is
  not idle. Closing the result closes the body."
  ^InputStream [^InputStream body timeout-ms]
  (let [state (atom :open)        ; :open, :expired or :closed
        waiting (volatile! nil)   ; when the read in progress began, in ns
        pending (volatile! nil)   ; the next check
        check (fn check []
                (when (= :open @state)
                  (let [since @waiting
                        left (if since
                               (- timeout-ms (quot (- (System/nanoTime) (long since)) 1000000))
                               timeout-ms)]
                    (if (pos? left)
                      (vreset! pending (.schedule ^ScheduledThreadPoolExecutor @timer
                                                  ^Runnable check (long left) TimeUnit/MILLISECONDS))
                      ;; Closing the body is what ends the blocked read.
                      (when (compare-and-set! state :open :expired)
                        (.close body))))))
        timed (fn [read]
                (vreset! waiting (System/nanoTime))
                (try
                  (read)
                  (finally
                    (vreset! waiting nil)
                    (when (= :expired @state)
                      (throw (HttpTimeoutException.
                              (str "no data arrived for " timeout-ms " ms")))))))]
    (check)
    (proxy [InputStream] []
      (read
        ([] (timed #(.read body)))
        ([b] (timed #(.read body ^bytes b)))
        ([b off len] (timed #(.read body ^bytes b (int off) (int len)))))
      (available [] (.available body))
      (close []
        (compare-and-set! state :open :closed)
        (some-> ^ScheduledFuture @pending (.cancel false))
        (.close body)))))

(defn- open
  "Send a request and return the response once its headers are in, with
  the body an open InputStream. With idle-timeout-ms, that is the longest
  wait for the headers and then for each further part of the body. The
  request's own timeout cannot do this: on newer JDKs it also covers the
  body, which would make it a limit on the whole stream."
  ^HttpResponse [^HttpClient http-client ^HttpRequest request idle-timeout-ms]
  (let [handler (HttpResponse$BodyHandlers/ofInputStream)]
    (if-not idle-timeout-ms
      (.send http-client request handler)
      (let [pending (.sendAsync http-client request handler)]
        (try
          (.get pending idle-timeout-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException _
            (.cancel pending true)
            (throw (HttpTimeoutException.
                    (str "no response began within " idle-timeout-ms " ms"))))
          (catch ExecutionException e
            (throw (or (.getCause e) e))))))))

(defn send!
  "Send a request map {:method :url :headers :body :timeout-ms} and return
  {:status :headers :body}. :body is a string, or an open InputStream when
  as is :stream; the caller must then close it. A request that gets no
  response throws a :cl4cl.error/connection ex-info.

  A :stream request may hold :idle-timeout-ms, the longest wait for the
  response to start and then for each further part of the body, as
  `idle-timeout` describes. It should not hold :timeout-ms, which on newer
  JDKs limits the whole body."
  [^HttpClient http-client {:keys [idle-timeout-ms] :as request} as]
  (try
    (let [^HttpResponse response
          (case as
            :string (.send http-client (build request) (HttpResponse$BodyHandlers/ofString))
            :stream (open http-client (build request) idle-timeout-ms))]
      {:status (.statusCode response)
       :headers (headers-map response)
       :body (cond-> (.body response)
               (and (= :stream as) idle-timeout-ms) (idle-timeout idle-timeout-ms))})
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
