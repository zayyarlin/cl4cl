# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

This is a freshly generated Leiningen `default` library template (`lein new cl4cl`) with no real code yet. The README, `doc/intro.md`, `project.clj` description/URL, and the CHANGELOG entries (the "widget" items) are template placeholders, not descriptions of actual behavior — do not treat them as a spec. The directory is not yet a git repository.

## Commands

Leiningen project (Clojure 1.12.2).

- `lein test` — run all tests
- `lein test cl4cl.core-test` — run one test namespace
- `lein test :only cl4cl.core-test/a-test` — run a single test
- `lein repl` — start a REPL (starts in `cl4cl.core`)
- `lein jar` / `lein install` — build the jar / install it to the local Maven repo

No linter or formatter is configured.

The template test `cl4cl.core-test/a-test` fails on purpose (`(is (= 0 1))`), so `lein test` is red until it is replaced.

## Layout

- `src/cl4cl/` — library source; `cl4cl.core` is the only namespace
- `test/cl4cl/` — `clojure.test` tests, mirroring source namespaces with a `-test` suffix (file names use `_`, namespaces use `-`)
- `resources/` — empty, on the classpath
