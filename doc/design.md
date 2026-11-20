# cl4cl design

cl4cl ("Claude for Clojure") is a pure Clojure client for the Claude Messages API over raw HTTP. It does not wrap the Java SDK.

Status: the spec tooling exists (`dev/`, `spec/`, `doc/spec-checklist.md`). The library itself is not implemented yet.

## Principles

The design question to ask is "what would Rich Hickey do?". In practice:

1. **Data, not objects.** Requests, responses, stream events, errors, the client and the spec itself are plain maps, vectors and keywords. There are no records, protocols or builders for API data.
2. **Simple, not easy.** Each part does one thing and the parts are not entangled: building a request is separate from sending it, parsing a stream is separate from reading a socket, and the retry policy is data rather than code inside the sender.
3. **Pure core, effects at the edge.** Only `cl4cl.http` touches the network. Everything else is a function from data to data and is developed and tested at the REPL.
4. **Use what Clojure already has.** Reducibles and transducers for streams, `ex-info` and keyword hierarchies (`derive`, `isa?`) for errors, metadata for out-of-band information, multimethods for open dispatch. No macros, no dynamic vars, no global default client.
5. **Open for extension by default.** Unknown fields, block types, events and error types pass through untouched. Anthropic adds to the API often; a new field must never need a cl4cl release.
6. **Grow by accretion.** Public names are never removed or given a new meaning. New capability arrives as new functions, new optional keys and new namespaces.
7. **Names say who owns them.** Data the API owns uses bare wire names (`:max_tokens`, `:stop_reason`). Data cl4cl owns is namespaced (`:cl4cl/request-id`, `:cl4cl.error/rate-limit`). The two can share a map without colliding.

## Decisions

| Topic | Decision | Reason |
|---|---|---|
| HTTP | `java.net.http.HttpClient` (JDK 11+) via interop | No dependency; streams response bodies for SSE |
| JSON | `org.clojure/data.json` | Pure Clojure, no Jackson conflicts in users' projects |
| Streaming | `stream` returns a reducible (`IReduceInit`) of events | Works with `reduce`/`transduce`/`run!`; deterministic connection cleanup |
| Keys | Wire names as keywords | Matches the API docs one to one; no translation layer |
| `:stream` in a request body | The function owns it: `create` sends `false`, `stream` sends `true`, whatever the caller put there | One function, one return type; nothing to reject |
| Timeout | A flat value in the client map, 10 minutes by default, with no scaling by `:max_tokens` | Policy is visible data, not hidden arithmetic. Long outputs should stream. |
| Tool-runner loop | Not in `cl4cl.core`. A later, separate namespace built only on the public API | A loop is policy on top of transport; keeping it apart keeps both simple |
| Request validation | None | The API's 400 messages are the source of truth and stay current |

Runtime dependencies are therefore Clojure and data.json only.

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

(claude/stream client body)                  ; reducible of event maps
(claude/final-message (claude/stream client body))
(claude/count-tokens client body)            ; POST /v1/messages/count_tokens
```

Every call takes an optional third argument of per-call options, kept out of the body:

```clojure
(claude/create client body {:betas ["compact-2026-01-12"] :timeout-ms 30000 :headers {...}})
```

### Requests are data too

`create` is a composition of three steps, and the first and last are pure and public:

```clojure
(claude/request client body opts)
;; => {:method :post
;;     :url "https://api.anthropic.com/v1/messages"
;;     :headers {"x-api-key" "..." "anthropic-version" "2026-10-21" "content-type" "application/json"}
;;     :body "{...}"}

(claude/response {:status 200 :headers {...} :body "{...}"})
;; => the message map, or throws the ex-info for a non-2xx status
```

A caller who wants a different HTTP client, a proxy, request signing or logging uses these two functions around their own transport. That is the whole extension mechanism; there is no client protocol to implement.

### Client

`client` returns an immutable map. It holds configuration and one shared `HttpClient`.

| Key | Default |
|---|---|
| `:api-key` | `ANTHROPIC_API_KEY`; sent as `x-api-key` |
| `:auth-token` | `ANTHROPIC_AUTH_TOKEN`; sent as `Authorization: Bearer` |
| `:base-url` | `ANTHROPIC_BASE_URL`, else `https://api.anthropic.com` |
| `:version` | `2026-10-21`; sent as `anthropic-version` |
| `:betas` | `[]`; merged with per-call `:betas` into one `anthropic-beta` header |
| `:timeout-ms` | 600000 for non-streaming calls |
| `:retry` | `{:max 2 :base-ms 500 :cap-ms 8000}` |

An explicit `:api-key` wins over `:auth-token`. If neither is given or found in the environment, `client` throws. Changing a setting for one call is `(assoc client :timeout-ms 5000)`.

### Responses

A response is the parsed body. HTTP details are metadata, so they are never sent back to the API when a caller appends the response to `:messages`:

```clojure
(meta response) ;; => {:cl4cl/status 200 :cl4cl/request-id "req_..." :cl4cl/headers {...}}
```

Callers continue a conversation by appending `{:role "assistant" :content (:content response)}` unchanged. Thinking, compaction and fallback blocks must round-trip exactly, and plain maps give that for free.

## Namespaces

| Namespace | Responsibility | Pure? |
|---|---|---|
| `cl4cl.core` | Public API | |
| `cl4cl.http` | Send a request map; retry | No |
| `cl4cl.json` | data.json read/write with the key rules below | Yes |
| `cl4cl.sse` | Lines to `{:event ... :data ...}` | Yes |
| `cl4cl.stream` | `accumulate` reducing function: events to a message | Yes |
| `cl4cl.error` | Non-2xx response to `ex-info`; the error hierarchy | Yes |

## JSON key rules

Reading uses `:key-fn keyword` everywhere, including tool inputs.

Writing must not use data.json's default key function, which calls `name` and so drops the namespace part: a tool input key `"a/b"` reads as `:a/b` and would be written back as `"b"`. `cl4cl.json` writes a keyword as its full text without the leading colon, and a string key as is. Round-tripping any response block back into a request is then lossless.

## Streaming

`stream` returns a reducible. Nothing is sent until it is reduced, and each reduction makes a new request.

- Each element is the parsed `data` payload of one SSE event, for example `{:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Hi"}}`.
- `ping` events are dropped.
- An `error` event throws the same `ex-info` as a non-2xx response.
- The connection is closed when the reduction completes, returns `reduced`, or throws.

`cl4cl.stream/accumulate` is a reducing function with init, step and completion arities, so it works with both `reduce` and `transduce`. It folds events into the same map `create` returns:

- `text_delta`, `thinking_delta` and `signature_delta` append to their block.
- `citations_delta` conjoins onto the block's `:citations`.
- `input_json_delta` appends `partial_json` to a buffer that is parsed at `content_block_stop`; an empty buffer becomes `{}`.
- `message_delta` merges `stop_reason`, `stop_details` and the cumulative `usage`.
- Unknown event and delta types are ignored.

`final-message` is `(transduce identity stream/accumulate events)`. Side effects and the final message come from one pass, with ordinary transducers:

```clojure
(transduce (map (fn [e] (print-delta e) e)) stream/accumulate (claude/stream client body))
```

Streaming calls have no total timeout, only a connect timeout, because a long generation is normal.

## Errors

Every failure is an `ex-info` with this data:

```clojure
{:type        :cl4cl.error/rate-limit
 :status      429
 :error-type  "rate_limit_error"         ; the API's own error.type, verbatim
 :error-code  "ramp_limit_exceeded"      ; error.details.error_code, when present
 :message     "..."
 :request-id  "req_..."
 :retry-after-ms 2000                    ; when the header is present
 :body        {...}}
```

`:type` is derived from the API's `error.type` first and the status second, because the API reuses statuses.

| API `error.type` | `:type` | Parent |
|---|---|---|
| `invalid_request_error` | `:cl4cl.error/invalid-request` | |
| `authentication_error` | `:cl4cl.error/authentication` | |
| `billing_error` | `:cl4cl.error/billing` | |
| `permission_error` | `:cl4cl.error/permission` | |
| `not_found_error` | `:cl4cl.error/not-found` | |
| `conflict_error` | `:cl4cl.error/conflict` | `:cl4cl.error/retryable` |
| `request_too_large` | `:cl4cl.error/request-too-large` | |
| `rate_limit_error` | `:cl4cl.error/rate-limit` | `:cl4cl.error/retryable` |
| `api_error` | `:cl4cl.error/api` | `:cl4cl.error/retryable` |
| `timeout_error` | `:cl4cl.error/timeout` | `:cl4cl.error/retryable` |
| `overloaded_error` | `:cl4cl.error/overloaded` | `:cl4cl.error/retryable` |
| no response | `:cl4cl.error/connection` | `:cl4cl.error/retryable` |
| anything else | `:cl4cl.error/unknown` | retryable if the status is 408, 409, 429 or 5xx |

Every type also derives from `:cl4cl.error/error`. Callers test with `isa?`, and can extend the hierarchy themselves:

```clojure
(catch clojure.lang.ExceptionInfo e
  (if (isa? (:type (ex-data e)) :cl4cl.error/retryable) ...))
```

A refusal is not an error: it is an HTTP 200 with `:stop_reason "refusal"`, and callers check `:stop_reason` before reading `:content`.

## Retries

Failures whose type is `:cl4cl.error/retryable` are retried according to the client's `:retry` map, with exponential backoff and jitter. A `retry-after` header overrides the computed delay.

A streaming call is retried only if it fails before the first event has been handed to the caller. After that the error propagates, because the caller has already seen partial output.

## The spec as data

The upstream Messages API reference is vendored in `spec/upstream/` (four markdown files; `messages.md` is the full reference with every type). Anthropic does not publish a machine-readable OpenAPI document at a public URL, but the reference is generated from one and has a regular shape, so `cl4cl.dev.spec` parses it into data.

- `spec/load-spec` returns the documents as sections of bullet trees.
- `spec/items` flattens what cl4cl has to handle into maps identified by `[kind id]`, for example `[:stream-event "message_start"]`. There are 158 items today across 14 kinds: endpoints, headers, body parameters, content blocks, tools, response fields, stop reasons, stream events and deltas, error types and codes, and the API version.
- `spec/implemented.edn` is the set of items cl4cl implements.
- `doc/spec-checklist.md` is rendered from those two by `bb spec:checklist`. It is never edited by hand.

`[kind id]` is the stable contract. If Anthropic publishes an OpenAPI document later, only the parser changes.

## Testing

Tests are driven by the spec, not by hand-written examples of what the author remembered.

`cl4cl.spec-test` defines a multimethod `verify`, dispatched on item kind. For each kind, `verify` builds its input from the spec's own type tree and exercises cl4cl with it:

| Kind | How `verify` checks an item |
|---|---|
| Body parameters, request blocks, tools | Generate a sample value from the type tree; assert it survives `request` and a JSON round trip unchanged |
| Stream events and deltas | Generate the event from its type tree, render it as SSE text, run it through `cl4cl.sse` and `accumulate`, and assert the message holds the sample content |
| Response fields and blocks, stop reasons | Generate a response body; assert `response` returns it unchanged and that the streamed form accumulates to the same value |
| Error types and codes | Generate the error body with its documented status; assert the `ex-data` |
| Endpoints, headers, API version | Assert on the request map from `request` |

Four tests hold the system together:

1. **`spec-parses`**: every kind yields items, so an upstream format change that breaks the parser is noticed.
2. **`implemented-items-are-in-the-spec`**: an item that upstream removed cannot stay marked implemented.
3. **`implemented-items-verify`**: an item is marked implemented only if `verify` passes for it. A kind with no `verify` method fails.
4. **`checklist-is-current`**: the committed checklist matches what the generator produces.

Two more will be added with the implementation:

- **Robustness for everything, implemented or not**: every stream event, delta and block in the spec, plus invented unknown ones, passes through the parser and accumulator without throwing.
- **Worked examples**: the complete SSE transcripts in `spec/upstream/streaming.md` are replayed as end-to-end fixtures.

Live tests are tagged `^:integration`, need `ANTHROPIC_API_KEY`, and are excluded from the default `lein test`.

## Evolving with the API

A spec change is handled as a data change first and a code change second.

1. **Refresh.** `bb spec:update` fetches the upstream documents and regenerates the checklist.
2. **Read the diff.** `git diff doc/spec-checklist.md` is the work list: new unchecked lines are additions, deleted lines are removals. `git diff spec/upstream/` has the detail.
3. **Run the tests.** Removed items that were implemented fail `implemented-items-are-in-the-spec`. The robustness test shows whether new events or blocks already pass through.
4. **Commit the refresh by itself**, so the mechanical change is reviewable apart from the code.
5. **Implement each new item at the REPL**: extend the code or the `verify` method, add the item to `spec/implemented.edn`, regenerate the checklist, and commit per group of related items.
6. **Bump the default `anthropic-version` separately.** A new version shows up as an unchecked item under "API version". It can remove behaviour, so it follows the migration guide linked from `spec/upstream/versioning.md` and gets its own commit.

Because most of the API passes through cl4cl as data, a typical update needs no code at all: new parameters, blocks and tools verify with the existing methods and only move into `implemented.edn`.

### Automating it with a coding agent

The pipeline is built so an agent can run it without judgment calls until step 5:

- A scheduled agent runs steps 1 to 3. If the checklist diff is empty it stops.
- Otherwise it opens a pull request containing the refresh commit, with the checklist diff as the description.
- It then works through the unchecked items. For each one the definition of done is mechanical: the item is in `implemented.edn` and `lein test` passes. An item that needs a new public function or changes behaviour is left unchecked and listed in the pull request for a human decision.
- A human reviews and merges. The integration tests run with a real key before a release.

The agent's instructions belong in a project skill once the first manual update has been done and the rough edges are known.

## Scope

**v0.1:** `client`, `request`, `response`, `create`, `stream`, `final-message`, `count-tokens`, errors, retries, beta headers.

**Later:** Message Batches (the six endpoints already in the checklist), a tool-runner namespace, Files, the Models API.

**Not planned:** Bedrock, Vertex or Foundry clients.
