(defproject cl4cl "0.1.0-SNAPSHOT"
  :description "Claude for Clojure: a pure Clojure client for the Claude Messages API"
  :url "https://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]]
  :profiles {:dev {:source-paths ["dev"]}}
  :test-selectors {:default (complement :integration)
                   :integration :integration}
  :repl-options {:init-ns cl4cl.core})
