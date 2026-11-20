---
name: repl
description: Develop cl4cl interactively against the project's running nREPL server. Use whenever writing, changing, exploring or testing Clojure code in this repository, before reaching for `lein test` or a fresh JVM.
---

# REPL-driven development for cl4cl

Evaluate code in the live project JVM instead of starting a new one. A `bb eval` round trip takes milliseconds; `lein test` takes many seconds.

## Connect

1. Check for a running server: the file `.nrepl-port` exists in the project root while one is up.
2. If there is none, start one in the background and wait for `.nrepl-port` to appear:

   ```bash
   lein repl :headless
   ```

   The `dev/` directory is on the REPL classpath, so `cl4cl.dev.*` namespaces load. If the user has their own editor REPL jacked in, use it; do not start a second one.

3. Evaluate:

   ```bash
   bb eval '(+ 1 2)'
   bb eval < /path/to/scratch.clj      # longer code: pass it on stdin
   ```

   Output, errors and the value of each form are printed. The exit code is 1 if evaluation threw.

## The loop

1. Edit the source file.
2. Reload it: `bb eval "(require 'cl4cl.sse :reload)"`.
3. Call the function with real data and look at the result.
4. Run the namespace's tests: `bb eval "(require 'cl4cl.sse-test :reload) (clojure.test/run-tests 'cl4cl.sse-test)"`.
5. Run one test: `bb eval "(clojure.test/test-vars [#'cl4cl.sse-test/parses-events])"`.

Run `lein test` once before each commit, as the clean-JVM check.

## Things to know

- Each `bb eval` is a new nREPL session that starts in the `user` namespace. Put `(in-ns 'some.ns)` first in the same call, or use fully qualified names.
- Vars defined with `def` persist in the server across calls; `*1` and the current namespace do not.
- Reload dependents too: after changing `cl4cl.json`, reload the namespaces that require it, or use `:reload-all` on the one being tested.
- A removed or renamed `defmethod` or var stays defined until the server restarts. If results stop making sense, restart the server.
- Changing `project.clj` (dependencies, profiles) needs a server restart.
- Explore the spec at the REPL rather than grepping the 45,000-line reference:

  ```clojure
  (require '[cl4cl.dev.spec :as spec])
  (def s (spec/load-spec))
  (spec/domain-type (:messages s) "Raw Content Block Delta")
  (filter #(= :stream-event (:kind %)) (spec/items s))
  ```
