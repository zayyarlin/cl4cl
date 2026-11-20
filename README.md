# cl4cl

Claude for Clojure: the Claude Messages API as functions over plain data.

cl4cl talks to the API over raw HTTP. It needs JDK 11 or later and has one dependency besides Clojure, `org.clojure/data.json`. Requests and responses are ordinary maps that mirror the API's JSON, so anything in the [API reference](https://platform.claude.com/docs/en/api/messages) works without waiting for a library release.

Status: not released yet. Messages, streaming, token counting and Message Batches are implemented. `doc/spec-checklist.md` lists every part of the API reference and whether it is covered.

## Usage

```clojure
(require '[cl4cl.core :as claude])

(def client (claude/client))   ; reads ANTHROPIC_API_KEY, or pass {:api-key "..."}

(claude/create client
  {:model "claude-opus-5-5"
   :max_tokens 16000
   :messages [{:role "user" :content "What is a transducer?"}]})
;; => {:id "msg_..." :type "message" :role "assistant"
;;     :content [{:type "thinking" ...} {:type "text" :text "..."}]
;;     :stop_reason "end_turn" :usage {...}}
```

Keys are keywords in the API's own spelling (`:max_tokens`, `:stop_reason`). Check `:stop_reason` before reading `:content`.

### Streaming

`stream` returns a reducible of event maps. Nothing is sent until you reduce it, and the connection closes when the reduction ends.

```clojure
(require '[cl4cl.stream :as stream])

;; print text as it arrives
(run! (fn [event]
        (when (= "text_delta" (get-in event [:delta :type]))
          (print (get-in event [:delta :text]))
          (flush)))
      (claude/stream client body))

;; just the finished message, the same map `create` returns
(claude/final-message (claude/stream client body))

;; both in one pass
(transduce (map observe) stream/accumulate (claude/stream client body))
```

### Conversations and tools

Append the response's `:content` unchanged, then the next user turn:

```clojure
(let [reply (claude/create client body)]
  (claude/create client
    (update body :messages conj
            {:role "assistant" :content (:content reply)}
            {:role "user" :content "Say more."})))
```

Tools, thinking, prompt caching and structured outputs are all request data: put the keys the API reference describes into the body.

### Per-call options

```clojure
(claude/create client body {:betas ["compact-2026-01-12"]
                            :timeout-ms 30000
                            :headers {"anthropic-workspace-id" "wrkspc_..."}})
```

### Errors

A failed call throws an `ex-info`. Its `:type` is a keyword in a hierarchy, so test it with `isa?`:

```clojure
(try
  (claude/create client body)
  (catch clojure.lang.ExceptionInfo e
    (let [{:keys [type status message request-id]} (ex-data e)]
      (cond
        (isa? type :cl4cl.error/rate-limit) ...
        (isa? type :cl4cl.error/retryable)  ...
        :else (throw e)))))
```

Retryable failures (rate limits, overload, server errors, lost connections) are retried twice with backoff before they reach you. Change that with `(assoc client :retry {:max 5 :base-ms 500 :cap-ms 8000})`.

### Message Batches

```clojure
(require '[cl4cl.batches :as batches])

(def batch
  (batches/create client
    {:requests [{:custom_id "q1" :params {:model "claude-opus-5-5" :max_tokens 16000
                                          :messages [{:role "user" :content "..."}]}}]}))

(batches/retrieve client (:id batch))        ; poll until :processing_status is "ended"
(into [] (batches/results client (:id batch)))
;; => [{:custom_id "q1" :result {:type "succeeded" :message {...}}}]

(batches/list client {:limit 20})            ; a page: {:data [...] :has_more ... :last_id ...}
(batches/cancel client (:id batch))
(batches/delete client (:id batch))
```

`results` is a reducible, like `stream`. Results come in no particular order; match them by `:custom_id`.

### Other endpoints

`request` builds a call to any path, `execute` sends it and parses the reply, and `lines` gives a reducible over a line-based reply:

```clojure
(claude/execute client (claude/request client nil {:method :get :path "/v1/models" :query {:limit 5}}))
```

### Your own HTTP client

`request` and `response` are pure, so you can do the sending yourself:

```clojure
(claude/request client body)   ;; => {:method :post :url "..." :headers {...} :body "{...}"}
(claude/response {:status 200 :headers {...} :body "{...}"})   ;; => the message, or throws
```

## Development

See `CLAUDE.md` for the REPL workflow and `doc/design.md` for the design.

```
lein repl                 # starts in the user namespace; try (say "Hello")
lein test                 # unit and spec tests
lein test :integration    # live API tests; needs ANTHROPIC_API_KEY in .env
```

## License

Copyright © 2026

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
