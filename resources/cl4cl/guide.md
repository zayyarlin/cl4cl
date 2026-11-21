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
- Do not write your own tool loop; use `tools/run`. A handler returns its result and does not build the `tool_result` block.
- When continuing a conversation, append the reply's `:content` exactly as received, which `message/reply` does. Do not filter out thinking blocks or rebuild the blocks.
- `:content` is a vector of blocks of several types. Do not assume `(first (:content m))` is the text; use `message/text`.
- The helpers in `cl4cl.message` only read and build the API's own maps. There are no other builders: write the rest of a body as a literal map.
- Check `:stop_reason` before using `:content`. `"refusal"` and `"max_tokens"` are normal responses, not exceptions.
- Do not write a retry loop for rate limits or server errors; cl4cl already retries them.

## Setup

```clojure
(require '[cl4cl.core :as claude]
         '[cl4cl.message :as message]
         '[cl4cl.tools :as tools]
         '[cl4cl.stream :as stream]
         '[cl4cl.batches :as batches]
         '[cl4cl.error :as error])

(def client (claude/client))                      ; reads ANTHROPIC_API_KEY
(def client (claude/client {:api-key "sk-ant-..."}))
```

The client is an immutable map: make one and share it. Other options: `:auth-token`, `:base-url`, `:version`, `:betas`, `:timeout-ms` (default 600000), `:idle-timeout-ms` (for streams, default 600000), `:retry` (default `{:max 2 :base-ms 500 :cap-ms 8000}`). Vary one with `assoc`.

## One request

```clojure
(def message
  (claude/create client
    {:model "claude-opus-5-5"
     :max_tokens 16000
     :system "Be concise."
     :messages [{:role "user" :content "What is a transducer?"}]}))

(:stop_reason message)             ; "end_turn", "max_tokens", "tool_use", "refusal", ...
(message/text message)             ; the text blocks joined, as one string
(:usage message)                   ; {:input_tokens 14 :output_tokens 210 ...}
(:cl4cl/request-id (meta message))
```

`(claude/count-tokens client body)` takes the same body without `:max_tokens` and returns `{:input_tokens n}`.

With a structured output format in the body (`:output_config {:format {:type "json_schema" :schema {...}}}`), `(message/data message)` is the reply parsed as JSON with keyword keys.

## Streaming

Use `stream` for long replies; it has no total timeout, only `:idle-timeout-ms`, the longest silence it accepts from the API before throwing `::error/connection`. Nothing is sent until the result is reduced, and each reduction is a new request.

```clojure
;; print text as it arrives, and get the finished message at the end
(defn show [event]
  (when (= "text_delta" (get-in event [:delta :type]))
    (print (get-in event [:delta :text]))
    (flush))
  event)

(transduce (map show) stream/accumulate (claude/stream client body))

;; only the text, piece by piece
(run! print (eduction (stream/text-deltas) (claude/stream client body)))

;; only the finished message, the same map `create` returns
(claude/final-message (claude/stream client body))

;; all events as a vector
(into [] (claude/stream client body))
```

Events are the API's own: `{:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Hi"}}`. Delta types are `text_delta`, `thinking_delta`, `signature_delta`, `citations_delta` and `input_json_delta`.

## Conversations

The API is stateless. Send every turn each time. `message/reply` appends the reply as the assistant's turn and `message/say` appends a user turn.

```clojure
(let [answer (claude/create client body)]
  (claude/create client (-> body (message/reply answer) (message/say "Say more."))))

;; an image or a PDF is a content block, built from its bytes
(message/say body [(message/image png-bytes "image/png")
                   (message/document pdf-bytes)
                   {:type "text" :text "Compare the chart with the report."}])
```

## Tools

Declare the tools in the body and give `tools/run` a map from each tool's name to a function of its input. It calls the API, runs the tools Claude asks for, sends the results back, and repeats until Claude answers.

```clojure
(def body
  {:model "claude-opus-5-5" :max_tokens 16000
   :tools [{:name "get_weather"
            :description "Get the current weather for a city."
            :input_schema {:type "object"
                           :properties {:city {:type "string"}}
                           :required ["city"]}}]
   :messages [{:role "user" :content "Weather in Paris?"}]})

(def handlers
  {"get_weather" (fn [{:keys [city]}] {:city city :celsius 18})})

(def answer (tools/run client body handlers))    ; the final message
(message/text answer)

;; the whole conversation so far is on the answer's metadata; carry on from it
(tools/run client
           (message/say (:cl4cl.tools/body (meta answer)) "And in Oslo?")
           handlers)

;; every message of the loop, at most 10 requests
(into [] (take 10) (tools/turns client body handlers))
```

A handler gets `:input` as a map with keyword keys. A string or a vector of content blocks it returns is the result as it is; any other value is sent as JSON. If it throws, or the tool has no handler, Claude is told so with `:is_error true` and the loop goes on. The loop has no limit of its own: bound it with `take` on `tools/turns`, which is both reducible and seqable. It ends on any `:stop_reason` other than `"tool_use"` and `"pause_turn"`, so check for `"max_tokens"` and `"refusal"` as after `create`.

For more control, each layer is public. `(tools/step client body handlers)` is one request plus its tool calls, and returns the message with the next body under `:cl4cl.tools/body` in its metadata; loop on it yourself while `(tools/pending? message)`. `(tools/results handlers message)` runs a message's calls and `(tools/call handlers tool-use)` runs one. Two options replace a part of the loop:

```clojure
(tools/run client body handlers
  {:betas ["some-beta-2026-01-01"]          ; options for one call still apply
   ;; stream each reply instead of waiting for it
   :create (fn [client body opts]
             (transduce (map show) stream/accumulate (claude/stream client body opts)))
   ;; decide how calls are run: approval, parallelism, timeouts
   :results (fn [handlers message]
              (vec (pmap #(tools/call handlers %) (message/tool-uses message))))})
```

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

At a REPL, `(clojure.repl/dir cl4cl.core)` lists the functions and `(clojure.repl/doc cl4cl.core/stream)` shows one. The public namespaces are `cl4cl.core`, `cl4cl.message`, `cl4cl.tools`, `cl4cl.stream`, `cl4cl.batches`, `cl4cl.error` and `cl4cl.json`.
