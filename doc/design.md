# cl4cl design

cl4cl ("Claude for Clojure") is a pure Clojure client for the Claude Messages API over raw HTTP. It does not wrap the Java SDK.

Status: implemented as described here, including Message Batches. `doc/spec-checklist.md` shows what is covered.

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
| HTTP | `java.net.http.HttpClient` via interop; Java 21 is the minimum supported version | No dependency; streams response bodies for SSE |
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

Every call takes an optional third argument of per-call options, kept out of the body: `:betas`, `:timeout-ms`, `:headers`, and `:path` and `:method` for an endpoint cl4cl has no function for yet.

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

Two more public functions do the sending, and every endpoint function is a one-line composition of them with `request`:

```clojure
(claude/execute client http-request)      ; send, retry, and return `response` of the reply
(claude/lines client http-request xf)     ; a reducible over the reply's lines, through transducer xf
```

`create` is `execute` of a request; `stream` is `lines` with the SSE transducer; `cl4cl.batches/results` is `lines` with a JSON-per-line transducer. A caller reaches an endpoint cl4cl has no function for the same way, with `:path`, `:method` and `:query` in the options.

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
| `:connect-timeout-ms` | 30000 |
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
| `cl4cl.batches` | Message Batches, built only on `cl4cl.core`'s public functions | |
| `cl4cl.http` | Send a request map; retry | No |
| `cl4cl.json` | data.json read/write with the key rules below | Yes |
| `cl4cl.sse` | Lines to `{:event ... :data ...}` | Yes |
| `cl4cl.stream` | `accumulate` reducing function: events to a message | Yes |
| `cl4cl.error` | Non-2xx response to `ex-info`; the error hierarchy | Yes |

## JSON key rules

Reading uses `:key-fn keyword` everywhere, including tool inputs.

Writing must not use data.json's default key function, which calls `name` and so drops the namespace part: a tool input key `"a/b"` reads as `:a/b` and would be written back as `"b"`. `cl4cl.json` writes a keyword as its full text without the leading colon, and a string key as is. Round-tripping any response block back into a request is then lossless.

data.json reads and writes recursively, and how deeply a tool input is nested is the model's choice. `cl4cl.json` turns the resulting `StackOverflowError` into an `ex-info` of type `:cl4cl.error/unknown`, because an `Error` would get past a caller's `(catch Exception ...)`.

## Streaming

`stream` returns a reducible. Nothing is sent until it is reduced, and each reduction makes a new request.

- Each element is the parsed `data` payload of one SSE event, for example `{:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Hi"}}`.
- `ping` events are dropped.
- An `error` event throws the same `ex-info` as a non-2xx response.
- The connection is closed when the reduction completes, returns `reduced`, or throws.

`cl4cl.stream/accumulate` is a reducing function with init, step and completion arities, so it works with both `reduce` and `transduce`. It folds events into the same map `create` returns:

- `text_delta`, `thinking_delta` and `signature_delta` append to their block.
- `citations_delta` conjoins onto the block's `:citations`.
- `input_json_delta` appends `partial_json` to a buffer kept in the block's metadata and parsed at `content_block_stop`. If the buffer cannot be read, as when `max_tokens` cuts a tool call short or the model nests the input too deeply for the stack, `:input` is left alone and the text stays in the metadata under `:cl4cl.stream/partial-json`.
- Deltas are applied by the multimethod `cl4cl.stream/apply-delta`, so a caller can add a method for a delta type cl4cl does not know yet.
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
 :retry-after-ms 2000                    ; when the header is a number of seconds
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
| anything else | `:cl4cl.error/unknown` | |
| anything else, with status 408, 409, 429 or 5xx | `:cl4cl.error/unknown-retryable` | `:cl4cl.error/unknown`, `:cl4cl.error/retryable` |

Every type also derives from `:cl4cl.error/error`. Callers test with `isa?`, and can extend the hierarchy themselves:

```clojure
(catch clojure.lang.ExceptionInfo e
  (if (isa? (:type (ex-data e)) :cl4cl.error/retryable) ...))
```

A refusal is not an error: it is an HTTP 200 with `:stop_reason "refusal"`, and callers check `:stop_reason` before reading `:content`.

## Retries

Failures whose type is `:cl4cl.error/retryable` are retried according to the client's `:retry` map, with exponential backoff and jitter. A `retry-after` header overrides the computed delay when it is a number of seconds no longer than the policy's `:max-retry-after-ms`, one minute by default. The header is input from outside: one that is malformed, negative or longer than that is ignored, so a response cannot make a caller sleep for hours.

A streaming call is retried only if it fails before the first event has been handed to the caller. After that the error propagates, because the caller has already seen partial output.

## The spec as data

The upstream Messages API reference is vendored in `spec/upstream/` (four markdown files; `messages.md` is the full reference with every type). Anthropic does not publish a machine-readable OpenAPI document at a public URL, but the reference is generated from one and has a regular shape, so `cl4cl.dev.spec` parses it into data.

- `spec/load-spec` returns the documents as sections of bullet trees.
- `spec/items` flattens what cl4cl has to handle into maps identified by `[kind id]`, for example `[:stream-event "message_start"]`. There are 181 items today across 20 kinds: endpoints, headers, body parameters, content blocks, tools, response fields, stop reasons, stream events and deltas, batch parameters, fields and result types, error types and codes, and the API version.
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
| Endpoints | Call the public function with the network stubbed; assert the method and URL it sends |
| Headers, API version | Assert on the request map from `request` |
| Batch parameters, fields and results | Generate the value; assert it reaches the wire, or comes back from a stubbed reply, unchanged |

Four tests hold the system together:

1. **`spec-parses`**: every kind yields items, so an upstream format change that breaks the parser is noticed.
2. **`implemented-items-are-in-the-spec`**: an item that upstream removed cannot stay marked implemented.
3. **`implemented-items-verify`**: an item is marked implemented only if `verify` passes for it. A kind with no `verify` method fails.
4. **`checklist-is-current`**: the committed checklist matches what the generator produces.

Three more guard the edges:

5. **`nothing-in-the-spec-breaks-a-stream`**: every stream event, delta and block in the spec, plus invented unknown ones, passes through the parser and accumulator without throwing.
6. **`documented-streams-accumulate`**: the complete SSE transcripts in `spec/upstream/streaming.md` are replayed end to end.
7. **`verify-rejects-what-cl4cl-does-not-handle`**: `verify` says no to invented items, so a pass means something.

`(cl4cl.spec-test/verified)` at the REPL returns the items that verify; `spec/implemented.edn` should equal it.

### Recorded exchanges

The spec says what the API should send; cassettes hold what it did send. `cl4cl.dev.cassette` defines scenarios as data (a name, an operation and a request body). `lein record` runs them against the real API and writes one EDN file each to `test/cassettes/`: every HTTP exchange in order, as the request cl4cl built (credentials replaced by `REDACTED`) and the raw response (only the content type, request id and retry-after headers are kept), plus the value cl4cl returned. A batch scenario is many exchanges: create, the polls until it ends, results, list and delete.

`cl4cl.replay-test` needs no key. For each cassette it starts a local HTTP server that answers each request with the next recorded response, written in 7-byte pieces so lines and multi-byte characters are split across reads. The client runs against it unchanged, so `cl4cl.http` and real sockets are exercised. Two things must hold: the requests received equal the recorded ones, and the value returned equals the recorded one.

Cassettes catch regressions in cl4cl. They do not catch changes in the API: a cassette is frozen when it is recorded. Never edit one by hand, and never compare a cassette's value with a fresh live call, because model output varies.

### Live tests

Live tests are tagged `^:integration`, need `ANTHROPIC_API_KEY`, and are excluded from the default `lein test`. They and the recordings use Claude Haiku 5.5 to keep the cost down.

## Evolving with the API

A spec change is handled as a data change first and a code change second.

1. **Refresh.** `bb spec:update` fetches the upstream documents and regenerates the checklist.
2. **Read the diff.** `git diff doc/spec-checklist.md` is the work list: new unchecked lines are additions, deleted lines are removals. `git diff spec/upstream/` has the detail.
3. **Run the tests.** Removed items that were implemented fail `implemented-items-are-in-the-spec`. The robustness test shows whether new events or blocks already pass through.
4. **Commit the refresh by itself**, so the mechanical change is reviewable apart from the code.
5. **Implement each new item at the REPL**: extend the code or the `verify` method, add the item to `spec/implemented.edn`, regenerate the checklist, and commit per group of related items.
6. **Re-record the cassettes** with `lein record` when a key is available, add scenarios for new behaviour worth pinning, and review the cassette diff. Commit it by itself.
7. **Bump the default `anthropic-version` separately.** A new version shows up as an unchecked item under "API version". It can remove behaviour, so it follows the migration guide linked from `spec/upstream/versioning.md` and gets its own commit.

Because most of the API passes through cl4cl as data, a typical update needs no code at all: new parameters, blocks and tools verify with the existing methods and only move into `implemented.edn`.

### Automating it with a coding agent

The pipeline is built so an agent can run it without judgment calls until step 5. Steps 1 to 5 need no API key; re-recording and the live tests do.

- A scheduled agent runs steps 1 to 3. If the checklist diff is empty it stops.
- Otherwise it opens a pull request containing the refresh commit, with the checklist diff as the description.
- It then works through the unchecked items. For each one the definition of done is mechanical: the item is in `implemented.edn` and `lein test` passes. An item that needs a new public function or changes behaviour is left unchecked and listed in the pull request for a human decision.
- A human reviews and merges. The integration tests run with a real key before a release.

The agent's instructions belong in a project skill once the first manual update has been done and the rough edges are known.

## How a consumer's coding agent learns cl4cl

An agent in another project guesses from the official SDKs unless told otherwise, and those guesses are wrong here in predictable ways (kebab-case keys, keywords as values, treating a stream as a sequence). Two things travel inside the jar, so they always match the installed version:

- **Docstrings**, the reference for each function.
- **The guide**, `resources/cl4cl/guide.md`, returned by `(cl4cl.core/guide)`: the rules, the mistakes to avoid, and one example of each kind of call.

Nothing can force an agent to look, so the pointer is repeated wherever one is likely to land: the `cl4cl.core` namespace docstring, the top of the README, the project description shown on Clojars, and a snippet in the README for the consumer to paste into their own `CLAUDE.md` or `AGENTS.md`. The last is the only one that is certain to be read.

## Scope

**Done:** `client`, `request`, `response`, `execute`, `lines`, `create`, `stream`, `final-message`, `count-tokens`, Message Batches, errors, retries, beta headers.

**Later:** a tool-runner namespace, Files, the Models API. Until then `request` with `:path` reaches them.

**Not planned:** Bedrock, Vertex or Foundry clients.
