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
| CLJEST-PERF-002 | P0 | TODO | ISO-001 | **Native parallel execution.** Add a `--jobs N` worker pool that runs namespaces (and/or mutation batches) concurrently, each with isolated tmp/DB/source. Removes the need for external Docker sharding. | Repo-wide sweep parallelizes in-process to core count; near-linear speedup to ~16 jobs; no shared-state corruption (DB/tmp). |
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
