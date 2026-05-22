(ns cljest.incremental-test
  (:require [clojure.test :refer [deftest testing is]]
            [cljest.incremental :as inc]))

;; ---------------------------------------------------------------------------
;; parse-unified-diff — pure, fixture-driven (the core of INC-001 scoping).
;; The new-side line set drives which mutation sites are tested; getting it
;; wrong silently mutates the wrong lines, so pin the hunk math precisely.
;; ---------------------------------------------------------------------------

(deftest parse-single-file-single-hunk
  (testing "@@ +c,d @@ yields new-side lines c..c+d-1"
    (let [diff (str "diff --git a/src/app/core.clj b/src/app/core.clj\n"
                    "index 111..222 100644\n"
                    "--- a/src/app/core.clj\n"
                    "+++ b/src/app/core.clj\n"
                    "@@ -10,2 +10,3 @@\n"
                    "+(some added)\n")]
      (is (= {"src/app/core.clj" #{10 11 12}}
             (inc/parse-unified-diff diff))))))

(deftest parse-count-defaults-to-one
  (testing "@@ +c @@ with no count means a single changed line c"
    (let [diff (str "+++ b/src/app/core.clj\n"
                    "@@ -5 +7 @@\n")]
      (is (= {"src/app/core.clj" #{7}}
             (inc/parse-unified-diff diff))))))

(deftest parse-pure-deletion-adds-no-new-lines
  (testing "+c,0 (pure deletion) contributes no new-side lines"
    (let [diff (str "+++ b/src/app/core.clj\n"
                    "@@ -8,3 +7,0 @@\n")]
      (is (= {} (inc/parse-unified-diff diff))))))

(deftest parse-multiple-hunks-union
  (testing "multiple hunks in one file union their changed lines"
    (let [diff (str "+++ b/src/app/core.clj\n"
                    "@@ -1 +1 @@\n"
                    "@@ -20,0 +21,2 @@\n")]
      (is (= {"src/app/core.clj" #{1 21 22}}
             (inc/parse-unified-diff diff))))))

(deftest parse-multiple-files
  (testing "tracks the current file across +++ markers"
    (let [diff (str "+++ b/src/app/a.clj\n"
                    "@@ -1 +1,2 @@\n"
                    "+++ b/src/app/b.clj\n"
                    "@@ -3 +4 @@\n")]
      (is (= {"src/app/a.clj" #{1 2}
              "src/app/b.clj" #{4}}
             (inc/parse-unified-diff diff))))))

(deftest parse-new-file-against-dev-null
  (testing "an added file (whole new content) is fully included"
    (let [diff (str "--- /dev/null\n"
                    "+++ b/src/app/new.clj\n"
                    "@@ -0,0 +1,4 @@\n")]
      (is (= {"src/app/new.clj" #{1 2 3 4}}
             (inc/parse-unified-diff diff))))))

(deftest parse-skips-deleted-file
  (testing "a deletion (+++ /dev/null) contributes nothing"
    (let [diff (str "--- a/src/app/gone.clj\n"
                    "+++ /dev/null\n"
                    "@@ -1,3 +0,0 @@\n")]
      (is (= {} (inc/parse-unified-diff diff))))))

(deftest parse-empty-and-nil
  (testing "empty or nil diff text yields an empty map"
    (is (= {} (inc/parse-unified-diff "")))
    (is (= {} (inc/parse-unified-diff nil)))))

;; ---------------------------------------------------------------------------
;; filter-mutations — keep only mutations whose line is in the changed set
;; ---------------------------------------------------------------------------

(deftest filter-keeps-only-changed-line-mutations
  (testing "mutations on changed lines are kept; others dropped"
    (let [changed {"/abs/app/core.clj" #{10 12}}
          muts [{:position [10 5] :operator-id :a}
                {:position [11 2] :operator-id :b}
                {:position [12 8] :operator-id :c}
                {:position [99 1] :operator-id :d}]]
      (is (= [{:position [10 5] :operator-id :a}
              {:position [12 8] :operator-id :c}]
             (inc/filter-mutations changed "/abs/app/core.clj" muts))))))

(deftest filter-file-with-no-changes-yields-none
  (testing "a file absent from the changed map contributes no mutations"
    (let [changed {"/abs/app/other.clj" #{1 2 3}}
          muts [{:position [1 0] :operator-id :a}]]
      (is (= [] (inc/filter-mutations changed "/abs/app/core.clj" muts))))))
