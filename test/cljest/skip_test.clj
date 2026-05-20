(ns cljest.skip-test
  "Tests for cljest.skip — docstring and log-body skip rules."
  (:require [clojure.test :refer [deftest testing is]]
            [cljest.mutator :as mut]
            [cljest.skip :as skip]
            [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- has-op? [sites op-id]
  (some #(contains? (:operator-ids %) op-id) sites))

;; ---------------------------------------------------------------------------
;; Docstring position skipping
;; ---------------------------------------------------------------------------

(deftest docstring-position-on-ns-is-skipped
  (testing "string at position 3 of (ns ...) is not mutated by const-nonempty-str-empty"
    (let [src   "(ns example.core \"Library docstring with stuff.\")"
          sites (mut/find-mutation-sites-from-string src #{:const-nonempty-str-empty})]
      (is (not (has-op? sites :const-nonempty-str-empty))
          "ns docstring must be skipped"))))

(deftest docstring-position-on-defn-is-skipped
  (testing "string at position 3 of (defn fname doc ...) is not mutated"
    (let [src   "(defn add \"Add two numbers.\" [a b] (+ a b))"
          sites (mut/find-mutation-sites-from-string src #{:const-nonempty-str-empty})]
      (is (not (has-op? sites :const-nonempty-str-empty))
          "defn docstring must be skipped"))))

(deftest docstring-position-on-defn-dash-is-skipped
  (testing "string at position 3 of (defn- fname doc ...) is not mutated"
    (let [src   "(defn- helper \"Private helper.\" [x] x)"
          sites (mut/find-mutation-sites-from-string src #{:const-nonempty-str-empty})]
      (is (not (has-op? sites :const-nonempty-str-empty))
          "defn- docstring must be skipped"))))

(deftest docstring-position-on-defmacro-is-skipped
  (testing "string at position 3 of (defmacro ...) is not mutated"
    (let [src   "(defmacro when-debug \"Conditional macro.\" [& body] `(when *debug* ~@body))"
          sites (mut/find-mutation-sites-from-string src #{:const-nonempty-str-empty})]
      (is (not (has-op? sites :const-nonempty-str-empty))
          "defmacro docstring must be skipped"))))

(deftest non-docstring-string-still-mutates
  (testing "string literals not at docstring position are still mutated"
    (let [src   "(defn greet [name] (str \"Hello, \" name))"
          sites (mut/find-mutation-sites-from-string src #{:const-nonempty-str-empty})]
      (is (has-op? sites :const-nonempty-str-empty)
          "Regular string literals in function bodies must remain mutable"))))

;; ---------------------------------------------------------------------------
;; clj-do-remove-form: skip when removed form is a log call
;; ---------------------------------------------------------------------------

(deftest do-remove-skips-leading-log-call
  (testing "do with leading log/info as first form is not mutated"
    (let [src   "(defn run [x] (do (log/info \"running\") (process x)))"
          sites (mut/find-mutation-sites-from-string src #{:clj-do-remove-form})]
      (is (not (has-op? sites :clj-do-remove-form))
          "Removing a log/info from a do block has no observable effect — skip"))))

(deftest do-remove-skips-leading-timbre-call
  (testing "do with leading taoensso.timbre/warn is not mutated"
    (let [src   "(defn run [x] (do (taoensso.timbre/warn \"alert\") (process x)))"
          sites (mut/find-mutation-sites-from-string src #{:clj-do-remove-form})]
      (is (not (has-op? sites :clj-do-remove-form))
          "Removing a timbre log call from a do block must be skipped"))))

(deftest do-remove-still-fires-when-first-form-is-real
  (testing "do whose first form is non-log code is still mutated"
    (let [src   "(defn run [x] (do (process! x) (log/info \"done\") :ok))"
          sites (mut/find-mutation-sites-from-string src #{:clj-do-remove-form})]
      (is (has-op? sites :clj-do-remove-form)
          "Removing a real side-effect must remain a valid mutation"))))

;; ---------------------------------------------------------------------------
;; when/when-not: skip when body is exclusively log calls
;; ---------------------------------------------------------------------------

(deftest when-not-skips-log-only-body
  (testing "when-not with log-only body is not flipped"
    (let [src   "(defn check [x] (when-not x (log/warn \"missing x\")))"
          sites (mut/find-mutation-sites-from-string src #{:logical-when-not-when})]
      (is (not (has-op? sites :logical-when-not-when))
          "when-not whose body is only logs must be skipped"))))

(deftest when-skips-log-only-body
  (testing "when with log-only body is not flipped to when-not"
    (let [src   "(defn check [x] (when x (log/info \"got x\")))"
          sites (mut/find-mutation-sites-from-string src #{:nil-when-when-not})]
      (is (not (has-op? sites :nil-when-when-not))
          "when whose body is only logs must be skipped"))))

(deftest when-not-fires-with-real-body
  (testing "when-not with non-log body is still mutated"
    (let [src   "(defn check [x] (when-not x (throw (ex-info \"missing\" {}))))"
          sites (mut/find-mutation-sites-from-string src #{:logical-when-not-when})]
      (is (has-op? sites :logical-when-not-when)
          "when-not with a meaningful body must remain mutable"))))

(deftest when-not-fires-with-mixed-body
  (testing "when-not with mixed log + real body is still mutated"
    (let [src   "(defn check [x] (when-not x (log/warn \"x missing\") (compute-fallback!)))"
          sites (mut/find-mutation-sites-from-string src #{:logical-when-not-when})]
      (is (has-op? sites :logical-when-not-when)
          "If any body form has observable effect, the form must remain mutable"))))

;; ---------------------------------------------------------------------------
;; if/if-not: skip when both branches are effect-free
;; ---------------------------------------------------------------------------

(deftest if-skips-both-branches-log-only
  (testing "if with both branches being log calls is not mutated"
    (let [src   "(defn dbg [x] (if x (log/info \"x\") (log/warn \"no x\")))"
          sites (mut/find-mutation-sites-from-string src
                                                      #{:nil-if-if-not :logical-negate-if})]
      (is (not (has-op? sites :nil-if-if-not))
          ":nil-if-if-not must skip when both branches are log-only")
      (is (not (has-op? sites :logical-negate-if))
          ":logical-negate-if must skip when both branches are log-only"))))

(deftest if-skips-then-log-else-missing
  (testing "if with then = log call and no else branch is not mutated"
    (let [src   "(defn maybe-log [x] (if x (log/info \"x\")))"
          sites (mut/find-mutation-sites-from-string src #{:nil-if-if-not})]
      (is (not (has-op? sites :nil-if-if-not))
          "Effectively-empty then-branch + missing else is observationally equivalent under flip"))))

(deftest if-fires-when-one-branch-real
  (testing "if with one log branch and one real branch is still mutated"
    (let [src   "(defn pick [x] (if x (compute!) (log/info \"defaulting\")))"
          sites (mut/find-mutation-sites-from-string src #{:nil-if-if-not})]
      (is (has-op? sites :nil-if-if-not)
          "A real then-branch keeps the form mutable"))))

;; ---------------------------------------------------------------------------
;; Helper-function unit checks
;; ---------------------------------------------------------------------------

(deftest skip-form-head-detects-log
  (let [z (z/of-string "(log/info \"hi\")")]
    (is (true? (skip/skip-form-head? z)))))

(deftest skip-form-head-rejects-non-log
  (let [z (z/of-string "(do-thing 1)")]
    (is (false? (skip/skip-form-head? z)))))

(deftest in-docstring-position-positive-ns
  (let [src "(ns foo \"doc\")"
        z   (z/of-string src {:track-position? true})
        ;; navigate to the string
        target (loop [loc z]
                 (cond
                   (z/end? loc) nil
                   (and (z/sexpr-able? loc) (= "doc" (try (z/sexpr loc) (catch Exception _ nil))))
                   loc
                   :else (recur (z/next loc))))]
    (is (some? target))
    (is (true? (skip/in-docstring-position? target)))))

(deftest in-docstring-position-negative-non-defining
  (let [src "(println \"hi\")"
        z   (z/of-string src {:track-position? true})
        target (loop [loc z]
                 (cond
                   (z/end? loc) nil
                   (and (z/sexpr-able? loc) (= "hi" (try (z/sexpr loc) (catch Exception _ nil))))
                   loc
                   :else (recur (z/next loc))))]
    (is (some? target))
    (is (false? (skip/in-docstring-position? target)))))
