(ns cl4cl.dev.spec
  "The upstream Claude API documents (spec/upstream/*.md) as data.

  The Messages reference is generated markdown with a regular shape:
  headings name sections, and nested bullets of the form - `name: type`
  describe fields. `parse` turns a document into sections of bullet trees;
  `items` flattens the parts cl4cl has to handle into [kind id] pairs, the
  unit the checklist and tests work in."
  (:require [clojure.string :as str]))

(def upstream "spec/upstream")

(def sources
  "Where each vendored document comes from."
  {:messages   "https://platform.claude.com/docs/en/api/messages.md"
   :errors     "https://platform.claude.com/docs/en/api/errors.md"
   :streaming  "https://platform.claude.com/docs/en/build-with-claude/streaming.md"
   :versioning "https://platform.claude.com/docs/en/api/versioning.md"})

(defn- file [dir doc] (str dir "/" (name doc) ".md"))

(defn fetch!
  "Replace the vendored documents with the current upstream ones."
  ([] (fetch! upstream))
  ([dir]
   (doseq [[doc url] sources]
     (spit (file dir doc) (slurp url)))))

;; --- markdown -> tree ---

(defn- heading [line]
  (when-let [[_ hashes title] (re-matches #"(#{1,6}) (.+)" line)]
    {:level (count hashes) :title title}))

(defn- bullet [line]
  (when-let [[_ indent sig] (re-matches #"( *)- `(.+)`" line)]
    {:depth (quot (count indent) 2) :sig sig}))

(defn- parse-sig
  "Split a bullet signature into :name, :type and :optional?.
  A signature without a field name (a type or a literal) has only :type."
  [sig]
  (if-let [[_ q bare type] (re-matches #"(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*)): (.+)" sig)]
    (let [optional? (str/starts-with? type "optional ")]
      {:name (or q bare)
       :type (cond-> type optional? (subs (count "optional ")))
       :optional? optional?})
    {:type sig}))

(defn- nest
  "Turn a flat seq of {:depth :sig} into a tree of nodes with :children."
  [bullets]
  (letfn [(siblings [bs depth]
            (loop [bs bs, out []]
              (let [[b & more] bs]
                (if (and b (= depth (:depth b)))
                  (let [[kids rest-bs] (split-with #(< depth (:depth %)) more)
                        node (cond-> (parse-sig (:sig b))
                               (seq kids) (assoc :children (siblings kids (inc depth))))]
                    (recur rest-bs (conj out node)))
                  out))))]
    (siblings bullets 0)))

(defn- drop-fenced
  "Remove fenced code blocks, whose contents are examples, not structure."
  [lines]
  (first
   (reduce (fn [[out in-fence?] line]
             (if (str/starts-with? (str/triml line) "```")
               [out (not in-fence?)]
               [(if in-fence? out (conj out line)) in-fence?]))
           [[] false]
           lines)))

(defn parse
  "Parse reference markdown into a vector of sections, in document order:
  {:path [heading titles] :lines [prose lines] :nodes [bullet tree]}."
  [markdown]
  (let [lines (drop-fenced (str/split-lines markdown))]
    (loop [lines lines, path [], sections []]
      (if-let [[line & more] (seq lines)]
        (if-let [{:keys [level title]} (heading line)]
          (let [path (conj (vec (take (dec level) (concat path (repeat nil)))) title)
                [body rest-lines] (split-with (complement heading) more)]
            (recur rest-lines path
                   (conj sections {:path (vec (remove nil? path))
                                   :lines (vec (remove str/blank? (remove bullet body)))
                                   :nodes (nest (keep bullet body))})))
          (recur more path sections))
        sections))))

(defn load-spec
  "The vendored documents, parsed: a map of document name to sections."
  ([] (load-spec upstream))
  ([dir]
   (into {} (for [doc (keys sources)]
              [doc (parse (slurp (file dir doc)))]))))

;; --- tree queries ---

(defn section
  "The first section whose path ends with the given titles."
  [sections & titles]
  (let [n (count titles)]
    (first (filter #(= titles (take-last n (:path %))) sections))))

(defn- literal
  "The first string literal of a type such as \"text_delta\" or
  \"a\" or \"b\", else nil."
  [type]
  (second (re-find #"^\"([^\"]+)\"" (str type))))

(defn field [node name]
  (first (filter #(= name (:name %)) (:children node))))

(defn variants
  "The members of a union or enum node. An object member is identified by
  the literal of its `type` field, an enum member by its own literal."
  [node]
  (for [child (:children node)]
    (or (literal (:type child))
        (literal (:type (field child "type")))
        (:type child))))

(defn domain-type [sections title]
  (first (:nodes (section sections "Domain types" title))))

(defn variant
  "The member of a union node that `variants` identifies by id."
  [node id]
  (some (fn [[v child]] (when (= id v) child))
        (map vector (variants node) (:children node))))

;; --- sample values ---

(defn- type-name
  "The name a node defines, as in `TextBlock object` or `StopReason = ...`."
  [node]
  (second (re-find #"^([A-Z]\w*)(?: object$| = )" (str (:type node)))))

(defn types-by-name
  "The reference's domain types, by the name bullets use to refer to them."
  [sections]
  (into {}
        (for [{:keys [path nodes]} sections
              :when (= "Domain types" (peek (pop path)))
              :let [node (first nodes)]
              :when (type-name node)]
          [(type-name node) node])))

(defn- sample* [types depth type children]
  (let [type (-> (str type)
                 (str/replace #"^\w+ = " "")
                 (str/replace #" or null$" ""))
        sample-node (fn [c] (sample* types (inc depth) (:type c) (:children c)))
        referenced (types (second (re-find #"^([A-Z]\w*)" type)))]
    (cond
      (literal type)                      (literal type)
      (str/starts-with? type "array of ") [(sample* types depth (subs type (count "array of ")) children)]
      (some :name children)               (into {} (map (juxt (comp keyword :name) sample-node)) children)
      (seq children)                      (sample-node (first children))
      ;; A type the reference names here but spells out only under its own heading.
      (and referenced (< depth 8))        (sample-node referenced)
      (str/starts-with? type "number")    1
      (str/starts-with? type "boolean")   true
      (str/starts-with? type "map[")      {}
      (= type "object")                   {}
      :else                               "s")))

(defn sample
  "A value of the shape a node describes: every field of an object, the
  first member of a union or enum, one element in an array. It is built
  from the reference alone, so tests that use it follow the spec as it
  changes."
  [sections node]
  (sample* (types-by-name sections) 0 (:type node) (:children node)))

;; --- items: the things cl4cl is checked against ---

(defn- endpoints [sections]
  (for [{:keys [path lines]} sections
        line lines
        :let [[_ verb url] (re-matches #"\*\*([A-Z]+)\*\* `([^`]+)`" line)]
        :when verb]
    {:kind :endpoint :id (str verb " " url) :title (peek path)}))

(defn- error-types
  "The general error types, from the HTTP errors list in errors.md."
  [sections]
  (for [line (:lines (section sections "HTTP errors"))
        :let [[_ status type] (re-find #"^\* (\d+) - `(\w+)`" line)]
        :when type]
    {:kind :error-type :id type :status (parse-long status)}))

(defn- error-codes
  "The values of error.details.error_code that an endpoint section lists."
  [sections & titles]
  (for [line (:lines (apply section sections (concat titles ["Errors"])))
        :let [[_ code status] (re-find #"^- `(\w+)` \(HTTP status (\d+)\)" line)]
        :when code]
    {:kind :error-code :id code :status (parse-long status)}))

(defn- extra-stream-events
  "Events that the streaming guide describes in prose and the reference's
  event union omits, such as ping and error."
  [sections]
  (for [{:keys [path]} sections
        :let [[_ event] (re-matches #"(\w+) events" (peek path))]
        :when (and event (= "Event types" (peek (pop path))) (not= "Other" event))]
    {:kind :stream-event :id (str/lower-case event)}))

(defn- api-versions
  "The latest anthropic-version, the first entry of the version history."
  [sections]
  (for [line (take 1 (filter #(re-find #"^\* `\d{4}-\d\d-\d\d`" %)
                             (:lines (section sections "Version history"))))]
    {:kind :api-version :id (second (re-find #"`([^`]+)`" line))}))

(defn- named [kind nodes]
  (for [{:keys [name type optional?]} nodes]
    {:kind kind :id name :type type :optional? optional?}))

(defn- members [kind node]
  (for [v (variants node)] {:kind kind :id v}))

(defn items
  "Every checkable item in the spec, in a stable order, as maps with at
  least :kind and :id. [kind id] identifies an item across spec versions."
  [{:keys [messages errors streaming versioning]}]
  (let [dt (partial domain-type messages)]
    (vec
     (distinct
      (concat
       (api-versions versioning)
       (endpoints messages)
       (named :request-header (:nodes (section messages "Create a Message" "Headers")))
       (named :request-param (:nodes (section messages "Create a Message" "Body parameters")))
       (named :count-tokens-param (:nodes (section messages "Count tokens in a Message" "Body parameters")))
       (named :response-field (:children (dt "Message")))
       (members :stop-reason (dt "Stop Reason"))
       (members :stream-event (dt "Raw Message Stream Event"))
       (extra-stream-events streaming)
       (members :stream-delta (dt "Raw Content Block Delta"))
       (members :response-block (dt "Content Block"))
       (members :request-block (dt "Content Block Param"))
       (members :tool (dt "Tool Union"))
       (error-types errors)
       (error-codes messages "Create a Message")
       (error-codes messages "Count tokens in a Message"))))))

(defn item-key [{:keys [kind id]}] [kind id])
