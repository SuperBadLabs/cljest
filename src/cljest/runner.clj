(ns cljest.runner
  "Test execution engine — builds a quoted Clojure form that runs inside
   the project's JVM via leiningen.core.eval/eval-in-project.

   For each source namespace, one JVM is launched. Inside that JVM, the
   mutation loop runs: for each mutation, the source file is overwritten,
   the namespace is reloaded, tests run with a timeout, and the result
   (killed/survived/timed-out/error) is recorded. The original source is
   always restored via a finally block."
  (:require [cljest.coverage :as coverage]
            [cljest.mutator :as mutator]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.core.classpath :as lcp]
            [leiningen.core.eval :as eval]
            [leiningen.core.main :as main]))

;; ---------------------------------------------------------------------------
;; Coverage capture (runtime, project JVM)
;; ---------------------------------------------------------------------------

(defn- coverage-capture-form
  "Return a form that, evaluated in the project JVM against the ORIGINAL
   (unmutated) source, returns a map of source-var-name (symbol) -> set of
   covering test vars.

   It temporarily wraps every fn-valued var in the source namespace so that
   each invocation attributes itself to the currently-running test
   (clojure.test/*testing-vars*), runs the whole matched suite exactly once,
   then restores the originals. Because attribution is by var invocation,
   transitive calls are captured: any test whose execution reaches `foo`
   (directly or indirectly) is recorded as covering `foo`."
  [source-ns test-ns-forms]
  `(let [cov# (atom {})
         src-vars# (->> (ns-interns (quote ~source-ns))
                        (filter (fn [[_# v#]]
                                  (and (bound? v#) (fn? (deref v#))))))
         originals# (atom {})
         test-vars# (vec (for [tns# [~@test-ns-forms]
                               [_# v#] (ns-interns tns#)
                               :when (:test (meta v#))]
                           v#))]
     (doseq [[vname# v#] src-vars#]
       (let [orig# (deref v#)]
         (swap! originals# assoc v# orig#)
         (alter-var-root v#
                         (fn [_#]
                           (fn [& args#]
                             (when-let [tv# (peek clojure.test/*testing-vars*)]
                               (swap! cov# update vname# (fnil conj #{}) tv#))
                             (apply orig# args#))))))
     (try
       (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)
                 clojure.test/*test-out* (java.io.StringWriter.)]
         (clojure.test/test-vars test-vars#))
       (catch Throwable _#))
     ;; Always restore the original vars before the mutation loop begins.
     (doseq [[v# orig#] (deref originals#)]
       (alter-var-root v# (constantly orig#)))
     (deref cov#)))

;; ---------------------------------------------------------------------------
;; Form construction
;; ---------------------------------------------------------------------------

(defn build-mutation-form
  "Construct a quoted Clojure form that, when eval'd in the project JVM,
   runs all mutations for a single source namespace and writes results
   to a temp EDN file.

   When coverage? is true, a one-pass instrumented suite run first builds a
   var -> covering-tests map; each mutant then runs only the tests covering
   its enclosing var. A mutant whose enclosing var has no recorded coverage
   (uncovered, a non-fn def, a multimethod, or an unresolved position) falls
   back to running the full test set, so verdicts never change — only the
   number of tests run per mutant shrinks.

   Arguments:
     source-ns      — symbol, the namespace being mutated
     source-file    — string, absolute path to the source file
     test-nses      — vec of symbols, test namespaces to run
     mutations      — vec of {:position [r c] :operator-id :id :mutated-source \"...\"
                              :enclosing-var sym-or-nil}
     timeout-ms     — long, per-mutation test timeout
     results-file   — string, path to write EDN results
     coverage?      — boolean, enable coverage-guided test selection"
  [source-ns source-file test-nses mutations timeout-ms results-file coverage?]
  (let [test-ns-forms (mapv (fn [ns] `(quote ~ns)) test-nses)
        mutation-data (mapv (fn [m]
                              {:position (:position m)
                               :operator-id (:operator-id m)
                               :mutated-source (:mutated-source m)
                               :original-form (pr-str (:original-form m))
                               :enclosing-var (when-let [ev (:enclosing-var m)]
                                                (str ev))})
                            mutations)]
    `(do
       (require 'clojure.test)
       ;; Require all test namespaces upfront
       ~@(for [tns test-nses]
           `(require (quote ~tns)))
       (require (quote ~source-ns))

       (let [results# (atom [])
             mutation-vec# ~(vec mutation-data)
             total# (count mutation-vec#)
             ;; Full test-var set — the fallback whenever coverage is unknown.
             all-test-vars# (vec (for [tns# [~@test-ns-forms]
                                       [_# v#] (ns-interns tns#)
                                       :when (:test (meta v#))]
                                   v#))
             ;; var-name -> #{covering test var}; empty map disables selection.
             coverage# ~(if coverage?
                          (coverage-capture-form source-ns test-ns-forms)
                          {})]
         (.println System/err
                   (str "[cljest] coverage: " (count coverage#) " covered var(s), "
                        (count all-test-vars#) " test var(s)"
                        ~(if coverage? "" " (disabled)")))
         (.flush System/err)
         (try
           (doseq [mutation# mutation-vec#]
             (let [mutated-src# (:mutated-source mutation#)
                   op-id# (:operator-id mutation#)
                   pos# (:position mutation#)
                   enc# (:enclosing-var mutation#)
                   ;; Only narrow to covering tests when we have positive
                   ;; coverage evidence; otherwise run everything.
                   sel-vars# (let [k# (when enc# (symbol enc#))]
                               (if (and k# (contains? coverage# k#))
                                 (vec (get coverage# k#))
                                 all-test-vars#))]
               (try
                 ;; Apply the mutant in-memory by re-evaluating the full
                 ;; mutated source (its own ns form + defs). This is
                 ;; equivalent to a file reload but NEVER writes to the
                 ;; working tree, so an interrupted/killed run can never
                 ;; leave a mutation baked into the real source file.
                 (load-string mutated-src#)
                 ;; Run tests with timeout on a dedicated, low-priority daemon
                 ;; thread. A mutation can turn a loop infinite; future-cancel only
                 ;; *interrupts* and cannot stop a pure CPU-spin (and Thread.stop is
                 ;; gone on JDK 21+). By running at MIN_PRIORITY as a daemon, a leaked
                 ;; runaway can't starve the mutation loop on a multi-core box, and it
                 ;; dies with the JVM. We interrupt on timeout (handles the common
                 ;; interruptible cases: sleeps, blocking I/O, parking).
                 (let [result-promise# (promise)
                       worker# (Thread.
                                 ^Runnable
                                 (fn []
                                   (try
                                     (deliver result-promise#
                                       (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)
                                                 clojure.test/*test-out* (java.io.StringWriter.)]
                                         (clojure.test/test-vars sel-vars#)
                                         (let [result# (deref clojure.test/*report-counters*)]
                                           {:test (:test result# 0)
                                            :pass (:pass result# 0)
                                            :fail (:fail result# 0)
                                            :error (:error result# 0)})))
                                     (catch Throwable _#
                                       ;; Exception during the test run ⇒ treat as a
                                       ;; failing run (mutation killed).
                                       (deliver result-promise# {:test 0 :pass 0 :fail 1 :error 0}))))
                                 (str "cljest-mutant-" (System/nanoTime)))
                       _# (doto worker#
                            (.setDaemon true)
                            (.setPriority Thread/MIN_PRIORITY)
                            (.start))
                       test-result# (deref result-promise# ~timeout-ms ::timeout)]
                   (when (= test-result# ::timeout)
                     ;; Best-effort interrupt; daemon + MIN_PRIORITY means a
                     ;; non-interruptible spin won't block the rest of the run.
                     (.interrupt worker#))
                   (swap! results# conj
                          {:position pos#
                           :operator-id op-id#
                           :original-form (:original-form mutation#)
                           :status (cond
                                     (= test-result# ::timeout) :timed-out
                                     (pos? (+ (get test-result# :fail 0)
                                              (get test-result# :error 0)))
                                     :killed
                                     :else :survived)
                           :test-result (when (map? test-result#) test-result#)}))
                 (catch Throwable e#
                   ;; Compilation error or crash → mutation killed
                   (swap! results# conj
                          {:position pos#
                           :operator-id op-id#
                           :original-form (:original-form mutation#)
                           :status :killed
                           :error (.getMessage e#)})))
               ;; Progress beacon (to stderr) so long runs are observable and a
               ;; stall is detectable in real time.
               (let [done# (count @results#)
                     last# (peek @results#)]
                 (.println System/err
                           (str "[cljest] " done# "/" total# " "
                                (name (:status last# :survived)) " "
                                (:operator-id last#)))
                 (.flush System/err))))
           (finally
             ;; The working source file was never modified (mutants are applied
             ;; in-memory via load-string), so there is nothing to restore. We
             ;; still reload from the pristine on-disk source to leave this JVM's
             ;; namespace state matching the real file.
             (try (require (quote ~source-ns) :reload) (catch Throwable _#))))
         ;; Write results to temp file
         (spit ~results-file (pr-str @results#))
         ;; Force-exit: an exercised namespace may leave lingering non-daemon
         ;; threads (http-kit servers, executor/connection pools) that would
         ;; otherwise pin this per-namespace JVM open and hang eval-in-project
         ;; for the rest of the sweep. Results are already on disk, so flush
         ;; the beacon stream and hard-halt — skipping shutdown hooks, which
         ;; themselves can block on those same non-daemon threads.
         (.flush System/err)
         (.halt (Runtime/getRuntime) 0)))))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defn- make-results-file
  "Generate a unique temp file path for results (safe under parallel jobs)."
  []
  (str (System/getProperty "java.io.tmpdir")
       "/cljest-results-" (System/nanoTime) "-" (java.util.UUID/randomUUID) ".edn"))

(defn- worker-slot
  "Derive this worker's slot id from its thread name (cljest-worker-N), or
   \"0\" when running on the calling thread (sequential mode)."
  []
  (let [n (.getName (Thread/currentThread))]
    (if-let [m (re-find #"cljest-worker-(\d+)" n)]
      (second m)
      "0")))

(defn prepare-launch-context
  "Compile the project and resolve the classpath + JVM args ONCE, so each
   namespace can be launched as an independent raw `java` subprocess.

   Leiningen's `eval-in-project` re-runs prep (javac/compile) and rebuilds the
   classpath inside the parent JVM on every call, which serializes (and races)
   when invoked from parallel workers — measured as ~1 concurrent worker even
   with --jobs 8. Doing that work once and launching bare subprocesses removes
   the shared parent bottleneck entirely.

   Returns {:classpath <cp string> :jvm-args [..] :target-path <dir>}."
  [project]
  (eval/prep project)
  (let [jvm-args (if-let [f (resolve 'leiningen.core.eval/get-jvm-args)]
                   (vec (f project))
                   (vec (:jvm-opts project)))]
    {:classpath (str/join java.io.File/pathSeparator (lcp/get-classpath project))
     :jvm-args jvm-args
     :target-path (or (:target-path project) "target")}))

(defn- run-subprocess!
  "Launch `form` in a fresh JVM via clojure.main using the precomputed
   classpath/JVM args, with a per-worker java.io.tmpdir. Blocks until the
   process exits (the form writes its results file and halts). Returns the
   exit code."
  [launch-ctx slot form]
  (let [tmp (str (:target-path launch-ctx) "/cljest-par/slot-" slot "/tmp")
        _ (.mkdirs (io/file tmp))
        form-file (java.io.File/createTempFile "cljest-form-" ".clj")
        cmd (-> ["java"]
                (into (:jvm-args launch-ctx))
                (conj (str "-Djava.io.tmpdir=" tmp))
                (into ["-cp" (:classpath launch-ctx)
                       "clojure.main" (.getAbsolutePath form-file)]))]
    (spit form-file (pr-str form))
    (try
      (let [pb (doto (ProcessBuilder. ^java.util.List cmd)
                 (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                 (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT))]
        (.waitFor (.start pb)))
      (finally
        (.delete form-file)))))

(defn run-mutations-for-namespace
  "Run all mutations for a source namespace.

   1. Pre-compute mutated source strings (in Lein JVM via mutator)
   2. Build the mutation form
   3. Launch project JVM via eval-in-project
   4. Read results from temp EDN file

   Arguments:
     launch-ctx — precomputed {:classpath :jvm-args :target-path} from
                  prepare-launch-context (classpath/prep resolved once)
     src-ns     — namespace symbol
     src-file   — absolute source file path
     test-nses  — vec of test namespace symbols
     mutations  — seq of expanded mutation maps from mutator/expand-mutations
     config     — resolved config map

   Returns a vec of result maps with :status :killed/:survived/:timed-out/:error."
  [launch-ctx src-ns src-file test-nses mutations config]
  (let [results-file (make-results-file)
        timeout-ms (:timeout config 30000)
        verbose? (:verbose config)
        coverage? (:coverage config true)
        ;; Pre-compute all mutated source strings
        mutations-with-source
        (mapv (fn [m]
                (try
                  (let [mutated (mutator/apply-mutation src-file
                                                        (:position m)
                                                        (:operator-id m))]
                    (if mutated
                      (assoc m :mutated-source mutated)
                      nil))
                  (catch Exception e
                    (when verbose?
                      (main/info "  Skipping mutation at" (:position m)
                                 "- transform error:" (.getMessage e)))
                    nil)))
              mutations)
        ;; Resolve each mutation's enclosing top-level def (one parse) so the
        ;; runtime can select only the tests covering that var.
        valid-mutations (coverage/attach-enclosing-vars
                          src-file (filterv some? mutations-with-source))]

    (if (empty? valid-mutations)
      (do
        (when verbose?
          (main/info "  No valid mutations for" (str src-ns)))
        [])

      ;; Build the form (self-initializing: it requires clojure.test, the test
      ;; namespaces, and the source namespace itself).
      (let [form (build-mutation-form src-ns src-file test-nses
                                      valid-mutations timeout-ms results-file
                                      coverage?)]
        ;; Launch as an independent subprocess — no shared Leiningen machinery,
        ;; so parallel workers actually run concurrently.
        (try
          (run-subprocess! launch-ctx (worker-slot) form)
          ;; Read results
          (let [results-raw (slurp results-file)
                results (edn/read-string results-raw)]
            (io/delete-file results-file true)
            (vec results))
          (catch Exception e
            (main/warn "Error running mutations for" (str src-ns) "-" (.getMessage e))
            (io/delete-file results-file true)
            ;; Return all mutations as errors
            (mapv #(assoc % :status :error :error (.getMessage e))
                  valid-mutations)))))))
