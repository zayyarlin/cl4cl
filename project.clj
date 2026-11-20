(defproject io.github.zayyarlin/cl4cl "0.1.1-SNAPSHOT"
  :description "Claude for Clojure: a pure Clojure client for the Claude Messages API. Usage guide for people and coding agents: (println (cl4cl.core/guide))"
  :url "https://github.com/zayyarlin/cl4cl"
  :scm {:name "git" :url "https://github.com/zayyarlin/cl4cl"}
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  ;; Clojure is provided so that cl4cl does not choose its user's version.
  ;; 1.11 is the oldest that works.
  :dependencies [[org.clojure/clojure "1.12.2" :scope "provided"]
                 [org.clojure/data.json "2.5.1"]]
  ;; Credentials come from the environment: CLOJARS_USERNAME and
  ;; CLOJARS_PASSWORD, the latter a deploy token, not the account password.
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]
  :profiles {:dev {:source-paths ["dev"]}}
  :test-selectors {:default (complement :integration)
                   :integration :integration}
  :aliases {"record" ["run" "-m" "cl4cl.dev.cassette"]}
  :repl-options {:init-ns user})
