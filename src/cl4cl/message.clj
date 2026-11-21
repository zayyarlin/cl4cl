(ns cl4cl.message
  "Pure functions over messages and request bodies: reading a reply,
  building the next request, and making the content blocks that are
  tedious to write by hand. Everything here takes and returns the API's
  own shapes, so each function is optional: the maps can always be read
  and built directly.

    (text message)                                  ; the reply's text
    (-> body (reply message) (say \"Say more.\"))    ; the next request body"
  (:require [cl4cl.json :as json]
            [clojure.string :as str])
  (:import [java.util Base64]))

(defn text
  "The text of a message: its text blocks joined, with thinking, tool and
  other blocks left out. \"\" when there is none."
  [message]
  (str/join (keep #(when (= "text" (:type %)) (:text %)) (:content message))))

(defn tool-uses
  "The tool_use blocks of a message, in order, as a vector. These are the
  calls the caller must answer; server tool blocks are not among them."
  [message]
  (filterv #(= "tool_use" (:type %)) (:content message)))

(defn data
  "The text of a message read as JSON, with keyword keys: the value of a
  reply made with a structured output format. Throws if the text is not
  JSON, as it is not after a refusal or when max_tokens cut it short, so
  check :stop_reason first."
  [message]
  (json/read-str (text message)))

(defn reply
  "body with a message from the API appended as an assistant turn, its
  :content unchanged, which is how the API must get it back.

    (-> body (reply message) (say \"And in French?\"))"
  [body message]
  (update body :messages #(conj (vec %) {:role "assistant" :content (:content message)})))

(defn say
  "body with a user turn appended. content is whatever the API accepts as
  a message's :content: a string or a vector of blocks."
  [body content]
  (update body :messages #(conj (vec %) {:role "user" :content content})))

(defn- base64-block [type bytes media-type]
  {:type type
   :source {:type "base64"
            :media_type media-type
            :data (.encodeToString (Base64/getEncoder) ^bytes bytes)}})

(defn image
  "An image content block from the image's bytes and its media type, such
  as \"image/png\" or \"image/jpeg\".

    {:role \"user\" :content [(image bytes \"image/png\")
                             {:type \"text\" :text \"What is this?\"}]}"
  [bytes media-type]
  (base64-block "image" bytes media-type))

(defn document
  "A document content block from a PDF's bytes. An :title, :context,
  :citations or :cache_control is an assoc away."
  [bytes]
  (base64-block "document" bytes "application/pdf"))
