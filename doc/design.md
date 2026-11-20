# cl4cl design

cl4cl ("Claude for Clojure") is a pure Clojure client for the Claude Messages API over raw HTTP. It does not wrap the Java SDK.

Status: draft, nothing implemented yet.

## Decisions

| Topic | Decision | Reason |
|---|---|---|
| HTTP | `java.net.http.HttpClient` (JDK 11+) via interop | No dependency; streams response bodies for SSE |
| JSON | `org.clojure/data.json` | Pure Clojure, no Jackson conflicts in users' projects |
| Streaming | `stream` returns a reducible (`IReduceInit`) of events | Works with `reduce`/`transduce`/`run!`; deterministic connection cleanup; no dependency |
| Keys | Wire names as keywords (`:max_tokens`, `:stop_reason`) | Matches the API docs one to one; no translation layer to maintain |
| Data model | Requests and responses are plain maps mirroring the wire JSON | New API fields and block types work without a library release |

Dependencies are therefore Clojure and data.json only.

## Public API (`cl4cl.core`)

```clojure
(require '[cl4cl.core :as claude])

(def client (claude/client))                 ; or (claude/client {:api-key "..."})

(claude/create client
  {:model "claude-opus-5-5"
   :max_tokens 16000
   :messages [{:role "user" :content "Hello"}]})
;; => {:id "msg_..." :type "message" :role "assistant"
;;     :content [{:type "text" :text "..."}]
;;     :stop_reason "end_turn" :usage {...}}

(claude/stream client request)               ; reducible of event maps
(claude/final-message (claude/stream client request))
(claude/count-tokens client request)         ; POST /v1/messages/count_tokens
```

Every call takes an optional third argument of per-call options, kept out of the request body:

```clojure
(claude/create client request {:betas ["compact-2026-01-12"] :timeout-ms 30000 :headers {...}})
```

### Client

`client` returns a plain map holding the configuration and one shared `HttpClient`.

| Option | Default |
|---|---|
| `:api-key` | `ANTHROPIC_API_KEY`; sent as `x-api-key` |
| `:auth-token` | `ANTHROPIC_AUTH_TOKEN`; sent as `Authorization: Bearer` |
| `:base-url` | `ANTHROPIC_BASE_URL`, else `https://api.anthropic.com` |
| `:version` | `2026-10-21`; sent as `anthropic-version` |
| `:betas` | `[]`; merged with per-call `:betas` into one `anthropic-beta` header |
| `:timeout-ms` | 600000 (10 minutes) for non-streaming calls |
| `:max-retries` | 2 |

An explicit `:api-key` wins over `:auth-token`. If neither is given or found in the environment, `client` throws.

### Responses

A response is the parsed body. HTTP details ride along as metadata so they never get sent back to the API when a caller appends the response to `:messages`:

```clojure
(meta response) ;; => {:cl4cl/status 200 :cl4cl/request-id "req_..." :cl4cl/headers {...}}
```

Callers continue a conversation by appending `{:role "assistant" :content (:content response)}` unchanged. Thinking, compaction and fallback blocks must round-trip exactly, which the plain-map model gives for free.

## Namespaces

| Namespace | Responsibility | Pure? |
|---|---|---|
| `cl4cl.core` | Public API | |
| `cl4cl.http` | Build requests and headers, send, retry | No |
| `cl4cl.json` | data.json read/write with the key rules below | Yes |
| `cl4cl.sse` | Lines to `{:event ... :data ...}` | Yes |
| `cl4cl.stream` | `accumulate` reducing function: events to a message | Yes |
| `cl4cl.error` | Non-2xx response to `ex-info` | Yes |

## JSON key rules

Reading uses `:key-fn keyword` everywhere, including tool inputs.

Writing must not use data.json's default key function, which calls `name` and so drops the namespace part: a tool input key `"a/b"` reads as `:a/b` and would be written back as `"b"`. `cl4cl.json` writes a keyword as its full text without the leading colon, and a string key as is. Round-tripping any response block back into a request is then lossless.

## Streaming

`stream` returns a reducible. Nothing is sent until it is reduced, and each reduction makes a new request.

- Each element is the parsed `data` payload of one SSE event, for example `{:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Hi"}}`.
- `ping` events are dropped.
- An `error` event throws the same `ex-info` as a non-2xx response.
- The connection is closed when the reduction completes, returns `reduced`, or throws.

`cl4cl.stream/accumulate` is a reducing function that folds events into the same map `create` returns:

- `text_delta`, `thinking_delta` and `signature_delta` append to their block.
- `input_json_delta` appends `partial_json` to a buffer that is parsed at `content_block_stop`; an empty buffer becomes `{}`.
- `message_delta` merges `stop_reason`, `stop_details` and `usage`.
- Unknown event and delta types are ignored, so new ones do not break old clients.

`final-message` is `(reduce stream/accumulate nil events)`. Side effects and the final message come from one pass:

```clojure
(transduce (map (fn [e] (print-delta e) e)) stream/accumulate (claude/stream client request))
```

Streaming calls have no total timeout, only a connect timeout, because a long generation is normal.

## Errors

Every failure is an `ex-info` with this data:

```clojure
{:type        :cl4cl.error/rate-limit   ; see table
 :status      429
 :error-type  "rate_limit_error"         ; the API's own error.type
 :message     "..."
 :request-id  "req_..."
 :retry-after-ms 2000                    ; when the header is present
 :retryable?  true
 :body        {...}}
```

| Status | `:type` | Retryable |
|---|---|---|
| 400 | `:cl4cl.error/bad-request` | No |
| 401 | `:cl4cl.error/authentication` | No |
| 402 | `:cl4cl.error/billing` | No |
| 403 | `:cl4cl.error/permission` | No |
| 404 | `:cl4cl.error/not-found` | No |
| 413 | `:cl4cl.error/request-too-large` | No |
| 429 | `:cl4cl.error/rate-limit` | Yes |
| 408, 409 | `:cl4cl.error/api` | Yes |
| 5xx, 529 | `:cl4cl.error/server`, `:cl4cl.error/overloaded` | Yes |
| none | `:cl4cl.error/connection` | Yes |

A refusal is not an error: it is an HTTP 200 with `:stop_reason "refusal"`, and callers check `:stop_reason` before reading `:content`.

## Retries

Retryable failures are retried up to `:max-retries` times with exponential backoff and jitter, starting at 0.5 s and capped at 8 s. A `retry-after` header overrides the computed delay.

A streaming call is retried only if it fails before the first event has been handed to the caller. After that the error propagates, because the caller has already seen partial output.

## Testing

- `cl4cl.sse`, `cl4cl.stream`, `cl4cl.json` and `cl4cl.error` are pure and are tested against recorded fixtures.
- `cl4cl.http` takes its send function from the client map (`:send-fn`, undocumented), so retry and header logic is tested with a stub and no network.
- Live tests are tagged `^:integration`, need `ANTHROPIC_API_KEY`, and are excluded from the default `lein test`.

## Scope

**v0.1:** `client`, `create`, `stream`, `final-message`, `count-tokens`, errors, retries, beta headers. Tool use, thinking, prompt caching, structured outputs and server tools need no dedicated code because requests are plain maps.

**Later:** a tool-runner loop, Message Batches, Files, the Models API.

**Not planned:** Bedrock, Vertex or Foundry clients; request validation against a schema (the API's 400 messages are the source of truth).

## Open questions

1. Should `create` reject `:stream true` in the request, or silently route to `stream`?
2. Should the non-streaming timeout scale with `:max_tokens` as the official SDKs do, or should the docs simply tell callers to stream large outputs?
3. Does the tool-runner belong in this library or in a separate one?
