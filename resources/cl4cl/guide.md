# cl4cl guide

How to write code against cl4cl, the Clojure client for the Claude Messages API. This file ships inside the library, so it matches the version on your classpath. Read it with `(println (cl4cl.core/guide))`.

## Three rules

1. **A request body is a map shaped like the API's JSON.** Keys are keywords in the API's own spelling, with underscores: `:max_tokens`, `:tool_choice`, `:input_schema`. cl4cl does not rename or validate them.
2. **A response is the parsed JSON.** Read `:stop_reason`, `:content`, `:usage`. Values stay strings: `"end_turn"`, `"text"`, `"user"`.
3. **cl4cl's own additions are namespaced**: metadata such as `:cl4cl/request-id`, error types such as `:cl4cl.error/rate-limit`.

For which fields exist and what a model accepts, the authority is the API reference at https://platform.claude.com/docs/en/api/messages, not this library. If the reference documents a field, put it in the map.

## Mistakes to avoid

- `:max-tokens` is wrong; write `:max_tokens`. No key is ever kebab-case.
- `{:role :user}` is wrong; write `{:role "user"}`. The same goes for every `:type`.
- `stream` and `batches/results` return a reducible, not a sequence. `map`, `first`, `doseq` and `count` do not work on it. Use `run!`, `reduce`, `transduce` or `(into [] ...)`.
- There is no tool-running helper and no builder functions. Write the loop below.
- When continuing a conversation, append the reply's `:content` exactly as received. Do not filter out thinking blocks or rebuild the blocks.
- `:content` is a vector of blocks of several types. Do not assume `(first (:content m))` is the text.
- Check `:stop_reason` before using `:content`. `"refusal"` and `"max_tokens"` are normal responses, not exceptions.
- Do not write a retry loop for rate limits or server errors; cl4cl already retries them.

## Setup

```clojure
(require '[cl4cl.core :as claude]
         '[cl4cl.stream :as stream]
         '[cl4cl.batches :as batches]
         '[cl4cl.error :as error])

(def client (claude/client))                      ; reads ANTHROPIC_API_KEY
(def client (claude/client {:api-key "sk-ant-..."}))
```

The client is an immutable map: make one and share it. Other options: `:auth-token`, `:base-url`, `:version`, `:betas`, `:timeout-ms` (default 600000), `:retry` (default `{:max 2 :base-ms 500 :cap-ms 8000}`). Vary one with `assoc`.

## One request

```clojure
(def message
  (claude/create client
    {:model "claude-opus-5-5"
     :max_tokens 16000
     :system "Be concise."
     :messages [{:role "user" :content "What is a transducer?"}]}))

(defn text [message]
  (apply str (keep #(when (= "text" (:type %)) (:text %)) (:content message))))

(:stop_reason message)             ; "end_turn", "max_tokens", "tool_use", "refusal", ...
(text message)
(:usage message)                   ; {:input_tokens 14 :output_tokens 210 ...}
(:cl4cl/request-id (meta message))
```

`(claude/count-tokens client body)` takes the same body without `:max_tokens` and returns `{:input_tokens n}`.

## Streaming

Use `stream` for long replies; it has no total timeout. Nothing is sent until the result is reduced, and each reduction is a new request.

```clojure
;; print text as it arrives, and get the finished message at the end
(defn show [event]
  (when (= "text_delta" (get-in event [:delta :type]))
    (print (get-in event [:delta :text]))
    (flush))
  event)

(transduce (map show) stream/accumulate (claude/stream client body))

;; only the finished message, the same map `create` returns
(claude/final-message (claude/stream client body))

;; all events as a vector
(into [] (claude/stream client body))
```

Events are the API's own: `{:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Hi"}}`. Delta types are `text_delta`, `thinking_delta`, `signature_delta`, `citations_delta` and `input_json_delta`.

## Conversations

The API is stateless. Send every turn each time.

```clojure
(let [reply (claude/create client body)]
  (claude/create client
    (update body :messages conj
            {:role "assistant" :content (:content reply)}
            {:role "user" :content "Say more."})))
```

## Tools

```clojure
(def tools
  [{:name "get_weather"
    :description "Get the current weather for a city."
    :input_schema {:type "object"
                   :properties {:city {:type "string"}}
                   :required ["city"]}}])

(defn run-tool [{:keys [id name input]}]
  (try
    {:type "tool_result"
     :tool_use_id id
     :content (case name
                "get_weather" (str "18°C in " (:city input)))}
    (catch Exception e
      {:type "tool_result" :tool_use_id id :is_error true :content (ex-message e)})))

(defn converse [client body]
  (let [message (claude/create client body)]
    (if (= "tool_use" (:stop_reason message))
      (let [calls (filter #(= "tool_use" (:type %)) (:content message))]
        (recur client
               (update body :messages conj
                       {:role "assistant" :content (:content message)}
                       {:role "user" :content (mapv run-tool calls)})))
      message)))

(converse client {:model "claude-opus-5-5" :max_tokens 16000 :tools tools
                  :messages [{:role "user" :content "Weather in Paris?"}]})
```

`:input` is a map with keyword keys. Return every tool's result in one user message. Report a failure with `:is_error true`; do not leave a result out.

## Message Batches

```clojure
(def batch
  (batches/create client
    {:requests [{:custom_id "q1"
                 :params {:model "claude-opus-5-5" :max_tokens 16000
                          :messages [{:role "user" :content "Define monad."}]}}]}))

(batches/retrieve client (:id batch))   ; poll until :processing_status is "ended"

(into {} (map (juxt :custom_id :result)) (batches/results client (:id batch)))
;; => {"q1" {:type "succeeded" :message {...}}}   ; or "errored", "canceled", "expired"
```

Also `(batches/list client {:limit 20})`, `(batches/cancel client id)` and `(batches/delete client id)`. Results arrive in no particular order; match them by `:custom_id`.

## Errors

A failed call throws an `ex-info`. Test its `:type` with `isa?`.

```clojure
(try
  (claude/create client body)
  (catch clojure.lang.ExceptionInfo e
    (let [{:keys [type status message request-id]} (ex-data e)]
      (cond
        (isa? type ::error/invalid-request) (println "Bad request:" message)
        (isa? type ::error/retryable)       (println "Still failing after retries:" message)
        :else                               (throw e)))))
```

Types: `::error/invalid-request` (400), `::error/authentication` (401), `::error/billing` (402), `::error/permission` (403), `::error/not-found` (404), `::error/request-too-large` (413), and the retryable ones `::error/conflict`, `::error/rate-limit` (429), `::error/api` (500), `::error/timeout`, `::error/overloaded` (529) and `::error/connection`. The retryable ones derive from `::error/retryable` and have already been retried twice when you see them.

A 400's `:message` comes from the API and says what to change. Read it before changing anything else.

## Options for one call

Every function takes a last argument of options that are not part of the body:

```clojure
(claude/create client body {:betas ["some-beta-2026-01-01"]
                            :timeout-ms 30000
                            :headers {"anthropic-workspace-id" "wrkspc_..."}})
```

## Endpoints without a function

```clojure
(claude/execute client
  (claude/request client nil {:method :get :path "/v1/models" :query {:limit 5}}))
```

`request` builds the HTTP request as data and is pure; `execute` sends it and parses the reply; `lines` returns a reducible over a line-based reply; `response` turns an HTTP response you fetched yourself into the result or the exception.

## Finding out more

At a REPL, `(clojure.repl/dir cl4cl.core)` lists the functions and `(clojure.repl/doc cl4cl.core/stream)` shows one. The public namespaces are `cl4cl.core`, `cl4cl.stream`, `cl4cl.batches`, `cl4cl.error` and `cl4cl.json`.
