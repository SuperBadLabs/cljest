(ns cljest.mutator
  "Mutation engine — uses rewrite-clj to find mutation sites in Clojure source
   and apply operator transforms while preserving formatting."
  (:require [cljest.operators :as ops]
            [cljest.skip :as skip]
            [rewrite-clj.zip :as z]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Position navigation
;; ---------------------------------------------------------------------------

(defn- zloc-position
  "Get [row col] position of a zipper location."
  [zloc]
  (let [pos (z/position zloc)]
    (when pos
      [(first pos) (second pos)])))

(defn navigate-to-position
  "Walk the zipper to find the node at [row col].
   Returns the zipper location at that position, or nil."
  [zloc target-pos]
  (loop [loc zloc]
    (cond
      (z/end? loc) nil

      (= (zloc-position loc) target-pos) loc

      :else (recur (z/next loc)))))

;; ---------------------------------------------------------------------------
;; Mutation site discovery
;; ---------------------------------------------------------------------------

(defn find-mutation-sites
  "Walk a source file and identify all locations where operators can be applied.
   Returns a seq of maps:
     {:position [row col]
      :operator-ids #{:op-id ...}
      :original-form <sexpr or string>
      :source-path <file path>}

   Arguments:
     source-path  — path to a .clj source file
     operator-ids — set of operator IDs to check"
  [source-path operator-ids]
  (let [operators (ops/operators-by-ids operator-ids)
        zloc (z/of-file (io/file source-path) {:track-position? true})]
    (loop [loc zloc
           sites []]
      (if (z/end? loc)
        sites
        (let [pos (zloc-position loc)
              ;; Skip mutations inside log/comment forms and at docstring
              ;; positions — both are observationally equivalent.
              matching (when (and pos
                                  (not (skip/in-skip-form? loc))
                                  (not (skip/in-docstring-position? loc)))
                         (filterv #(try
                                     ((:predicate %) loc)
                                     (catch Exception _ false))
                                  operators))
              new-sites (if (seq matching)
                          (conj sites {:position pos
                                       :operator-ids (set (map :id matching))
                                       :original-form (when (z/sexpr-able? loc)
                                                        (try (z/sexpr loc) (catch Exception _ nil)))
                                       :source-path (str source-path)})
                          sites)]
          (recur (z/next loc) new-sites))))))

(defn find-mutation-sites-from-string
  "Like find-mutation-sites, but takes a source string instead of file path.
   Useful for testing."
  [source-str operator-ids]
  (let [operators (ops/operators-by-ids operator-ids)
        zloc (z/of-string source-str {:track-position? true})]
    (loop [loc zloc
           sites []]
      (if (z/end? loc)
        sites
        (let [pos (zloc-position loc)
              matching (when (and pos
                                  (not (skip/in-skip-form? loc))
                                  (not (skip/in-docstring-position? loc)))
                         (filterv #(try
                                     ((:predicate %) loc)
                                     (catch Exception _ false))
                                  operators))
              new-sites (if (seq matching)
                          (conj sites {:position pos
                                       :operator-ids (set (map :id matching))
                                       :original-form (when (z/sexpr-able? loc)
                                                        (try (z/sexpr loc) (catch Exception _ nil)))})
                          sites)]
          (recur (z/next loc) new-sites))))))

;; ---------------------------------------------------------------------------
;; Mutation application
;; ---------------------------------------------------------------------------

(defn apply-mutation
  "Apply a single mutation to a source file.
   Returns the mutated source string (full file content with formatting preserved).

   Arguments:
     source-path — path to the .clj file
     position    — [row col] of the mutation target
     operator-id — keyword ID of the operator to apply"
  [source-path position operator-id]
  (let [operator (ops/operator-by-id operator-id)
        zloc (z/of-file (io/file source-path) {:track-position? true})
        target (navigate-to-position zloc position)]
    (when (and target operator)
      (let [mutated ((:transform operator) target)]
        (z/root-string mutated)))))

(defn apply-mutation-to-string
  "Like apply-mutation but operates on a source string.
   Useful for testing."
  [source-str position operator-id]
  (let [operator (ops/operator-by-id operator-id)
        zloc (z/of-string source-str {:track-position? true})
        target (navigate-to-position zloc position)]
    (when (and target operator)
      (let [mutated ((:transform operator) target)]
        (z/root-string mutated)))))

;; ---------------------------------------------------------------------------
;; Mutation expansion
;; ---------------------------------------------------------------------------

(defn expand-mutations
  "Expand mutation sites into individual mutation instances.
   Each site with N matching operators produces N mutations.
   Returns a seq of:
     {:position [row col]
      :operator-id :op-id
      :original-form <sexpr>
      :source-path <file path>}"
  [sites]
  (for [site sites
        op-id (:operator-ids site)]
    (-> site
        (dissoc :operator-ids)
        (assoc :operator-id op-id))))

(defn count-mutations
  "Count total mutations for a source file (for dry-run mode)."
  [source-path operator-ids]
  (let [sites (find-mutation-sites source-path operator-ids)]
    (reduce + 0 (map #(count (:operator-ids %)) sites))))
