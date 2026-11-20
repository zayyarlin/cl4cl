(ns cl4cl.dev.env
  "Read settings for development from a .env file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn dotenv
  "The KEY=VALUE lines of a .env file as a map, or {} if there is no file.
  Blank lines, # comments, a leading `export` and quotes around a value
  are ignored."
  ([] (dotenv ".env"))
  ([file]
   (if (.exists (io/file file))
     (into {}
           (keep (fn [line]
                   (when-let [[_ k v] (re-matches #"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*" line)]
                     [k (str/replace v #"^(['\"])(.*)\1$" "$2")])))
           (str/split-lines (slurp file)))
     {})))

(defn setting
  "A setting from .env, else from the environment, else nil."
  [k]
  (not-empty (or (get (dotenv) k) (System/getenv k))))

(defn api-key [] (setting "ANTHROPIC_API_KEY"))
