(ns cl4cl.batches
  "The Message Batches API: many message requests processed together,
  asynchronously. Like cl4cl.core, every function takes and returns plain
  data in the API's own shape, and takes the same optional opts.

    (def batch (create client {:requests [{:custom_id \"a\" :params {...}}]}))
    (retrieve client (:id batch))     ; until :processing_status is \"ended\"
    (into [] (results client (:id batch)))"
  (:refer-clojure :exclude [list])
  (:require [cl4cl.core :as core]
            [cl4cl.json :as json]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

(def ^:private root "/v1/messages/batches")

(defn- path [id & more]
  (str/join "/" (concat [root (URLEncoder/encode (str id) "UTF-8")] more)))

(defn create
  "Create a batch: POST /v1/messages/batches. body is {:requests [...]},
  each request a {:custom_id s :params message-request-body}. Returns the
  batch."
  ([client body] (create client body {}))
  ([client body opts]
   (core/execute client (core/request client body (assoc opts :path root)))))

(defn retrieve
  "The batch with this id: GET /v1/messages/batches/{id}."
  ([client id] (retrieve client id {}))
  ([client id opts]
   (core/execute client (core/request client nil (assoc opts :method :get :path (path id))))))

(defn list
  "A page of batches, newest first: GET /v1/messages/batches. query may
  hold :limit, :after_id and :before_id. Returns {:data [...] :has_more
  :first_id :last_id}; pass :last_id as :after_id for the next page."
  ([client] (list client {} {}))
  ([client query] (list client query {}))
  ([client query opts]
   (core/execute client (core/request client nil (assoc opts :method :get :path root :query query)))))

(defn cancel
  "Ask for a batch to stop: POST /v1/messages/batches/{id}/cancel. Returns
  the batch, which keeps processing until its status is \"ended\"."
  ([client id] (cancel client id {}))
  ([client id opts]
   (core/execute client (core/request client nil (assoc opts :path (path id "cancel"))))))

(defn delete
  "Delete an ended batch: DELETE /v1/messages/batches/{id}."
  ([client id] (delete client id {}))
  ([client id opts]
   (core/execute client (core/request client nil (assoc opts :method :delete :path (path id))))))

(defn results
  "The results of an ended batch: GET /v1/messages/batches/{id}/results.
  Returns a reducible of maps {:custom_id s :result {:type ...}}, in no
  particular order, so match them to requests by :custom_id. A result of
  type \"succeeded\" holds the :message. It behaves as cl4cl.core/lines
  describes."
  ([client id] (results client id {}))
  ([client id opts]
   (core/lines client
               (core/request client nil (assoc opts :method :get :path (path id "results")))
               (comp (remove str/blank?) (map json/read-str)))))
