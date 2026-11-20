# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

cl4cl ("Claude for Clojure") is a pure Clojure client for the Claude Messages API over raw HTTP: no Java SDK, and only `java.net.http` plus `org.clojure/data.json` at runtime. `doc/design.md` is the design and must be read before changing library code.

Only `cl4cl.http` touches the network. `cl4cl.core/request` and `response` are pure, and `execute` and `lines` do the sending, and every endpoint function (`create`, `count-tokens`, `stream`, everything in `cl4cl.batches`) is a one-line composition of those; `cl4cl.sse`, `cl4cl.stream`, `cl4cl.json` and `cl4cl.error` are pure data transformations.

## Design principle

When a design question comes up, ask what Rich Hickey would do, and use Clojure's own features: plain data instead of objects, pure functions with effects at the edge, reducibles and transducers, `ex-info` with keyword hierarchies, metadata, open maps, growth by accretion. `doc/design.md` lists the concrete rules that follow from this. Do not ask the user to settle individual design questions that this principle answers.

## Develop at the REPL

Work interactively against the running nREPL server rather than starting a JVM per change. The `repl` skill has the full workflow.

- `lein repl :headless` starts the server (writes `.nrepl-port`); `dev/` is on its classpath
- `bb eval '(+ 1 2)'` or `bb eval < file.clj` evaluates in it, starting in the `user` namespace
- `dev/user.clj` has helpers for live calls (`ask`, `say`, `client`); they are not part of the library. They read `ANTHROPIC_API_KEY` from `.env`, which is git-ignored
- Live calls, in the helpers and the integration tests, default to `claude-haiku-5-5` to save cost. Switch with `(use-model! "...")`, a `:model` key, or `CL4CL_MODEL` in `.env`
- `bb eval "(require 'cl4cl.spec-test :reload) (clojure.test/run-tests 'cl4cl.spec-test)"` runs one test namespace
- `bb eval "(clojure.test/test-vars [#'cl4cl.spec-test/spec-parses])"` runs one test

## Commands

- `lein test` runs all tests except `^:integration` ones; run it before each commit
- `lein test cl4cl.spec-test` runs one namespace; `lein test :only cl4cl.spec-test/spec-parses` runs one test
- `lein test :integration` runs live API tests (needs `ANTHROPIC_API_KEY`)
- `lein record` re-records every cassette in `test/cassettes/` against the real API; `lein record NAME...` records some (needs the key, costs a little)
- `bb spec:checklist` regenerates `doc/spec-checklist.md`
- `bb spec:update` fetches the upstream spec and regenerates the checklist

No linter or formatter is configured.

## The spec drives the work

- `spec/upstream/*.md` is the vendored Claude API reference. Never edit it; refresh it with `bb spec:update`.
- `cl4cl.dev.spec` (in `dev/`) parses it into data. `spec/items` yields the checkable items, each identified by `[kind id]`.
- `spec/implemented.edn` is the set of implemented items. `doc/spec-checklist.md` is generated from it and is never edited by hand.
- `test/cl4cl/spec_test.clj` has a `verify` multimethod per item kind, which builds its test input from the spec.

To implement a spec item: write or extend the code, make `verify` pass for the item's kind, add `[kind id]` to `spec/implemented.edn`, run `bb spec:checklist`, then `lein test`. An item is never marked implemented without a passing `verify`. `(cl4cl.spec-test/verified)` lists the items that currently verify.

Test inputs come from `cl4cl.dev.spec/sample`, which builds a value from the reference's type definitions. Prefer extending it over hand-writing fixtures.

The procedure for a new upstream spec version is in `doc/design.md` under "Evolving with the API".

## Tests without a key

Three layers run in `lein test` with no API key: unit tests with `http/send!` stubbed, the spec-driven `verify` tests, and `cl4cl.replay-test`, which replays the recorded exchanges in `test/cassettes/` through a local HTTP server. Scenarios are data in `cl4cl.dev.cassette/scenarios`; a new scenario needs `lein record NAME` before the suite passes. Never edit a cassette by hand. If a change to cl4cl alters the request it sends or the value it returns, the replay test fails: decide whether the change is intended, and re-record only if it is.

## Docstrings and the guide

Agents working in other projects learn cl4cl from two places that ship in the jar: the docstrings, and `resources/cl4cl/guide.md`, which `(cl4cl.core/guide)` returns. Both must describe the code as it is. Whenever a public function is added, removed, renamed, or changes its arguments, options, return value or behaviour, update in the same commit:

- its docstring, and the namespace docstring if the overview changes
- `resources/cl4cl/guide.md`: the relevant example, and the "Mistakes to avoid" list if the change creates or removes a pitfall
- `README.md`, including its function reference

`cl4cl.guide-test` enforces part of this: every public var has a docstring, the guide mentions every function in `cl4cl.core` and `cl4cl.batches`, names nothing that does not exist, and its examples are readable Clojure. It cannot tell whether the prose is still true, so read the guide section you touched. The guide is for a reader with no access to this repository: keep it self-contained and under about 200 lines.

## Releasing

The artifact is `io.github.zayyarlin/cl4cl` on Clojars. A release is permanent, and deploying is the user's step, not Claude's.

1. Set the version in `project.clj` (drop `-SNAPSHOT`), date the entry in `CHANGELOG.md`, run `lein test`, commit as "Release X.Y.Z", and tag `vX.Y.Z`.
2. The user runs `bin/deploy`, which reads the Clojars username and deploy token from the macOS Keychain item `clojars-deploy`, refuses a dirty tree or an untagged release, runs the tests and deploys. `bin/deploy --dry-run` stops before the deploy.
3. Push `main` and the tag, then set the next `-SNAPSHOT` version.

## Commits

Commit at natural intervals: one coherent step per commit, with a spec refresh kept apart from the code that responds to it.
