(ns cljest.core
  "Orchestrator — ties selector, mutator, runner, and reporter together
   into the main mutation testing pipeline."
  (:require [cljest.checkpoint :as checkpoint]
            [cljest.config :as config]
            [cljest.incremental :as incremental]
            [cljest.mutator :as mutator]
            [cljest.operators :as ops]
            [cljest.reporter :as reporter]
            [cljest.runner :as runner]
            [cljest.selector :as selector]
            [clojure.set :as set]
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

(defn- make-work-units
  "Split a target (with :mutations, :sig and :cost attached) into work units.
   Small namespaces become a single unit; large ones are sharded into mutant
   batches (CLJEST-PERF-004) so one slow namespace can use many workers instead
   of bounding the makespan. Every unit carries the full-namespace :sig and
   :n-batches so results can be aggregated and checkpointed per namespace."
  [{:keys [mutations cost] :as target} jobs batch-size]
  (let [n (count mutations)
        cost (or cost (* n 200))]
    (if (or (<= jobs 1) (<= n batch-size))
      [(assoc target :n-batches 1 :unit-cost cost)]
      (let [n-batches (min jobs (long (Math/ceil (/ n (double batch-size)))))
            per (long (Math/ceil (/ n (double n-batches))))
            parts (vec (partition-all per mutations))
            nb (count parts)]
        (map-indexed (fn [_ part]
                       (assoc target
                              :mutations (vec part)
                              :n-batches nb
                              :unit-cost (* cost (/ (double (count part)) n))))
                     parts)))))

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
        ;; Equivalence hygiene (EQV-001): drop operators the project has judged
        ;; to produce only equivalent mutants for its code (e.g. defn-→defn,
        ;; which is behavior-preserving in Clojure). Auditable: the excluded set
        ;; is echoed in the banner below.
        excluded-ops (set (:exclude-operators config))
        operator-ids (set/difference (ops/resolve-preset (:operators config))
                                     excluded-ops)
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
    (main/info (format "  Operators:  %s (%d)%s"
                       (name (:operators config)) (count operator-ids)
                       (if (seq excluded-ops)
                         (format " — excluding %d equivalent: %s"
                                 (count excluded-ops) (str/join ", " (sort (map name excluded-ops))))
                         "")))
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
                ;; Per-namespace batch accumulator: source-ns -> {:remaining n
                ;; :results [...] :elapsed ms}. A namespace is checkpointed once
                ;; its last mutant-batch completes.
                ns-acc (atom {})
                process-unit
                (fn [{:keys [source-ns source-file test-namespaces mutations sig n-batches]}]
                  (let [t0 (System/nanoTime)
                        ns-results (runner/run-mutations-for-namespace
                                     launch-ctx source-ns source-file
                                     test-namespaces mutations config)
                        elapsed-ms (quot (- (System/nanoTime) t0) 1000000)
                        tagged (mapv #(assoc % :source-ns source-ns :source-file source-file)
                                     ns-results)]
                    (swap! all-results into tagged)
                    (let [st (get (swap! ns-acc update source-ns
                                         (fn [s] (-> (or s {:remaining n-batches :results [] :elapsed 0})
                                                     (update :results into tagged)
                                                     (update :elapsed + elapsed-ms)
                                                     (update :remaining dec))))
                                  source-ns)]
                      (when (zero? (:remaining st))
                        (let [res (:results st)
                              killed (count (filter #(#{:killed :timed-out} (:status %)) res))
                              survived (count (filter #(= :survived (:status %)) res))]
                          (log! (format "    → %s: %d killed, %d survived (%.1fs%s)"
                                        source-ns killed survived (/ (:elapsed st) 1000.0)
                                        (if (> n-batches 1) (format ", %d batches" n-batches) "")))
                          (checkpoint/save-results! checkpoint-dir source-ns sig res (:elapsed st))
                          (swap! fresh-count inc))))))]

            ;; LPT + mutation-level sharding: compute each namespace's mutation
            ;; sites once (parallel parse) with its signature and cost, split
            ;; large namespaces into mutant batches, then dispatch all units
            ;; (small-namespace wholes + large-namespace batches) longest-first
            ;; through one pool — so a single slow namespace can't bound the
            ;; makespan.
            (let [batch-size (:batch-size config 50)
                  ;; Incremental scoping (INC-001): when --since is set, compute
                  ;; the changed-line map ONCE and keep only mutations on changed
                  ;; lines. nil means "not a git tree / bad ref" — abort loudly
                  ;; rather than silently mutate everything (or nothing).
                  since (:since config)
                  changed (when since (incremental/changed-lines since))
                  _ (when (and since (nil? changed))
                      (main/abort (format "  --since %s: could not diff against ref (not a git work tree, or unknown ref)" since)))
                  targets+ (->> targets
                                (pmap (fn [t]
                                        (let [sites (mutator/find-mutation-sites
                                                      (:source-file t) operator-ids)
                                              all-muts (vec (mutator/expand-mutations sites))
                                              muts (if since
                                                     (incremental/filter-mutations
                                                       changed (:source-file t) all-muts)
                                                     all-muts)]
                                          (assoc t
                                                 :sites-count (count sites)
                                                 :mutations muts
                                                 :sig (checkpoint/signature t config)
                                                 :cost (or (checkpoint/load-elapsed
                                                             checkpoint-dir (:source-ns t))
                                                           (* (count muts) 200))))))
                                vec)
                  ;; In --since mode most namespaces have zero changed sites;
                  ;; only surface the ones that actually contribute mutations.
                  scanned (if since (filter #(seq (:mutations %)) targets+) targets+)]
              (when since
                (main/info (format "  Incremental (--since %s): %d changed mutation site(s) across %d namespace(s)"
                                   since
                                   (reduce + (map (comp count :mutations) scanned))
                                   (count scanned))))
              (doseq [{:keys [source-ns sites-count mutations]} scanned]
                (swap! total-mutations + (count mutations))
                (log! (format "  Scanning %s ... %d site(s), %d mutation(s)"
                              source-ns sites-count (count mutations))))
              (when-not dry-run?
                (let [cached (if resume?
                               (into {} (keep (fn [{:keys [source-ns sig]}]
                                                (when-let [c (checkpoint/load-results
                                                               checkpoint-dir source-ns sig)]
                                                  [source-ns c]))
                                              targets+))
                               {})
                      units (->> targets+
                                 (remove #(contains? cached (:source-ns %)))
                                 (filter #(seq (:mutations %)))
                                 (mapcat #(make-work-units % jobs batch-size))
                                 (sort-by :unit-cost >)
                                 vec)]
                  (doseq [[source-ns c] cached]
                    (let [killed (count (filter #(#{:killed :timed-out} (:status %)) c))
                          survived (count (filter #(= :survived (:status %)) c))]
                      (log! (format "    ↺ %s resumed from checkpoint: %d killed, %d survived"
                                    source-ns killed survived))
                      (swap! resumed-count inc)
                      (swap! all-results into c)))
                  (when (> jobs 1)
                    (main/info (format "  Running %d work unit(s) across %d job(s), longest-first scheduling"
                                       (count units) jobs)))
                  (parallel-doseq jobs process-unit units))))

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

