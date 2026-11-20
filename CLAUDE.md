# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

cl4cl ("Claude for Clojure") is a pure Clojure client for the Claude Messages API over raw HTTP: no Java SDK, and only `java.net.http` plus `org.clojure/data.json` at runtime. `doc/design.md` is the design and must be read before changing library code. The library namespaces it describes are not implemented yet; the spec tooling is.

The README, `doc/intro.md` and CHANGELOG are still Leiningen template placeholders.

## Design principle

When a design question comes up, ask what Rich Hickey would do, and use Clojure's own features: plain data instead of objects, pure functions with effects at the edge, reducibles and transducers, `ex-info` with keyword hierarchies, metadata, open maps, growth by accretion. `doc/design.md` lists the concrete rules that follow from this. Do not ask the user to settle individual design questions that this principle answers.

## Develop at the REPL

Work interactively against the running nREPL server rather than starting a JVM per change. The `repl` skill has the full workflow.

- `lein repl :headless` starts the server (writes `.nrepl-port`); `dev/` is on its classpath
- `bb eval '(+ 1 2)'` or `bb eval < file.clj` evaluates in it, starting in the `user` namespace
- `bb eval "(require 'cl4cl.spec-test :reload) (clojure.test/run-tests 'cl4cl.spec-test)"` runs one test namespace
- `bb eval "(clojure.test/test-vars [#'cl4cl.spec-test/spec-parses])"` runs one test

## Commands

- `lein test` runs all tests except `^:integration` ones; run it before each commit
- `lein test cl4cl.spec-test` runs one namespace; `lein test :only cl4cl.spec-test/spec-parses` runs one test
- `lein test :integration` runs live API tests (needs `ANTHROPIC_API_KEY`)
- `bb spec:checklist` regenerates `doc/spec-checklist.md`
- `bb spec:update` fetches the upstream spec and regenerates the checklist

No linter or formatter is configured.

## The spec drives the work

- `spec/upstream/*.md` is the vendored Claude API reference. Never edit it; refresh it with `bb spec:update`.
- `cl4cl.dev.spec` (in `dev/`) parses it into data. `spec/items` yields the checkable items, each identified by `[kind id]`.
- `spec/implemented.edn` is the set of implemented items. `doc/spec-checklist.md` is generated from it and is never edited by hand.
- `test/cl4cl/spec_test.clj` has a `verify` multimethod per item kind, which builds its test input from the spec.

To implement a spec item: write or extend the code, make `verify` pass for the item's kind, add `[kind id]` to `spec/implemented.edn`, run `bb spec:checklist`, then `lein test`. An item is never marked implemented without a passing `verify`.

The procedure for a new upstream spec version is in `doc/design.md` under "Evolving with the API".

## Commits

Commit at natural intervals: one coherent step per commit, with a spec refresh kept apart from the code that responds to it.
