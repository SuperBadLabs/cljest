(ns cljest.coverage-test
  (:require [cljest.coverage :as coverage]
            [clojure.test :refer [deftest is testing]]))

(def sample-source
  "(ns demo.math)

(defn add
  [a b]
  (+ a b))

(def TAX 0.2)

(defn total
  [items]
  (reduce + 0 items))

(defmethod area :circle
  [shape]
  (* 3.14 (:r shape)))
")

(deftest enclosing-var-index-from-string-test
  (testing "indexes def-like top-level forms with line spans"
    (let [idx (coverage/enclosing-var-index-from-string sample-source)
          by-name (into {} (map (juxt :name identity) idx))]
      (is (= #{'add 'TAX 'total 'area} (set (map :name idx))))
      (testing "spans cover the body lines"
        ;; add starts at line 3, body runs through line 5
        (is (= 3 (:start (by-name 'add))))
        (is (<= 5 (:end (by-name 'add))))
        ;; total starts at line 9
        (is (= 9 (:start (by-name 'total))))))))

(deftest enclosing-var-name-test
  (let [idx (coverage/enclosing-var-index-from-string sample-source)]
    (testing "resolves a position inside a defn to its name"
      ;; line 5 is inside (defn add ...)
      (is (= 'add (coverage/enclosing-var-name idx [5 3])))
      ;; line 11 is inside (defn total ...)
      (is (= 'total (coverage/enclosing-var-name idx [11 3]))))
    (testing "resolves defmethod to the multifn symbol"
      (is (= 'area (coverage/enclosing-var-name idx [15 3]))))
    (testing "returns nil for positions outside any def (ns form / blank lines)"
      (is (nil? (coverage/enclosing-var-name idx [1 1])))
      (is (nil? (coverage/enclosing-var-name idx [100 1]))))
    (testing "nil-safe on empty index or bad position"
      (is (nil? (coverage/enclosing-var-name [] [5 3])))
      (is (nil? (coverage/enclosing-var-name idx nil))))))

(deftest attach-enclosing-vars-string-fallback-test
  (testing "enclosing-var-index returns [] on a missing file (safe fallback)"
    (is (= [] (coverage/enclosing-var-index "/nonexistent/path/nope.clj")))))
