(ns cljest.incremental
  "Git-diff-aware incremental scoping (CLJEST-INC-001).

   `--since REF` restricts mutation to only the sites on lines that differ from
   a git ref — the PR-gate workload. Instead of mutating all 14k sites across
   the repo, a typical PR run mutates the few tens of sites it actually changed.

   Verdict-equivalent to a full run restricted to those sites: we change WHICH
   sites are mutated, not how any mutant is evaluated (coverage capture and test
   selection are untouched), so a scoped mutant's kill/survive verdict is
   identical to its verdict in a full sweep."
  (:require [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn repo-root
  "Absolute path of the enclosing git work tree, or nil if not in one."
  []
  (let [{:keys [exit out]} (sh/sh "git" "rev-parse" "--show-toplevel")]
    (when (zero? exit)
      (str/trim out))))

(defn parse-unified-diff
  "Parse `git diff --unified=0` output into {repo-relative-path -> #{changed
   new-side line numbers}}. Only the new (post-image) side matters: those are
   the lines whose current content differs from the ref and thus carry the
   mutation sites we want to test. Pure — no git, no IO — so it is unit-testable
   on fixture text.

   Recognizes:
     `+++ b/<path>`             sets the current file (skips `/dev/null`)
     `@@ -a,b +c,d @@`          adds new-side lines c..c+d-1 (d defaults to 1;
                                d=0 is a pure deletion → no new lines)."
  [diff-text]
  (loop [lines (str/split-lines (or diff-text ""))
         cur nil
         acc {}]
    (if-let [line (first lines)]
      (cond
        (str/starts-with? line "+++ ")
        (let [path (subs line 4)
              path (cond
                     (= path "/dev/null") nil
                     (str/starts-with? path "b/") (subs path 2)
                     :else path)]
          (recur (rest lines) path acc))

        (str/starts-with? line "@@")
        ;; @@ -old[,n] +new[,m] @@ ...
        (let [m (re-find #"\+(\d+)(?:,(\d+))?" line)]
          (if (and cur m)
            (let [start (Long/parseLong (nth m 1))
                  cnt (if (nth m 2) (Long/parseLong (nth m 2)) 1)
                  newlines (when (pos? cnt) (set (range start (+ start cnt))))]
              (recur (rest lines) cur
                     (if (seq newlines)
                       (update acc cur (fnil into #{}) newlines)
                       acc)))
            (recur (rest lines) cur acc)))

        :else
        (recur (rest lines) cur acc))
      acc)))

(defn changed-lines
  "Map of ABSOLUTE source-file path -> #{changed line numbers} for the working
   tree vs `ref`. Returns nil if not in a git work tree or the diff fails (e.g.
   an unknown ref) — the caller should treat nil as a hard error rather than
   silently mutating nothing.

   `ref` is passed through to `git diff` verbatim, so both `main` (working tree
   vs ref) and `main...HEAD` (merge-base) forms work."
  [ref]
  (when-let [root (repo-root)]
    (let [{:keys [exit out]} (sh/sh "git" "diff" "--unified=0" ref)]
      (when (zero? exit)
        (into {}
              (map (fn [[rel lines]]
                     [(.getAbsolutePath (io/file root rel)) lines]))
              (parse-unified-diff out))))))

(defn filter-mutations
  "Keep only mutations whose enclosing line is in `changed` for `abs-file`.
   `changed` is the map from `changed-lines`; each mutation has a :position
   [row col]. A file with no changed lines yields no mutations."
  [changed abs-file mutations]
  (if-let [lineset (get changed abs-file)]
    (filterv (fn [m] (contains? lineset (first (:position m)))) mutations)
    []))
