(ns cl4cl.error
  "API failures as data. Every failure is an ex-info whose :type is a
  keyword in a hierarchy: all types derive from ::error, and the ones
  worth retrying derive from ::retryable. Test with isa?."
  (:require [cl4cl.json :as json]))

(def types
  "The API's error.type strings and the keyword each becomes."
  {"invalid_request_error" ::invalid-request
   "authentication_error"  ::authentication
   "billing_error"         ::billing
   "permission_error"      ::permission
   "not_found_error"       ::not-found
   "conflict_error"        ::conflict
   "request_too_large"     ::request-too-large
   "rate_limit_error"      ::rate-limit
   "api_error"             ::api
   "timeout_error"         ::timeout
   "overloaded_error"      ::overloaded})

(doseq [t (concat (vals types) [::connection ::unknown ::unknown-retryable ::retryable])]
  (derive t ::error))

(doseq [t [::conflict ::rate-limit ::api ::timeout ::overloaded ::connection ::unknown-retryable]]
  (derive t ::retryable))

(derive ::unknown-retryable ::unknown)

(defn retryable?
  "Is this exception a cl4cl failure that may succeed if tried again?"
  [e]
  (isa? (:type (ex-data e)) ::retryable))

(defn- retryable-status? [status]
  (boolean (and status (or (#{408 409 429} status) (<= 500 status)))))

(defn- error-type [api-type status]
  (or (types api-type)
      (if (retryable-status? status) ::unknown-retryable ::unknown)))

(defn- parse-body
  "The body as data if it is a JSON object, else nil."
  [body]
  (let [parsed (try (json/read-str body) (catch Exception _ nil))]
    (when (map? parsed) parsed)))

(defn- retry-after-ms [headers]
  (some-> (get headers "retry-after") parse-double (* 1000) long))

(defn- error
  "Build the ex-info for an API error payload {:type \"error\" :error {...}}."
  [payload {:keys [status headers raw]}]
  (let [{api-type :type :keys [message details]} (:error payload)
        request-id (or (:request_id payload) (get headers "request-id"))
        retry-after (retry-after-ms headers)]
    (ex-info (or message (str "Claude API request failed" (when status (str " with status " status))))
             (cond-> {:type (error-type api-type status)}
               status                (assoc :status status)
               api-type              (assoc :error-type api-type)
               (:error_code details) (assoc :error-code (:error_code details))
               message               (assoc :message message)
               request-id            (assoc :request-id request-id)
               retry-after           (assoc :retry-after-ms retry-after)
               payload               (assoc :body payload)
               (and raw (not payload)) (assoc :body raw)))))

(defn from-response
  "The ex-info for a non-2xx response {:status :headers :body}, where
  :body is the raw string."
  [{:keys [status headers body]}]
  (error (parse-body body) {:status status :headers headers :raw body}))

(defn from-event
  "The ex-info for an error event received in a stream after a 200."
  [payload]
  (error payload {}))

(defn connection
  "The ex-info for a request that got no response."
  [^Throwable cause]
  (ex-info (str "Could not reach the Claude API: " (or (.getMessage cause) (class cause)))
           {:type ::connection}
           cause))
