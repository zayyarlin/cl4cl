;; Babashka script: evaluate code in the project's running nREPL server.
;; Usage: bb eval '(+ 1 2)'   or   bb eval < file.clj   (see bb.edn)
(ns nrepl-eval
  (:require [bencode.core :as b]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackInputStream]
           [java.net Socket]))

(defn- text [x] (if (bytes? x) (String. ^bytes x "UTF-8") x))

(defn- port []
  (let [f (io/file ".nrepl-port")]
    (when-not (.exists f)
      (binding [*out* *err*]
        (println "No .nrepl-port file. Start the REPL first: lein repl :headless"))
      (System/exit 2))
    (parse-long (str/trim (slurp f)))))

(defn -main [& args]
  (let [code (if (seq args) (str/join " " args) (slurp *in*))
        failed? (volatile! false)]
    (with-open [sock (Socket. "localhost" (int (port)))
                out (.getOutputStream sock)
                in (PushbackInputStream. (.getInputStream sock))]
      (b/write-bencode out {"op" "eval" "code" code "ns" "user" "id" "1"})
      (.flush out)
      (loop []
        (let [msg (b/read-bencode in)
              status (set (map text (get msg "status")))]
          (some-> (get msg "out") text print)
          (when-let [e (get msg "err")]
            (binding [*out* *err*] (print (text e)) (flush)))
          (some-> (get msg "value") text println)
          (flush)
          (when (some status ["eval-error" "error"]) (vreset! failed? true))
          (when-not (status "done") (recur)))))
    (when @failed? (System/exit 1))))
