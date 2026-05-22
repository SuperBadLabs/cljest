(ns cljest.coverage
  "Coverage-guided test selection support.

   Mutation testing's dominant cost is re-running tests once per mutant. The
   vast majority of those runs are wasted: a mutant inside `foo` can only be
   killed by a test that actually exercises `foo`. This namespace provides the
   *static* half of coverage-guided selection — mapping each mutation position
   to the name of its enclosing top-level definition. The runner then asks, at
   runtime, 'which tests touched this var?' and runs only those.

   Granularity is top-level form (var) level, which is the pragmatic sweet
   spot: nearly every mutation lives inside one `defn`, and runtime coverage is
   captured by var invocation, so transitive calls are attributed correctly.

   Safety: callers treat a position with no resolvable enclosing var as
   'unknown' and fall back to running the full test set, so selection can only
   ever *shrink* the run when there is positive coverage evidence — it never
   changes a kill/survive verdict.

   The runtime capture itself lives as inline Clojure inside cljest.runner's
   mutation form, because the project JVM does not have cljest on its
   classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(defn- def-form-name
  "If the top-level list form at `zloc` is a def-like form (head symbol begins
   with \"def\", e.g. def/defn/defn-/defmethod/defmacro/defprotocol/defrecord),
   return the defined symbol; otherwise nil."
  [zloc]
  (when (= :list (z/tag zloc))
    (when-let [head (z/down zloc)]
      (when (z/sexpr-able? head)
        (let [h (try (z/sexpr head) (catch Exception _ nil))]
          (when (and (symbol? h) (str/starts-with? (name h) "def"))
            (when-let [nm (z/right head)]
              (when (z/sexpr-able? nm)
                (let [s (try (z/sexpr nm) (catch Exception _ nil))]
                  (when (symbol? s) s))))))))))

(defn- top-level-loc
  "Return the zipper located at the first top-level form, handling both the
   case where `z/of-*` descends to the first form and where it sits on the
   enclosing :forms node."
  [zloc]
  (if (= :forms (z/tag zloc))
    (z/down zloc)
    zloc))

(defn index-from-zipper
  "Build the enclosing-var index from a position-tracking zipper.
   Returns a vector of {:name sym :start row :end row} for each top-level
   def-like form, in source order."
  [zloc]
  (loop [loc (top-level-loc zloc)
         acc []]
    (if (or (nil? loc) (z/end? loc))
      acc
      (let [pos (z/position loc)
            start (when pos (first pos))
            nm (def-form-name loc)
            acc' (if (and nm start)
                   (let [text (z/string loc)
                         nlines (count (filter #(= % \newline) text))]
                     (conj acc {:name nm :start start :end (+ start nlines)}))
                   acc)
            nxt (z/right loc)]
        (recur nxt acc')))))

(defn enclosing-var-index
  "Parse the source file and return the enclosing-var index (see
   index-from-zipper). Returns [] on any parse error."
  [source-path]
  (try
    (index-from-zipper
      (z/of-file (io/file source-path) {:track-position? true}))
    (catch Exception _ [])))

(defn enclosing-var-index-from-string
  "Like enclosing-var-index but from a source string. Useful for testing."
  [source-str]
  (try
    (index-from-zipper (z/of-string source-str {:track-position? true}))
    (catch Exception _ [])))

(defn enclosing-var-name
  "Given an index (from enclosing-var-index) and a [row col] position, return
   the symbol of the enclosing top-level def whose line span contains row, or
   nil if none does."
  [index position]
  (when (and (seq index) (vector? position))
    (let [row (first position)]
      (some (fn [{:keys [name start end]}]
              (when (and (<= start row) (<= row end)) name))
            index))))

(defn attach-enclosing-vars
  "Given a source file path and a seq of mutation maps (each with :position),
   assoc :enclosing-var (a symbol or nil) onto each, using one parse of the
   file."
  [source-path mutations]
  (let [index (enclosing-var-index source-path)]
    (mapv #(assoc % :enclosing-var (enclosing-var-name index (:position %)))
          mutations)))
