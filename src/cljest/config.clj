(ns cljest.config
  "Configuration resolution for cljest mutation testing.
   Merges defaults ← project :cljest ← CLI args."
  (:require [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def defaults
  "Default configuration values."
  {:source-paths nil          ; nil = use project's :source-paths
   :test-paths nil            ; nil = use project's :test-paths
   :namespaces nil            ; nil = all source namespaces
   :exclude-namespaces nil    ; nil = no exclusions
   :operators :standard       ; :fast | :standard | :comprehensive
   :threshold 80              ; minimum mutation score (%)
   :timeout 30000             ; per-mutation test timeout (ms)
   :output-dir "target/cljest"
   :output-format [:text]
   :skip-equivalent true      ; filter trivially equivalent mutations
   :since nil                 ; git ref: mutate only sites changed vs ref (INC-001)
   :coverage true             ; coverage-guided test selection (only run covering tests)
   :jobs 1                    ; number of namespaces to mutate in parallel
   :batch-size 50             ; shard a namespace into mutant batches above this size
   :private-tmp :auto         ; per-worker private /tmp: :auto | :off | :unshare | :sudo
   :dry-run false
   :verbose false
   :resume false              ; reuse valid checkpoint entries, skip re-running
   :checkpoint-dir nil        ; nil = "<output-dir>/checkpoint"
   :clear-checkpoint false})  ; wipe checkpoint before running

;; ---------------------------------------------------------------------------
;; CLI options
;; ---------------------------------------------------------------------------

(def cli-options
  "Command-line option definitions."
  [[nil "--namespaces REGEX" "Regex to filter source namespaces"]
   [nil "--exclude-namespaces REGEX" "Regex to exclude source namespaces"]
   [nil "--since REF" "Incremental: mutate only sites on lines changed vs this git ref"]
   [nil "--threshold N" "Minimum mutation score (%)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 % 100) "Must be 0-100"]]
   [nil "--operators PRESET" "Operator preset: fast, standard, comprehensive"
    :parse-fn keyword
    :validate [#{:fast :standard :comprehensive} "Must be fast, standard, or comprehensive"]]
   [nil "--format FMT" "Report format: text, html, both"
    :parse-fn keyword]
   [nil "--timeout MS" "Per-mutation timeout (ms)"
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]
   [nil "--jobs N" "Number of namespaces to mutate in parallel (default: 1)"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be positive"]]
   [nil "--batch-size N" "Shard a namespace into mutant batches above this size (default: 50)"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be positive"]]
   [nil "--private-tmp MODE" "Per-worker private /tmp: auto, off, unshare, sudo (default: auto)"
    :parse-fn keyword
    :validate [#{:auto :off :unshare :sudo} "Must be auto, off, unshare, or sudo"]]
   [nil "--output-dir DIR" "Report output directory"]
   [nil "--dry-run" "Show mutation count without running"]
   [nil "--no-coverage" "Disable coverage-guided test selection (run all matched tests per mutant)"
    :id :coverage :update-fn (constantly false)]
   [nil "--resume" "Resume from checkpoint: skip namespaces already completed with unchanged source/tests"]
   [nil "--checkpoint-dir DIR" "Directory for per-namespace checkpoint files (default: <output-dir>/checkpoint)"]
   [nil "--clear-checkpoint" "Delete existing checkpoint entries before running"]
   [nil "--verbose" "Verbose output"]
   ["-h" "--help" "Show help"]])

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(defn- parse-cli-args
  "Parse command-line arguments into an options map."
  [args]
  (let [{:keys [options errors]} (parse-opts args cli-options)]
    (when (seq errors)
      (throw (ex-info (str "CLI errors: " (str/join "; " errors))
                      {:errors errors})))
    options))

(defn- normalize-format
  "Normalize :format key to :output-format vector."
  [config]
  (if-let [fmt (:format config)]
    (-> config
        (dissoc :format)
        (assoc :output-format (case fmt
                                :both [:text :html]
                                :text [:text]
                                :html [:html]
                                [:text])))
    config))

(defn- compile-regex-patterns
  "Compile namespace regex string patterns into Pattern objects."
  [config]
  (cond-> config
    (string? (:namespaces config))
    (update :namespaces #(vector (re-pattern %)))

    (string? (:exclude-namespaces config))
    (update :exclude-namespaces #(vector (re-pattern %)))))

(defn resolve-config
  "Merge defaults ← project :cljest ← CLI args.
   Returns a fully resolved config map."
  [project cli-args]
  (let [project-config (get project :cljest {})
        cli-config (parse-cli-args cli-args)
        ;; Merge layers: defaults <- project <- CLI
        merged (merge defaults project-config cli-config)
        ;; Resolve paths from project if not overridden
        merged (cond-> merged
                 (nil? (:source-paths merged))
                 (assoc :source-paths (or (:source-paths project) ["src"]))

                 (nil? (:test-paths merged))
                 (assoc :test-paths (or (:test-paths project) ["test"]))

                 (nil? (:checkpoint-dir merged))
                 (assoc :checkpoint-dir (str (:output-dir merged) "/checkpoint")))]
    (-> merged
        normalize-format
        compile-regex-patterns)))

(defn help-text
  "Generate help text for CLI output."
  []
  (let [{:keys [summary]} (parse-opts [] cli-options)]
    (str "cljest — mutation testing for Clojure\n\n"
         "Usage: lein cljest [options]\n\n"
         "Options:\n" summary)))
