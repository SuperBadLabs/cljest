(ns cljest.skip
  "Skip rules — forms and positions where a mutation would be observationally
   equivalent (i.e., it changes only the debug surface area such as docstrings
   or logging calls). Centralising these here lets both the mutator walker and
   individual operator predicates apply consistent rules without circular
   requires between the two."
  (:require [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------------------
;; Default skip-forms
;; ---------------------------------------------------------------------------

(def default-skip-forms
  "Head symbols of forms whose only observable effect is on debug output.
   Mutations to (or removal of) these forms are equivalent under
   functional semantics."
  #{'comment
    ;; clojure.tools.logging (commonly aliased as `log`)
    'log/info 'log/warn 'log/error 'log/debug 'log/trace 'log/fatal
    'log/infof 'log/warnf 'log/errorf 'log/debugf 'log/tracef 'log/fatalf
    'clojure.tools.logging/info 'clojure.tools.logging/warn
    'clojure.tools.logging/error 'clojure.tools.logging/debug
    'clojure.tools.logging/trace 'clojure.tools.logging/fatal
    ;; taoensso.timbre (commonly aliased as `log` or `timbre`)
    'timbre/info 'timbre/warn 'timbre/error 'timbre/debug 'timbre/trace 'timbre/fatal
    'taoensso.timbre/info 'taoensso.timbre/warn 'taoensso.timbre/error
    'taoensso.timbre/debug 'taoensso.timbre/trace 'taoensso.timbre/fatal
    ;; printing
    'println 'prn 'print 'printf 'pr})

;; ---------------------------------------------------------------------------
;; Zipper helpers
;; ---------------------------------------------------------------------------

(defn- list-head-sym
  "Return the head symbol of `zloc` when `zloc` is a list whose first child is
   a sexpr-able symbol; otherwise nil."
  [zloc]
  (when (and zloc (= :list (z/tag zloc)))
    (let [head (z/down zloc)]
      (when (and (some? head) (z/sexpr-able? head))
        (let [s (try (z/sexpr head) (catch Exception _ nil))]
          (when (symbol? s) s))))))

(defn skip-form-head?
  "True when `zloc` is a list whose head symbol is in `skip-set`
   (default: `default-skip-forms`)."
  ([zloc] (skip-form-head? zloc default-skip-forms))
  ([zloc skip-set]
   (boolean
     (when-let [h (list-head-sym zloc)]
       (contains? skip-set h)))))

(defn in-skip-form?
  "True when `zloc` is inside any ancestor whose head is a skip-form.
   Does *not* check `zloc` itself — that's what `skip-form-head?` is for."
  ([zloc] (in-skip-form? zloc default-skip-forms))
  ([zloc skip-set]
   (loop [loc (z/up zloc)]
     (cond
       (nil? loc) false
       (skip-form-head? loc skip-set) true
       :else (recur (z/up loc))))))

;; ---------------------------------------------------------------------------
;; Docstring detection
;; ---------------------------------------------------------------------------

(def ^:private defining-forms-with-docstring
  "Defining forms whose third child (after head and name) is the docstring."
  #{'ns 'defn 'defn- 'defmacro 'defmacro- 'defprotocol 'definterface
    'defrecord 'deftype})

(defn in-docstring-position?
  "True when `zloc` is a string literal occupying the docstring slot of a
   defining form — i.e., the 3rd child (after head + name) of an `ns`,
   `defn`, `defn-`, `defmacro`, `defmacro-`, `defprotocol`, `definterface`,
   `defrecord`, or `deftype` form."
  [zloc]
  (boolean
    (when (and zloc
               (z/sexpr-able? zloc)
               (string? (try (z/sexpr zloc) (catch Exception _ nil))))
      (when-let [parent (z/up zloc)]
        (when (and (= :list (z/tag parent))
                   (contains? defining-forms-with-docstring
                              (list-head-sym parent)))
          (let [head-child   (z/down parent)
                name-child   (some-> head-child z/right)
                doc-child    (some-> name-child z/right)]
            (and doc-child
                 (= (z/position doc-child) (z/position zloc)))))))))

;; ---------------------------------------------------------------------------
;; Body / branch composition checks
;; ---------------------------------------------------------------------------

(defn- effectively-empty-expr?
  "True when `loc` is nil, a literal nil, or a skip-form call — i.e., the
   expression has no observable effect beyond logging."
  [loc skip-set]
  (or (nil? loc)
      (and (z/sexpr-able? loc)
           (nil? (try (z/sexpr loc) (catch Exception _ ::not-nil))))
      (skip-form-head? loc skip-set)))

(defn body-only-skip-forms?
  "Given the head zloc of a `when`/`when-not`/`when-let`/`when-some` form,
   return true if every body expression after the condition is a skip-form
   call. Such forms are observationally equivalent under logical flips
   because their only effect is on debug output."
  ([head-zloc] (body-only-skip-forms? head-zloc default-skip-forms))
  ([head-zloc skip-set]
   (let [cond-zloc  (z/right head-zloc)
         body-start (some-> cond-zloc z/right)]
     (boolean
       (when body-start
         (loop [loc body-start
                seen-body? false]
           (cond
             (nil? loc) seen-body?
             (effectively-empty-expr? loc skip-set) (recur (z/right loc) true)
             :else false)))))))

(defn branches-only-skip-forms?
  "Given the head zloc of an `if`/`if-not` form, return true if both the
   then- and else-branches are effectively empty (nil, missing, or
   skip-form calls)."
  ([head-zloc] (branches-only-skip-forms? head-zloc default-skip-forms))
  ([head-zloc skip-set]
   (let [cond-zloc (z/right head-zloc)
         then-zloc (some-> cond-zloc z/right)
         else-zloc (some-> then-zloc z/right)]
     (and (effectively-empty-expr? then-zloc skip-set)
          (effectively-empty-expr? else-zloc skip-set)))))
