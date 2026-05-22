(ns cljest.core
  "Orchestrator — ties selector, mutator, runner, and reporter together
   into the main mutation testing pipeline."
  (:require [cljest.checkpoint :as checkpoint]
            [cljest.config :as config]
            [cljest.mutator :as mutator]
            [cljest.operators :as ops]
            [cljest.reporter :as reporter]
            [cljest.runner :as runner]
            [cljest.selector :as selector]
            [clojure.string :as str]
            [leiningen.core.main :as main]))

;; ---------------------------------------------------------------------------
;; Progress output
;; ---------------------------------------------------------------------------

(defn- progress
  "Print a progress message if verbose or always for key milestones."
  [verbose? & args]
  (when verbose?
    (apply main/info args)))

(defn- parallel-doseq
  "Apply f to each item of coll, blocking until all complete.

   With jobs<=1, runs sequentially on the calling thread. With jobs>1, runs on
   a fixed pool of `jobs` daemon threads named `cljest-worker-<slot>` — the
   runner derives a per-worker target/tmp isolation directory from that slot so
   concurrent project JVMs never share a compile-path or temp dir. Each
   namespace already runs in its own subprocess JVM (process isolation) and
   mutants are applied in-memory (CLJEST-ISO-001), so the working source tree is
   read-only and safe to share across workers. The first worker exception is
   rethrown after the pool is shut down."
  [jobs f coll]
  (if (<= jobs 1)
    (doseq [x coll] (f x))
    (let [factory (let [i (atom -1)]
                    (reify java.util.concurrent.ThreadFactory
                      (newThread [_ r]
                        (doto (Thread. r (str "cljest-worker-" (swap! i inc)))
                          (.setDaemon true)))))
          pool (java.util.concurrent.Executors/newFixedThreadPool (int jobs) factory)]
      (try
        (let [futures (mapv (fn [x]
                              (.submit pool ^java.util.concurrent.Callable
                                       (fn [] (f x))))
                            coll)]
          (doseq [^java.util.concurrent.Future fut futures]
            (.get fut)))
        (finally
          (.shutdown pool))))))

;; ---------------------------------------------------------------------------
;; Main pipeline
;; ---------------------------------------------------------------------------

(defn run-mutation-testing
  "Execute the full mutation testing pipeline.

   Arguments:
     project — Leiningen project map
     config  — resolved config map (from config/resolve-config)

   Returns report-data map with :results, :duration-ns, etc."
  [project config]
  (let [verbose? (:verbose config)
        dry-run? (:dry-run config)
        resume? (:resume config)
        checkpoint-dir (:checkpoint-dir config)
        jobs (max 1 (long (:jobs config 1)))
        operator-ids (ops/resolve-preset (:operators config))
        start-time (System/nanoTime)]

    ;; Optionally wipe checkpoint before running
    (when (and (:clear-checkpoint config) (not dry-run?))
      (let [n (checkpoint/clear! checkpoint-dir)]
        (main/info (format "  Cleared %d checkpoint entrie(s) in %s" n checkpoint-dir))))

    ;; Banner
    (main/info)
    (main/info "================================================================")
    (main/info "              CLJEST MUTATION TESTING")
    (main/info "================================================================")
    (main/info (format "  Operators:  %s (%d)"
                       (name (:operators config)) (count operator-ids)))
    (main/info (format "  Threshold:  %d%%" (:threshold config)))
    (main/info (format "  Timeout:    %dms" (:timeout config)))
    (when dry-run? (main/info "  Mode:       DRY RUN"))
    (main/info "================================================================")
    (main/info)

    ;; 1. Discover mutation targets
    (progress verbose? "Discovering namespaces...")
    (let [targets (selector/discover-mutation-targets config)]
      (if (empty? targets)
        (do
          (main/warn "No mutation targets found. Check your namespace filters.")
          {:results []
           :duration-ns (- (System/nanoTime) start-time)
           :source-ns-count 0
           :test-ns-count 0
           :config config})

        (do
          (main/info (format "  Found %d source namespace(s) with matching tests:"
                             (count targets)))
          (doseq [t targets]
            (main/info (format "    %s → %s"
                               (:source-ns t)
                               (str/join ", " (:test-namespaces t)))))
          (main/info)

          ;; 2. Find mutation sites and optionally run
          (let [all-results (atom [])
                total-mutations (atom 0)
                resumed-count (atom 0)
                fresh-count (atom 0)
                print-lock (Object.)
                log! (fn [& args] (locking print-lock (apply main/info args)))
                ;; Compile the project and resolve the classpath ONCE; each
                ;; namespace then launches as an independent raw subprocess
                ;; (see runner/prepare-launch-context). This avoids Leiningen's
                ;; per-call prep, which serializes and races parallel workers.
                launch-ctx
                (when-not dry-run?
                  (when (> jobs 1)
                    (main/info "  Compiling project once for parallel workers ..."))
                  (let [ctx (runner/prepare-launch-context project (:private-tmp config))]
                    (when (> jobs 1)
                      (main/info (format "  Private /tmp per worker: %s"
                                         (case (:private-tmp ctx)
                                           :unshare "yes (unprivileged mount namespace)"
                                           :sudo "yes (sudo mount namespace)"
                                           "no — workers share host /tmp"))))
                    ctx))
                process-target
                (fn [{:keys [source-ns source-file test-namespaces] :as target}]
                  (let [sites (mutator/find-mutation-sites source-file operator-ids)
                        mutations (mutator/expand-mutations sites)
                        mutation-count (count mutations)]
                    (swap! total-mutations + mutation-count)
                    (log! (format "  Scanning %s ... %d site(s), %d mutation(s)"
                                  source-ns (count sites) mutation-count))

                    (when (and (seq mutations) (not dry-run?))
                      (let [sig (checkpoint/signature target config)
                            cached (when resume?
                                     (checkpoint/load-results checkpoint-dir source-ns sig))]
                        (if cached
                          ;; Resume: reuse checkpointed results, skip the JVM launch
                          (let [killed (count (filter #(#{:killed :timed-out} (:status %)) cached))
                                survived (count (filter #(= :survived (:status %)) cached))]
                            (log! (format "    ↺ %s resumed from checkpoint: %d killed, %d survived"
                                          source-ns killed survived))
                            (swap! resumed-count inc)
                            (swap! all-results into cached))
                          ;; Run fresh, then checkpoint the results
                          (let [ns-results (runner/run-mutations-for-namespace
                                             launch-ctx source-ns source-file
                                             test-namespaces mutations config)
                                ;; Tag results with source info
                                tagged (mapv #(assoc %
                                                     :source-ns source-ns
                                                     :source-file source-file)
                                             ns-results)
                                killed (count (filter #(#{:killed :timed-out} (:status %)) tagged))
                                survived (count (filter #(= :survived (:status %)) tagged))]
                            (log! (format "    → %s: %d killed, %d survived"
                                          source-ns killed survived))
                            (checkpoint/save-results! checkpoint-dir source-ns sig tagged)
                            (swap! fresh-count inc)
                            (swap! all-results into tagged)))))))]

            (when (> jobs 1)
              (main/info (format "  Running with %d parallel job(s)" jobs)))
            (parallel-doseq jobs process-target targets)

            (when (and (not dry-run?) (or (pos? @resumed-count) resume?))
              (main/info)
              (main/info (format "  Checkpoint: %d namespace(s) resumed, %d run fresh (dir: %s)"
                                 @resumed-count @fresh-count checkpoint-dir)))

            ;; 3. Generate reports
            (let [duration-ns (- (System/nanoTime) start-time)
                  test-nses (into #{} (mapcat :test-namespaces targets))
                  report-data {:results @all-results
                               :duration-ns duration-ns
                               :source-ns-count (count targets)
                               :test-ns-count (count test-nses)
                               :config config}]

              (when dry-run?
                (main/info)
                (main/info (format "  DRY RUN: %d total mutations would be generated"
                                   @total-mutations))
                (main/info))

              (when (and (seq @all-results) (not dry-run?))
                ;; Text report
                (when (some #{:text} (:output-format config))
                  (reporter/text-report report-data))

                ;; HTML report
                (when (some #{:html} (:output-format config))
                  (let [path (reporter/html-report report-data
                                                    (:output-dir config))]
                    (main/info (format "  HTML report: %s" path)))))

              report-data)))))))

