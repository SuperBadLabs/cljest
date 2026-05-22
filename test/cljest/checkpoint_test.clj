(ns cljest.checkpoint-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [cljest.checkpoint :as cp])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- tmp-dir ^java.io.File []
  (.toFile (Files/createTempDirectory "cljest-cp-test" (make-array FileAttribute 0))))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    (.getAbsolutePath f)))

(defn- delete-tree! [^java.io.File f]
  (when (.isDirectory f) (run! delete-tree! (.listFiles f)))
  (.delete f))

;; ---------------------------------------------------------------------------
;; project-coverage-signature — the soundness contract for PERF-007
;;
;; A cached coverage map is only valid if a *fresh* capture would produce the
;; same map. That can shift from any change to code reachable from the tests,
;; so the signature MUST change when any source or test file content changes,
;; and MUST be invariant to run parameters (operators/timeout/threshold) since
;; coverage is independent of which mutants we apply.
;; ---------------------------------------------------------------------------

(deftest coverage-signature-stable-for-identical-content
  (testing "same source + test content ⇒ identical signature"
    (let [d (tmp-dir)]
      (try
        (let [src (spit-file d "src/app/core.clj" "(ns app.core) (defn f [x] (inc x))")
              _   (spit-file d "test/app/core_test.clj" "(ns app.core-test)")
              targets [{:source-file src :test-namespaces ['app.core-test]}]
              config  {:test-paths [(.getAbsolutePath (io/file d "test"))]}]
          (is (= (cp/project-coverage-signature targets config)
                 (cp/project-coverage-signature targets config))))
        (finally (delete-tree! d))))))

(deftest coverage-signature-changes-on-source-edit
  (testing "editing the source file changes the signature"
    (let [d (tmp-dir)]
      (try
        (let [src (spit-file d "src/app/core.clj" "(ns app.core) (defn f [x] (inc x))")
              _   (spit-file d "test/app/core_test.clj" "(ns app.core-test)")
              targets [{:source-file src :test-namespaces ['app.core-test]}]
              config  {:test-paths [(.getAbsolutePath (io/file d "test"))]}
              before  (cp/project-coverage-signature targets config)]
          (spit-file d "src/app/core.clj" "(ns app.core) (defn f [x] (dec x))")
          (is (not= before (cp/project-coverage-signature targets config))))
        (finally (delete-tree! d))))))

(deftest coverage-signature-changes-on-test-edit
  (testing "editing a test file changes the signature (verdict-safety: stale "
           "coverage from a changed test must not be reused)"
    (let [d (tmp-dir)]
      (try
        (let [src (spit-file d "src/app/core.clj" "(ns app.core) (defn f [x] (inc x))")
              _   (spit-file d "test/app/core_test.clj" "(ns app.core-test) (deftest a)")
              targets [{:source-file src :test-namespaces ['app.core-test]}]
              config  {:test-paths [(.getAbsolutePath (io/file d "test"))]}
              before  (cp/project-coverage-signature targets config)]
          (spit-file d "test/app/core_test.clj" "(ns app.core-test) (deftest a) (deftest b)")
          (is (not= before (cp/project-coverage-signature targets config))))
        (finally (delete-tree! d))))))

(deftest coverage-signature-invariant-to-run-parameters
  (testing "operators/timeout/threshold do NOT affect the coverage signature"
    (let [d (tmp-dir)]
      (try
        (let [src (spit-file d "src/app/core.clj" "(ns app.core) (defn f [x] (inc x))")
              _   (spit-file d "test/app/core_test.clj" "(ns app.core-test)")
              targets [{:source-file src :test-namespaces ['app.core-test]}]
              tp      (.getAbsolutePath (io/file d "test"))]
          (is (= (cp/project-coverage-signature targets {:test-paths [tp] :operators :fast
                                                         :timeout 1000 :threshold 50})
                 (cp/project-coverage-signature targets {:test-paths [tp] :operators :comprehensive
                                                         :timeout 99999 :threshold 90}))))
        (finally (delete-tree! d))))))

;; ---------------------------------------------------------------------------
;; coverage-cache-valid? — the read-side guard
;; ---------------------------------------------------------------------------

(deftest cache-valid-only-when-sig-matches
  (testing "valid iff entry exists and stored :sig matches"
    (let [d (tmp-dir)]
      (try
        (let [path (cp/coverage-cache-path (.getAbsolutePath d) 'app.core)]
          ;; missing entry ⇒ invalid
          (is (false? (cp/coverage-cache-valid? (.getAbsolutePath d) 'app.core "sig-1")))
          (spit path (pr-str {:sig "sig-1" :coverage {}}))
          (is (true?  (cp/coverage-cache-valid? (.getAbsolutePath d) 'app.core "sig-1")))
          ;; sig mismatch (project changed) ⇒ invalid
          (is (false? (cp/coverage-cache-valid? (.getAbsolutePath d) 'app.core "sig-2"))))
        (finally (delete-tree! d))))))

(deftest cache-valid-false-on-corrupt-entry
  (testing "a corrupt cache file degrades to invalid, never throws "
           "(so it falls back to recompute / full-suite — verdict-safe)"
    (let [d (tmp-dir)]
      (try
        (let [path (cp/coverage-cache-path (.getAbsolutePath d) 'app.core)]
          (spit path "{:sig \"sig-1\" :coverage ")   ; truncated EDN
          (is (false? (cp/coverage-cache-valid? (.getAbsolutePath d) 'app.core "sig-1"))))
        (finally (delete-tree! d))))))
