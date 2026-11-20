(ns cl4cl.core
  "Claude for Clojure: the Claude Messages API as functions over plain data.

  A request body is a map that mirrors the API's JSON, with keyword keys
  in the API's own spelling:

    (def client (client))
    (create client {:model \"claude-opus-5-5\"
                    :max_tokens 1024
                    :messages [{:role \"user\" :content \"Hello\"}]})

  The result is the response body as a map."
  (:require [cl4cl.error :as error]
            [cl4cl.http :as http]
            [cl4cl.json :as json]
            [cl4cl.stream :as stream]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io InputStream]
           [java.net URLEncoder]))

(def defaults
  {:base-url "https://api.anthropic.com"
   :version "2026-10-21"
   :betas []
   :timeout-ms 600000
   :connect-timeout-ms 30000
   :retry {:max 2 :base-ms 500 :cap-ms 8000}})

(defn client
  "A client: an immutable map of configuration plus a shared HTTP client.
  Options override the defaults and the environment:

    :api-key     sent as x-api-key; default ANTHROPIC_API_KEY
    :auth-token  sent as a bearer token; default ANTHROPIC_AUTH_TOKEN
    :base-url    default ANTHROPIC_BASE_URL, else https://api.anthropic.com
    :version     the anthropic-version header
    :betas       beta names sent on every call
    :timeout-ms  for non-streaming calls
    :retry       {:max :base-ms :cap-ms}

  An :api-key given here wins over an :auth-token. To vary a setting for
  some calls, assoc onto the client."
  ([] (client {}))
  ([options]
   (let [env (fn [k] (not-empty (System/getenv k)))
         config (merge defaults
                       (when-let [url (env "ANTHROPIC_BASE_URL")] {:base-url url})
                       (when-not (or (:api-key options) (:auth-token options))
                         (if-let [k (env "ANTHROPIC_API_KEY")]
                           {:api-key k}
                           (when-let [t (env "ANTHROPIC_AUTH_TOKEN")] {:auth-token t})))
                       options)]
     (when-not (or (:api-key config) (:auth-token config))
       (throw (ex-info "No credentials: pass :api-key or :auth-token, or set ANTHROPIC_API_KEY"
                       {:type ::error/authentication})))
     (assoc config :http-client (http/http-client config)))))

(defn- query-string
  "A map of query parameters as ?k=v&k=v, or \"\" when there are none.
  Parameters whose value is nil are left out."
  [query]
  (let [encode #(URLEncoder/encode (str (if (keyword? %) (name %) %)) "UTF-8")
        pairs (for [[k v] query :when (some? v)] (str (encode k) "=" (encode v)))]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))

(defn request
  "The HTTP request for a call, as data: {:method :url :headers :body
  :timeout-ms}. Pure. body is the JSON body as a map, or nil for none.
  Options:

    :path        default \"/v1/messages\"
    :method      default :post
    :query       a map of query parameters
    :betas       beta names for this call, added to the client's
    :headers     extra headers, which win over the ones cl4cl sets
    :timeout-ms  overrides the client's

  With `execute` or `lines` this reaches any endpoint; with `response` it
  lets a caller use any HTTP transport."
  ([client body] (request client body {}))
  ([{:keys [api-key auth-token base-url version] :as client} body
    {:keys [path method query betas headers timeout-ms] :or {path "/v1/messages" method :post}}]
   (let [betas (distinct (concat (:betas client) betas))]
     (cond-> {:method method
              :url (str (str/replace base-url #"/+$" "") path (query-string query))
              :headers (merge {"anthropic-version" version}
                              (when body {"content-type" "application/json"})
                              (if api-key
                                {"x-api-key" api-key}
                                {"authorization" (str "Bearer " auth-token)})
                              (when (seq betas) {"anthropic-beta" (str/join "," betas)})
                              headers)
              :timeout-ms (or timeout-ms (:timeout-ms client))}
       body (assoc :body (json/write-str body))))))

(defn- response-meta [{:keys [status headers]}]
  (cond-> {:cl4cl/status status}
    headers (assoc :cl4cl/headers headers)
    (get headers "request-id") (assoc :cl4cl/request-id (get headers "request-id"))))

(defn response
  "The result of a call, from an HTTP response {:status :headers :body}
  whose :body is a string. A 2xx response gives the parsed body, with the
  status, headers and request id as metadata under :cl4cl/ keys. Any
  other status throws the ex-info described in cl4cl.error. Pure."
  [{:keys [status body] :as http-response}]
  (if (<= 200 status 299)
    (with-meta (json/read-str body) (response-meta http-response))
    (throw (error/from-response http-response))))

(defn execute
  "Send a request map from `request` and return `response` of the reply,
  retrying as the client's :retry policy says."
  [client http-request]
  (http/with-retry (:retry client)
    #(response (http/send! (:http-client client) http-request :string))))

(defn- open-body
  "Send a request and return the 200 response with its body still open,
  or throw the error the API returned instead."
  [client http-request]
  (let [{:keys [status body] :as http-response}
        (http/send! (:http-client client) http-request :stream)]
    (if (= 200 status)
      http-response
      (throw (error/from-response
              (assoc http-response :body (with-open [in ^InputStream body] (slurp in))))))))

(defn lines
  "A reducible over the lines of a response body, passed through the
  transducer xf, for endpoints that answer with a stream of lines.

  Nothing is sent until the result is reduced, and each reduction is a
  new request. The connection closes when the reduction finishes, stops
  early with `reduced`, or throws. A failure before the body starts is
  retried like any other call; one after it is thrown. There is no total
  timeout, since a long body is normal."
  [client http-request xf]
  (let [http-request (dissoc http-request :timeout-ms)]
    (reify clojure.lang.IReduceInit
      (reduce [_ f init]
        (let [{:keys [body]} (http/with-retry (:retry client)
                               #(open-body client http-request))]
          (with-open [reader (io/reader body)]
            (try
              (transduce xf (completing f) init (line-seq reader))
              (catch java.io.IOException e
                (throw (error/connection e))))))))))

(defn create
  "Create a message: POST /v1/messages. Returns the message as a map.
  Check :stop_reason before reading :content. opts are as for `request`."
  ([client body] (create client body {}))
  ([client body opts]
   (execute client (request client (dissoc body :stream) opts))))

(defn count-tokens
  "Count the input tokens of a message request without creating it:
  POST /v1/messages/count_tokens. Returns {:input_tokens n}."
  ([client body] (count-tokens client body {}))
  ([client body opts]
   (execute client (request client (dissoc body :stream)
                            (assoc opts :path "/v1/messages/count_tokens")))))

(defn stream
  "Create a message and stream it. Returns a reducible of event payloads,
  maps such as {:type \"content_block_delta\" :index 0 :delta {...}}.
  It behaves as `lines` describes.

    (run! prn (stream client body))
    (final-message (stream client body))
    (transduce (map observe) cl4cl.stream/accumulate (stream client body))"
  ([client body] (stream client body {}))
  ([client body opts]
   (lines client (request client (assoc body :stream true) opts) (stream/payloads))))

(defn final-message
  "Fold a stream of event payloads into the message that `create` would
  have returned."
  [events]
  (transduce identity stream/accumulate events))
