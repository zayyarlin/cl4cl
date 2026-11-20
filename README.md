# cl4cl

Claude for Clojure: the Claude Messages API as functions over plain data.

cl4cl talks to the API over raw HTTP. It needs Java 21 or later and has one dependency besides Clojure, `org.clojure/data.json`. Requests and responses are ordinary maps that mirror the API's JSON, so anything in the [API reference](https://platform.claude.com/docs/en/api/messages) works without waiting for a library release.

Messages, streaming, token counting and Message Batches are implemented. `doc/spec-checklist.md` lists every part of the API reference and whether it is covered.

**Coding agents:** the usage guide ships inside the library and matches the installed version. Read it before writing code against cl4cl: `(println (cl4cl.core/guide))`.

## Installation

```clojure
[io.github.zayyarlin/cl4cl "0.1.1"]                  ; Leiningen
io.github.zayyarlin/cl4cl {:mvn/version "0.1.1"}     ; deps.edn
```

cl4cl needs Clojure 1.11 or later and does not bring its own.

### If a coding agent works on your project

Add this to your project's `CLAUDE.md` or `AGENTS.md`, so the agent reads the guide instead of guessing from other SDKs:

```markdown
## Claude API

This project calls the Claude API through cl4cl (`io.github.zayyarlin/cl4cl`).
Before writing or changing code that uses it, read the library's guide, which
matches the installed version: evaluate `(println (cl4cl.core/guide))` at the
REPL, or read `cl4cl/guide.md` inside the cl4cl jar. Request bodies are plain
maps with the API's own key names, such as `:max_tokens`.
```

## Contents

- [Installation](#installation)
- [The idea](#the-idea)
- [Client](#client)
- [Creating a message](#creating-a-message)
- [Streaming](#streaming)
- [Counting tokens](#counting-tokens)
- [Conversations](#conversations)
- [Tools](#tools)
- [Thinking, caching and structured output](#thinking-caching-and-structured-output)
- [Message Batches](#message-batches)
- [Per-call options](#per-call-options)
- [Errors and retries](#errors-and-retries)
- [Other endpoints and your own HTTP client](#other-endpoints-and-your-own-http-client)
- [Function reference](#function-reference)
- [Development](#development)

## The idea

There are three rules, and the rest follows from them.

1. **A request body is a map shaped like the API's JSON.** Keys are keywords in the API's own spelling: `:max_tokens`, `:tool_choice`, `:cache_control`. cl4cl does not rename, validate or restrict them. If the API reference documents a field, put it in the map.
2. **A response is the parsed JSON body.** `:stop_reason`, `:content`, `:usage` and everything else appear exactly as documented. Values such as `"end_turn"` and `"text"` stay strings.
3. **Anything cl4cl adds is namespaced or kept out of the way.** HTTP details are metadata on the response. Error types are keywords such as `:cl4cl.error/rate-limit`.

```clojure
(require '[cl4cl.core :as claude])

(def client (claude/client))

(claude/create client
  {:model "claude-opus-5-5"
   :max_tokens 16000
   :messages [{:role "user" :content "What is a transducer?"}]})
;; => {:id "msg_..." :type "message" :role "assistant" :model "claude-opus-5-5"
;;     :content [{:type "thinking" ...} {:type "text" :text "..."}]
;;     :stop_reason "end_turn" :stop_sequence nil
;;     :usage {:input_tokens 14 :output_tokens 210 ...}}
```

## Client

`client` returns an immutable map. Make one and share it.

```clojure
(claude/client)                       ; credentials from the environment
(claude/client {:api-key "sk-ant-..."})
(claude/client {:auth-token "..."     ; an OAuth bearer token instead of a key
                :betas ["oauth-2025-04-20"]})
```

| Option | Meaning | Default |
|---|---|---|
| `:api-key` | Sent as `x-api-key` | `ANTHROPIC_API_KEY` |
| `:auth-token` | Sent as `Authorization: Bearer` | `ANTHROPIC_AUTH_TOKEN` |
| `:base-url` | Where the API lives | `ANTHROPIC_BASE_URL`, else `https://api.anthropic.com` |
| `:version` | The `anthropic-version` header | `2026-10-21` |
| `:betas` | Beta names sent on every call as `anthropic-beta` | `[]` |
| `:timeout-ms` | Total time allowed for a non-streaming call | 600000 (10 minutes) |
| `:connect-timeout-ms` | Time allowed to connect | 30000 |
| `:retry` | Retry policy, see [Errors and retries](#errors-and-retries) | `{:max 2 :base-ms 500 :cap-ms 8000}` |

An explicit `:api-key` wins over an `:auth-token`. In the environment, `ANTHROPIC_API_KEY` wins over `ANTHROPIC_AUTH_TOKEN`. With no credentials at all, `client` throws.

Because the client is a map, a variant is one `assoc` away:

```clojure
(def patient (assoc client :timeout-ms 1800000))
(def no-retry (assoc client :retry {:max 0 :base-ms 0 :cap-ms 0}))
```

`:connect-timeout-ms` is the exception: it is fixed when `client` is called.

## Creating a message

`(claude/create client body)` sends `POST /v1/messages` and returns the message.

```clojure
(def message
  (claude/create client
    {:model "claude-opus-5-5"
     :max_tokens 16000
     :system "You are a concise technical writer."
     :messages [{:role "user" :content "Explain persistent data structures."}]}))

(:stop_reason message)   ;; => "end_turn"
(:usage message)         ;; => {:input_tokens 21 :output_tokens 180 ...}
```

`:content` is a vector of blocks, and a reply can hold several kinds. To get the text:

```clojure
(defn text [message]
  (apply str (keep #(when (= "text" (:type %)) (:text %)) (:content message))))
```

Read `:stop_reason` before trusting `:content`:

| `:stop_reason` | What it means |
|---|---|
| `"end_turn"` | The reply is complete |
| `"max_tokens"` | The reply was cut off at `:max_tokens` |
| `"stop_sequence"` | One of your `:stop_sequences` was produced; `:stop_sequence` says which |
| `"tool_use"` | Claude wants you to run tools, see [Tools](#tools) |
| `"pause_turn"` | A long server-side turn paused; send the conversation back to continue |
| `"refusal"` | The request was declined; `:stop_details` says why |
| `"model_context_window_exceeded"` | The conversation no longer fits the model |

A refusal is a normal response, not an exception.

The HTTP status, headers and request id travel as metadata, so they never leak back to the API when you reuse the message:

```clojure
(meta message)
;; => {:cl4cl/status 200
;;     :cl4cl/request-id "req_..."
;;     :cl4cl/headers {"content-type" "application/json" "request-id" "req_..." ...}}
```

Response header names are lower-case. Rate-limit headers are in `:cl4cl/headers`.

`create` always sends a non-streaming request: a `:stream` key in the body is dropped. Use `stream` to stream.

## Streaming

`(claude/stream client body)` returns a reducible of events. Use it for long replies, since a streaming call has no total timeout.

Three facts about the reducible:

- Nothing is sent until you reduce it, and each reduction is a new request.
- The connection closes when the reduction finishes, stops early, or throws.
- It works with `reduce`, `transduce`, `run!`, `into` and `eduction`. It is not a sequence, so `map` and `first` do not apply directly; use a transducer or `(into [] ...)`.

### Events

Each event is the API's event payload as a map:

```clojure
(into [] (claude/stream client body))
;; => [{:type "message_start" :message {:id "msg_..." :content [] ...}}
;;     {:type "content_block_start" :index 0 :content_block {:type "text" :text ""}}
;;     {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Hel"}}
;;     {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "lo"}}
;;     {:type "content_block_stop" :index 0}
;;     {:type "message_delta" :delta {:stop_reason "end_turn"} :usage {:output_tokens 2}}
;;     {:type "message_stop"}]
```

| Event `:type` | Carries |
|---|---|
| `"message_start"` | `:message`, the message so far with empty `:content` |
| `"content_block_start"` | `:index` and `:content_block`, the block's starting value |
| `"content_block_delta"` | `:index` and `:delta`, see below |
| `"content_block_stop"` | `:index` |
| `"message_delta"` | `:delta` with `:stop_reason` and friends, and cumulative `:usage` |
| `"message_stop"` | Nothing; the stream is done |

| Delta `:type` | Carries | Applies to |
|---|---|---|
| `"text_delta"` | `:text` | text blocks |
| `"thinking_delta"` | `:thinking` | thinking blocks |
| `"signature_delta"` | `:signature` | thinking blocks |
| `"citations_delta"` | `:citation` | text blocks |
| `"input_json_delta"` | `:partial_json`, a fragment of the tool input | tool use blocks |

`ping` events are dropped. An `error` event is thrown as the same `ex-info` a failed call throws. Event types cl4cl does not know are passed through to you untouched.

### Printing text as it arrives

```clojure
(run! (fn [event]
        (when (= "text_delta" (get-in event [:delta :type]))
          (print (get-in event [:delta :text]))
          (flush)))
      (claude/stream client body))
```

### The finished message

`final-message` folds the events into the same map `create` returns:

```clojure
(claude/final-message (claude/stream client body))
```

### Both in one pass

`cl4cl.stream/accumulate` is the reducing function behind `final-message`. Combine it with any transducer to observe events on the way:

```clojure
(require '[cl4cl.stream :as stream])

(transduce (map (fn [event] (show event) event))
           stream/accumulate
           (claude/stream client body))
;; => the finished message
```

### Stopping early

```clojure
;; the first 20 text fragments, then the connection is closed
(into []
      (comp (keep #(get-in % [:delta :text])) (take 20))
      (claude/stream client body))
```

### Tool input in a stream

Tool input arrives as fragments of one JSON document. `accumulate` joins them and parses the result when the block stops, so the finished block has `:input` as a map.

If the fragments cannot be read, which happens when `:max_tokens` cuts a tool call short or the input is nested thousands of levels deep, `:input` is left as it was and the raw text is kept on the block's metadata:

```clojure
(let [message (claude/final-message (claude/stream client body))]
  (when (= "max_tokens" (:stop_reason message))
    (map (comp :cl4cl.stream/partial-json meta) (:content message))))
;; => ("{\"title\": \"Essay\"")
```

### Events from somewhere else

`(stream/payloads)` is the transducer from the lines of an SSE body to events. Use it if you fetched the body yourself:

```clojure
(transduce (stream/payloads) stream/accumulate (line-seq reader))
```

### New delta types

Deltas are applied by the multimethod `stream/apply-delta`, dispatched on the delta's `:type`. Unknown types leave the block unchanged. To handle one before cl4cl does:

```clojure
(defmethod stream/apply-delta "some_new_delta" [block delta]
  (update block :something str (:something delta)))
```

## Counting tokens

`(claude/count-tokens client body)` sends `POST /v1/messages/count_tokens`. The body is a message request without `:max_tokens`.

```clojure
(claude/count-tokens client
  {:model "claude-opus-5-5"
   :system "You are a concise technical writer."
   :messages [{:role "user" :content "Explain persistent data structures."}]})
;; => {:input_tokens 29}
```

## Conversations

The API is stateless: send the whole conversation each time. Append the reply's `:content` unchanged, then the next user turn.

```clojure
(defn reply
  "Add Claude's answer to a request body."
  [body message]
  (update body :messages conj {:role "assistant" :content (:content message)}))

(defn say
  "Add a user turn to a request body."
  [body text]
  (update body :messages conj {:role "user" :content text}))

(let [body   {:model "claude-opus-5-5" :max_tokens 16000
              :messages [{:role "user" :content "Name a sorting algorithm."}]}
      first-answer (claude/create client body)
      body   (-> body (reply first-answer) (say "Why is it stable?"))]
  (claude/create client body))
```

Pass `:content` back whole. Thinking blocks carry signatures the API checks, so do not edit, reorder or drop blocks from earlier turns.

Content can be a string or a vector of blocks. Images and documents are blocks:

```clojure
{:role "user"
 :content [{:type "image"
            :source {:type "base64" :media_type "image/png" :data "iVBORw0..."}}
           {:type "document"
            :source {:type "base64" :media_type "application/pdf" :data "JVBERi0..."}}
           {:type "text" :text "Compare the chart with the report."}]}
```

## Tools

Declare tools in the body. When Claude wants one, the reply has `:stop_reason "tool_use"` and one or more `tool_use` blocks. Run them, send every result back in a single user message, and repeat.

```clojure
(def weather-tool
  {:name "get_weather"
   :description "Get the current weather for a city."
   :input_schema {:type "object"
                  :properties {:city {:type "string" :description "City name"}}
                  :required ["city"]}})

(defn run-tool
  "The tool_result block for one tool_use block."
  [{:keys [id name input]}]
  (try
    {:type "tool_result"
     :tool_use_id id
     :content (case name
                "get_weather" (str "18°C and cloudy in " (:city input)))}
    (catch Exception e
      {:type "tool_result" :tool_use_id id :is_error true :content (ex-message e)})))

(defn converse
  "Call the API until Claude stops asking for tools. Returns the final message."
  [client body]
  (let [message (claude/create client body)]
    (if (= "tool_use" (:stop_reason message))
      (let [calls (filter #(= "tool_use" (:type %)) (:content message))]
        (recur client
               (update body :messages conj
                       {:role "assistant" :content (:content message)}
                       {:role "user" :content (mapv run-tool calls)})))
      message)))

(converse client
  {:model "claude-opus-5-5"
   :max_tokens 16000
   :tools [weather-tool]
   :messages [{:role "user" :content "Is it warmer in Paris or in Oslo?"}]})
```

Things to know:

- `:input` is already a map with keyword keys. Keys round-trip exactly, including unusual ones such as `"a/b"`.
- One reply may hold several `tool_use` blocks. Return all their results in one user message.
- Report a failed tool with `:is_error true` rather than leaving its result out.
- Server tools (web search, code execution and others) are declared the same way in `:tools`, for example `{:type "web_search_20260209" :name "web_search"}`. Their results come back as blocks in the reply; there is nothing for you to run.
- cl4cl does not ship a tool loop. The one above is the whole pattern.

## Thinking, caching and structured output

These are all request data. A few common ones:

```clojure
;; Thinking: let the model decide how much, and return a readable summary
{:thinking {:type "adaptive" :display "summarized"}
 :output_config {:effort "high"}}

;; Prompt caching: mark the end of the stable prefix
{:system [{:type "text" :text long-instructions :cache_control {:type "ephemeral"}}]}
;; then check (:cache_read_input_tokens (:usage message)) on later calls

;; Structured output: the reply's text is JSON matching the schema
{:output_config {:format {:type "json_schema"
                          :schema {:type "object"
                                   :properties {:answer {:type "string"}}
                                   :required ["answer"]
                                   :additionalProperties false}}}}
```

Parse structured output with `cl4cl.json/read-str`, which gives keyword keys.

Which options a model accepts varies by model. The API answers an unsupported one with a 400 whose message says what to change; cl4cl surfaces that message and does not second-guess it.

## Message Batches

Batches process many message requests asynchronously. Everything is in `cl4cl.batches`.

```clojure
(require '[cl4cl.batches :as batches])

;; 1. Create. Each request has your own :custom_id and a message body as :params.
(def batch
  (batches/create client
    {:requests [{:custom_id "q1"
                 :params {:model "claude-opus-5-5" :max_tokens 16000
                          :messages [{:role "user" :content "Define monad."}]}}
                {:custom_id "q2"
                 :params {:model "claude-opus-5-5" :max_tokens 16000
                          :messages [{:role "user" :content "Define functor."}]}}]}))
;; => {:id "msgbatch_..." :type "message_batch" :processing_status "in_progress"
;;     :request_counts {:processing 2 :succeeded 0 :errored 0 :canceled 0 :expired 0}
;;     :created_at "..." :expires_at "..." :ended_at nil :results_url nil ...}

;; 2. Wait until it has ended.
(defn await-batch [client id]
  (let [batch (batches/retrieve client id)]
    (if (= "ended" (:processing_status batch))
      batch
      (do (Thread/sleep 30000) (recur client id)))))

(await-batch client (:id batch))

;; 3. Read the results.
(into {} (map (juxt :custom_id :result)) (batches/results client (:id batch)))
;; => {"q1" {:type "succeeded" :message {:content [...] :stop_reason "end_turn" ...}}
;;     "q2" {:type "errored" :error {...}}}
```

`results` is a reducible with the same behaviour as `stream`. Results arrive in no particular order, so match them by `:custom_id`. Each `:result` has one of four types:

| `:type` | Holds |
|---|---|
| `"succeeded"` | `:message`, a message exactly as `create` returns |
| `"errored"` | `:error`, the API's error for that request |
| `"canceled"` | Nothing; the batch was canceled first |
| `"expired"` | Nothing; the batch expired first |

The other functions:

```clojure
(batches/list client)                        ; newest first
(batches/list client {:limit 20})
;; => {:data [batch ...] :has_more true :first_id "..." :last_id "..."}
(batches/list client {:limit 20 :after_id last-id})   ; the next page

(batches/cancel client id)   ; returns the batch; it ends shortly after
(batches/delete client id)   ; only an ended batch; => {:id "..." :type "message_batch_deleted"}
```

`:processing_status` is `"in_progress"`, `"canceling"` or `"ended"`.

## Per-call options

Every function takes an optional last argument of options that are not part of the body:

```clojure
(claude/create client body
  {:betas ["compact-2026-01-12"]                       ; added to the client's :betas
   :timeout-ms 30000                                   ; for this call only
   :headers {"anthropic-workspace-id" "wrkspc_..."}})  ; wins over headers cl4cl sets
```

| Option | Meaning |
|---|---|
| `:betas` | Beta names for this call, joined with the client's into `anthropic-beta` |
| `:timeout-ms` | Overrides the client's; ignored by `stream` and `batches/results` |
| `:headers` | Extra headers, as a map of string to string |
| `:path`, `:method`, `:query` | For [other endpoints](#other-endpoints-and-your-own-http-client) |

## Errors and retries

A failed call throws an `ex-info`. Its data:

```clojure
(try
  (claude/create client body)
  (catch clojure.lang.ExceptionInfo e
    (ex-data e)))
;; => {:type :cl4cl.error/rate-limit
;;     :status 429
;;     :error-type "rate_limit_error"       ; the API's own error.type
;;     :error-code "ramp_limit_exceeded"    ; error.details.error_code, when present
;;     :message "..."
;;     :request-id "req_..."                ; quote this to Anthropic support
;;     :retry-after-ms 2000                 ; when the API sent retry-after in seconds
;;     :body {...}}                         ; the parsed error body, or the raw text
```

`:type` is a keyword in a hierarchy. Test it with `isa?`, which matches the keyword and its parents:

```clojure
(require '[cl4cl.error :as error])

(try
  (claude/create client body)
  (catch clojure.lang.ExceptionInfo e
    (let [type (:type (ex-data e))]
      (cond
        (isa? type ::error/rate-limit)     (back-off)
        (isa? type ::error/retryable)      (try-later)
        (isa? type ::error/authentication) (fix-credentials)
        :else                              (throw e)))))
```

| API `error.type` | Usual status | `:type` | Retryable |
|---|---|---|---|
| `invalid_request_error` | 400 | `:cl4cl.error/invalid-request` | No |
| `authentication_error` | 401 | `:cl4cl.error/authentication` | No |
| `billing_error` | 402 | `:cl4cl.error/billing` | No |
| `permission_error` | 403 | `:cl4cl.error/permission` | No |
| `not_found_error` | 404 | `:cl4cl.error/not-found` | No |
| `conflict_error` | 409 | `:cl4cl.error/conflict` | Yes |
| `request_too_large` | 413 | `:cl4cl.error/request-too-large` | No |
| `rate_limit_error` | 429 | `:cl4cl.error/rate-limit` | Yes |
| `api_error` | 500 | `:cl4cl.error/api` | Yes |
| `timeout_error` | 504 | `:cl4cl.error/timeout` | Yes |
| `overloaded_error` | 529 | `:cl4cl.error/overloaded` | Yes |
| no response at all | none | `:cl4cl.error/connection` | Yes |
| anything else | 408, 409, 429, 5xx | `:cl4cl.error/unknown-retryable` | Yes |
| anything else | other | `:cl4cl.error/unknown` | No |

Every type derives from `:cl4cl.error/error`, and the retryable ones from `:cl4cl.error/retryable`. `(error/retryable? e)` is a shorthand for the second test. A connection failure has the original exception as its cause.

### Retries

Retryable failures are retried before they reach you: twice by default, with exponential backoff and jitter. If the API sends `retry-after`, that delay is used instead, unless it is longer than `:max-retry-after-ms`.

```clojure
(assoc client :retry {:max 5         ; extra attempts after the first
                      :base-ms 500   ; first delay, doubling each time
                      :cap-ms 8000   ; longest computed delay
                      :max-retry-after-ms 60000}) ; optional; the longest retry-after to obey
```

A `retry-after` that is longer than `:max-retry-after-ms` (one minute unless you set it), negative or not a number of seconds is ignored and the computed delay is used, so a response cannot make your thread sleep for hours.

A streamed call is retried only if it fails before the first event. Once events have reached you, a failure is thrown, because you have already seen part of the reply.

The longest a non-streaming call can take is roughly `:timeout-ms` times one more than `:max`.

## Other endpoints and your own HTTP client

Every function above is a one-line composition of three public pieces. Use them directly for anything cl4cl has no function for.

```clojure
;; request: build the HTTP request as data. Pure.
(claude/request client nil {:method :get :path "/v1/models" :query {:limit 5}})
;; => {:method :get
;;     :url "https://api.anthropic.com/v1/models?limit=5"
;;     :headers {"anthropic-version" "2026-10-21" "x-api-key" "..."}
;;     :timeout-ms 600000}

;; execute: send it, retry as configured, and return the parsed reply
(claude/execute client
  (claude/request client nil {:method :get :path "/v1/models" :query {:limit 5}}))

;; lines: a reducible over a line-based reply, through a transducer
(claude/lines client http-request (map cl4cl.json/read-str))
```

To use a different HTTP client, a proxy or request signing, do the sending yourself between `request` and `response`:

```clojure
(let [http-request  (claude/request client body)
      http-response (my-send http-request)]     ; => {:status 200 :headers {...} :body "..."}
  (claude/response http-response))              ; the message, or throws as above
```

`response` expects `:body` as a string and header names in lower case.

## Function reference

**`cl4cl.core`**

| Function | Does |
|---|---|
| `(client)` `(client options)` | Make a client map |
| `(create client body)` `(… opts)` | `POST /v1/messages`; returns the message |
| `(stream client body)` `(… opts)` | The same, streamed; returns a reducible of events |
| `(final-message events)` | Fold events into the message |
| `(count-tokens client body)` `(… opts)` | `POST /v1/messages/count_tokens`; returns `{:input_tokens n}` |
| `(request client body)` `(… opts)` | The HTTP request as data; pure |
| `(response http-response)` | The parsed reply, or throws; pure |
| `(execute client http-request)` | Send with retries; returns `response` of the reply |
| `(lines client http-request xf)` | A reducible over the reply's lines through `xf` |
| `defaults` | The default client settings |
| `(guide)` | The usage guide for the installed version, as markdown text |

**`cl4cl.stream`**

| Function | Does |
|---|---|
| `accumulate` | Reducing function from events to a message |
| `(payloads)` | Transducer from SSE lines to events |
| `apply-delta` | Multimethod `[block delta]`, dispatched on the delta's `:type` |

**`cl4cl.batches`**

| Function | Does |
|---|---|
| `(create client body)` | `POST /v1/messages/batches` |
| `(retrieve client id)` | `GET /v1/messages/batches/{id}` |
| `(list client)` `(list client query)` | `GET /v1/messages/batches` |
| `(cancel client id)` | `POST /v1/messages/batches/{id}/cancel` |
| `(delete client id)` | `DELETE /v1/messages/batches/{id}` |
| `(results client id)` | `GET /v1/messages/batches/{id}/results`; a reducible |

Each also takes `opts` as a last argument.

**`cl4cl.error`**

| Function | Does |
|---|---|
| `(retryable? e)` | Is this exception a cl4cl failure worth retrying? |
| `types` | Map from the API's `error.type` strings to cl4cl keywords |

**`cl4cl.json`**

| Function | Does |
|---|---|
| `(read-str s)` | JSON text to data with keyword keys |
| `(write-str x)` | Data to JSON text; keyword keys keep their namespace |

`cl4cl.sse` and `cl4cl.http` are the SSE parser and the transport underneath. They are usable but you should rarely need them.

## Development

See `CLAUDE.md` for the REPL workflow and `doc/design.md` for the design.

```
lein repl                 # starts in the user namespace; try (say "Hello")
lein test                 # unit, spec and replay tests; no API key needed
lein test :integration    # live API tests; needs ANTHROPIC_API_KEY in .env
lein record               # re-record the replay cassettes against the live API
```

## License

Copyright © 2026 Zay Yar Lin

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
