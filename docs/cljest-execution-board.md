# cljest — "True Gangster" Execution Board

Make cljest a production-grade mutation tester: fast enough to run repo-wide
routinely, safe enough that an interrupted run never corrupts a working tree,
and clear enough that survivors are immediately actionable.

## Why now

The first full-repo baseline (202 namespaces, ~14k mutations) proved the
**signal is trustworthy** — scores tracked intuition (I/O-heavy notification
code low, `db.*` stores high, money paths exposed). But producing it required
us to *bolt on* a resilience layer from the outside:

- a `.halt` patch to escape the non-daemon-thread JVM wedge,
- a `git checkout HEAD -- src/` contamination guard (in-place mutation risk),
- per-namespace checkpoint/resume (built mid-run),
- 16 Docker shards for parallelism (the tool has none natively),
- per-container `/tmp` + per-worktree source to prevent cross-talk.

Every one of those is a gap to close *inside* cljest. This board does that.

## Operating Rules

1. No ticket moves to `DONE` without linked evidence (PR, benchmark log, or a
   reproducible before/after on a fixture project).
2. Correctness gate: any perf/isolation change MUST reproduce the same
   kill/survive verdicts as the current engine on a frozen fixture namespace.
3. `P0` tickets are foundational — `P1`/`P2` build on them; respect the
   dependency column.
4. Each ticket has one accountable owner.

## Ticket Board

| ID | Pri | Status | Depends on | Work Item | Definition of Done |
|---|---|---|---|---|---|
| CLJEST-PERF-001 | P0 | DONE | — | **Coverage-guided test selection.** Today every mutant reruns *all* matched test namespaces (`runner.clj:74`). Capture per-test line/form coverage once, then for each mutant run only the tests that exercise the mutated site. | ✅ Mutants run only covering tests, with all-tests fallback on unknown coverage; verdicts identical to full-run on fixture + real namespaces; ≥3× on benchmark, 2.0× on a real 29-min namespace. Evidence in Progress Log 2026-05-21. |
| CLJEST-ISO-001 | P0 | DONE | — | **Out-of-tree mutation.** Stop `spit`-ing mutants into the working `src/`. Apply each mutant in-memory via `load-string` (re-evaluates the full mutated file text); the real source file is read-only input. | ✅ `kill -9` mid-run leaves source pristine (vs 0.1.0 which leaves it mutated); verdicts unchanged (fixture 16/6, real pagination 26/2); contamination guard no longer needed. Evidence in Progress Log 2026-05-21. |
| CLJEST-PERF-002 | P0 | DONE | ISO-001 | **Native parallel execution.** `--jobs N` worker pool launches each namespace as an independent raw `java` subprocess (classpath/prep resolved once, not per call). Removes the need for external Docker sharding for source isolation. | ✅ **6.54× at 8 jobs** (near-linear, 82% eff.) on independent workloads; verdicts unchanged; tree clean. Speedup is bounded by a project's own shared test state — see CLJEST-ISO-002. Evidence in Progress Log 2026-05-21. |
| CLJEST-ISO-002 | P1 | DONE | PERF-002 | **Per-worker private `/tmp`.** Each worker subprocess runs in its own mount namespace with a fresh dir bind-mounted over `/tmp` (so even hardcoded `/tmp/foo.db` literals are isolated), dropping back to the invoking user via `setpriv`. `--private-tmp auto\|off\|unshare\|sudo`. | ✅ Removes cross-worker `/tmp` contention: chengis jobs=8 went from serialized (workers pinned ~1) to a genuine 8→1 parallel burst; 289s→199s; verdicts correct, tree clean, ran as uid 1000. Residual long-tail (one slow ns) → CLJEST-PERF-004. Evidence in Progress Log 2026-05-22. |
| CLJEST-PERF-005 | P1 | DONE | PERF-002 | **LPT / adaptive scheduling.** Dispatch namespaces longest-first (was alphabetical, so the slowest landed in the tail). Parallel pre-pass computes mutation count per ns; cost key prefers recorded `:elapsed-ms` from a prior run, else mutation count. Records elapsed per ns for adaptive runs. | ✅ Full repo: **LPT@16 = 33m09s beat alphabetical@24 = 36m27s** (fewer workers, no idle tail — held full 16-width through the bulk). Verdicts reproduced (46.8%). Evidence in Progress Log 2026-05-22. |
| CLJEST-PERF-004 | P0 | DONE | PERF-002 | **Mutation-level parallelism within a namespace.** The schedulable unit is now a mutant-batch, not a namespace: large namespaces shard into batches (`--batch-size`, default 50) that all share one pool; a namespace checkpoints when its last batch lands. | ✅ Single namespace: identical verdicts, **4.05× sharding 8 batches**. Full repo: **33m09s → 21m05s** (concurrency held full ~17 min vs LPT@16's early decay), beats Docker baseline ~1.65×. Floor broken; remaining cost is redundant per-batch coverage → CLJEST-PERF-006. Evidence in Progress Log 2026-05-22. |
| CLJEST-PERF-006 | P0 | TODO | PERF-004 | **Shared coverage across batches.** Each mutant-batch re-runs its namespace's full suite to rebuild the coverage map (observed: `web.saml` 7 batches × ~390s, dominated by coverage recompute, not mutants). Compute coverage once per namespace (write var→test map to a file) and have batches read it. | A sharded namespace computes coverage once; batch time is dominated by mutants, not coverage; full sweep drops toward ~total-mutant-work/N. |
| CLJEST-PERF-003 | P1 | TODO | PERF-002 | **Warm worker JVMs.** Reuse pre-warmed project JVM(s) across namespaces instead of a cold `eval-in-project` per namespace; recycle on leak/wedge. | Per-namespace cold-start cost amortized; measured wall-clock reduction on the full sweep; wedge recovery still safe (halt + respawn). |
| CLJEST-ROB-001 | P1 | TODO | — | **Mutation-level streaming + resume.** Stream each result to disk as it completes (`runner.clj:127` writes once at the end); extend checkpoint to mutation granularity. | Kill mid-namespace, `--resume` re-runs only the unfinished mutants of that namespace, not the whole namespace. |
| CLJEST-INC-001 | P1 | TODO | — | **Git-diff-aware incremental mode.** `--since REF` mutates only sites on lines changed vs a ref. | PR-scoped run mutates only changed code; documented; verdicts match a full run restricted to those sites. |
| CLJEST-RPT-001 | P2 | TODO | — | **Survivor drill-down report.** HTML/text per surviving mutant: file, line, operator, original→mutated diff, and which tests ran; grouped by namespace/operator. | A reviewer can act on every survivor without rerunning; HTML diff view ships. |
| CLJEST-EQV-001 | P2 | TODO | — | **Equivalent-mutant handling.** Better trivial-equivalence heuristics + a `.cljest-ignore` suppression file for known won't-fix/equivalent mutants. | Suppressed mutants excluded from the denominator with an audit trail; documented format. |
| CLJEST-RPT-002 | P2 | TODO | — | **Machine-readable output + trend.** Emit JSON/EDN report; append score history per run. | CI can parse the score; a trend file updates each run; schema documented. |
| CLJEST-DX-001 | P2 | TODO | RPT-002, INC-001 | **CI integration.** Published GitHub Action with diff-scoped threshold gating and PR annotations on survivors. | Action gates a sample PR; survivors annotated inline; README usage section. |
| CLJEST-OPS-001 | P2 | TODO | — | **Operator expansion + granular config.** Per-operator enable/disable, custom presets, and additional Clojure-aware operators. | Operators configurable from `project.clj`; catalog documented; new operators have fixture coverage. |

## Phased Plan

**Phase 1 — Speed & Safety foundations (P0).** PERF-001 + ISO-001 + PERF-002.
After this phase a full-repo sweep runs in-process, in parallel, several times
faster, and can never dirty the working tree. This is the "gangster" floor:
cheap and safe enough to run on every PR.

**Phase 2 — Throughput & resilience (P1).** PERF-003 (warm JVMs), ROB-001
(mutation-level resume), INC-001 (incremental). Squeezes the remaining startup
cost and makes interrupted/CI runs cheap and recoverable.

**Phase 3 — Signal quality & DX (P2).** RPT-001 (survivor drill-down),
EQV-001 (equivalents), RPT-002 (JSON+trend), DX-001 (GH Action), OPS-001
(operators). Turns a fast engine into a tool teams actually adopt.

## Already shipped (context)

- **Checkpoint/resume** (namespace-level) — `src/cljest/checkpoint.clj`.
- **JVM wedge fix** — force-`.halt` after results flush — `src/cljest/runner.clj`.
- **Progress beacons** — per-mutation stderr lines for live observability.

## Progress Log

- 2026-05-21: Board created. Highest-leverage target identified as
  CLJEST-PERF-001 (coverage-guided selection) — current engine reruns the full
  matched test set per mutant.
- 2026-05-21: `CLJEST-PERF-001` DONE. Implementation: `src/cljest/coverage.clj`
  (static enclosing-var resolver), one-pass instrumented coverage capture +
  per-mutant test selection in `src/cljest/runner.clj`, `:coverage`/`--no-coverage`
  config in `src/cljest/config.clj`, unit tests in `test/cljest/coverage_test.clj`
  (149-test suite green). Design: attribute each source-var invocation to the
  running `clojure.test/*testing-vars*`; select only covering tests per mutant;
  fall back to the full set when coverage is unknown so verdicts can only get
  faster, never change. Validation (A=--no-coverage vs B=coverage, identical
  JVM/host):
  - benchmark fixture: 16 killed/6 survived both; 12.7s → 2.6s (**4.79×**).
  - `chengis.web.auth` (281 mutations): 112 killed/169 survived both;
    1750s → 872s (**2.01×**, ~15 min saved on one namespace).
  - `chengis.engine.iac` (302): 83/219 both; ~10s both (fast suite, no harm).
  - `chengis.db.pagination`: 26/2 both; 2.4s both (fast suite, no harm).
  Released under version 0.2.0-SNAPSHOT.
- 2026-05-21: `CLJEST-ISO-001` DONE. `src/cljest/runner.clj` no longer writes
  the working source file: each mutant is applied with `(load-string
  mutated-src)` (re-evaluates the full mutated file — ns form + defs — exactly
  like a reload, but in-memory), and the file-restore `finally` is gone. The
  real source file is now read-only input. Validation (fixture, killed at
  mutant ~5):
  - 0.2.0-SNAPSHOT: source md5 unchanged after `kill -9` mid-run (PASS).
  - 0.1.0 (spit-based) on the same kill: source LEFT MUTATED
    (`(> a b)` baked to `(< a b)`) — the exact contamination this fixes.
  - Verdicts unchanged: fixture 16 killed/6 survived; real
    `chengis.db.pagination` 26/2 (92.9%); working tree clean after a normal run.
  Consequence: the `git checkout HEAD -- src/` contamination guard is no longer
  needed in any launcher/workflow, and CLJEST-PERF-002 (parallel execution) is
  unblocked — concurrent mutants can no longer corrupt a shared checkout.
- 2026-05-22: `CLJEST-PERF-002` DONE. `--jobs N` runs namespaces on a fixed
  daemon thread pool; each namespace launches as an independent raw `java …
  clojure.main` subprocess (`runner/run-subprocess!`) using a classpath + JVM
  args resolved exactly ONCE (`runner/prepare-launch-context`, which preps the
  project a single time). This replaced per-namespace `eval-in-project`, whose
  per-call prep (javac/compile) both *raced* (`Wrong number of arguments to
  javac task`) and *serialized* parallel workers — measured as ~1 concurrent
  JVM even at `--jobs 8`. Per-slot `java.io.tmpdir` isolates scratch dirs.
  Validation:
  - Independent 8-namespace fixture: jobs=1 51.4s → jobs=8 7.9s = **6.54×**,
    peak concurrency saturated. Verdicts unchanged; tree clean.
  - Real chengis 8-namespace subset: verdicts match sequential
    (215–216 killed / 205 survived / 51.7%, ±1 timeout-tail), tree clean — but
    **no speedup** because chengis tests hardcode shared `/tmp/chengis-*.db`
    paths and contend on SQLite locks (`busy_timeout=5000`); more concurrency
    made it *slower*. This is a project test-isolation limit (the reason the
    original baseline used Docker private `/tmp`), tracked as CLJEST-ISO-002.
  Net: near-linear parallelism for isolation-clean suites; no harm otherwise.
- 2026-05-22: `CLJEST-ISO-002` DONE. Each worker subprocess now launches inside
  a private mount namespace with a fresh per-launch dir bind-mounted over /tmp,
  so even tests that hardcode `/tmp/foo.db` are isolated per worker; `setpriv`
  drops from root back to the invoking uid/gid so tests don't run as root.
  Mechanism auto-detected (`--private-tmp auto|off|unshare|sudo`): unprivileged
  user+mount namespace where allowed, else `sudo unshare` (Ubuntu 23.10+ blocks
  unprivileged userns via AppArmor). The form file and results file live under
  the per-launch dir (not /tmp) so their absolute paths resolve identically
  inside and outside the namespace. Validation on the chengis 8-namespace set
  (which hardcodes `/tmp/chengis-*.db`):
  - **without** private /tmp: workers pinned at ~1 concurrent (SQLite lock
    contention on shared /tmp), 289s.
  - **with** `--private-tmp sudo`: clean 8→1 concurrency decay (genuine parallel
    burst), 289s→199s, verdicts 217k/205s/51.7% (±1 timeout-tail), tree clean,
    scratch dirs auto-removed, tests ran as uid 1000.
  Standalone PoC confirmed two processes both writing `/tmp/app.db` land in
  separate private dirs with no leak to the host /tmp.
  Residual: the 199s is now bounded by ONE slow namespace running ~136s alone
  after the other 7 finished — workload imbalance, not /tmp. Addressed by
  mutation-level parallelism (CLJEST-PERF-004), a separate axis.
- 2026-05-22: `CLJEST-PERF-005` DONE. Namespaces are now dispatched
  longest-first (was alphabetical). A parallel pre-pass (`pmap`) computes each
  namespace's mutation count and caches the sites so the worker doesn't
  re-parse; the cost key prefers a recorded `:elapsed-ms` (now persisted in the
  checkpoint per namespace), falling back to mutation count on a fresh run.
  Full-repo bake-off (202 ns, `--private-tmp sudo`, coverage):
  - **alphabetical @ jobs=24: 36m27s** (2188s) — the heavy `web.*`/`cli`/`seed`
    namespaces sort last, so 24 workers sat half-idle draining a ~27-min tail.
  - **LPT @ jobs=16: 33m09s** (1991s) — heavies dispatched at t=0, full
    16-width held through the bulk, no idle tail. Fewer workers, faster wall.
  Both reproduced the baseline score (46.7–46.8% vs 47.6%); trees clean.
  Finding: the makespan is now floored by a SINGLE slow namespace
  (`web.saml` ~33 min, `cli.commands` ~32.5, `web.mfa` ~28, `web.auth` ~23 —
  inflated ~1.6× by CPU oversubscription among the heavy multi-threaded test
  JVMs). LPT removes the idle tail but cannot split one job; CLJEST-PERF-004
  (now P0) is required to break the floor. Adaptive timing now recorded, so the
  next run ranks these by real cost (mutation count alone under-ranked
  `web.mfa`, only 151 mutants).
- 2026-05-22: `CLJEST-PERF-004` DONE. The schedulable unit is now a
  mutant-batch, not a whole namespace. A namespace with more than `--batch-size`
  (default 50) mutants is split into `min(jobs, ceil(n/batch-size))` batches;
  all units (small-namespace wholes + large-namespace batches) go into one pool
  longest-first, and a namespace is checkpointed once its last batch lands
  (`ns-acc` countdown). `run-mutations-for-namespace` already runs an arbitrary
  mutant subset, so batches reuse it unchanged. Validation:
  - Single namespace, `--jobs 8`: 1 unit 18.6s vs 8 batches 4.6s = **4.05×**,
    identical verdicts (11 killed/5 survived).
  - Full repo (`--jobs 24 --private-tmp sudo`, fresh): **LPT@16 33m09s →
    21m05s**; concurrency held full (~25) for ~17 min then a ~2.5-min tail
    (vs LPT@16's long decay) — the single-namespace floor is gone. Beats the
    original Docker baseline (~35 min) by ~1.65×. Score 46.5% (≈ baseline);
    tree clean; 0 errors; 395 work units.
  Finding: per-batch wall is now dominated by REDUNDANT COVERAGE — each batch
  re-runs its namespace's full suite to rebuild the var→test map (`web.saml`
  7 batches × ~390s, mostly coverage). Compute coverage once per namespace and
  share it across batches → CLJEST-PERF-006 (next ~1.4×).
