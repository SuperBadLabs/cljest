# cljest

Mutation testing for Clojure. A Leiningen plugin that introduces small bugs into your source code and checks whether your test suite catches them.

## Why Mutation Testing?

Code coverage tells you "this line was executed." Mutation testing tells you "your tests would **catch a bug** on this line."

A test can execute a function and check the return type without noticing that `+` was swapped to `-`. Mutation testing catches these blind spots by computing a **mutation score**: the percentage of introduced bugs ("mutants") that your tests detect ("kill").

## Installation

Add cljest to your project's `:plugins`:

```clojure
;; project.clj
:plugins [[org.clojars.sremani/cljest "0.1.0"]]
```

## Quick Start

```bash
# Run mutation testing on all source namespaces
lein cljest

# Target specific namespaces
lein cljest --namespaces "myapp\.core"

# Quick feedback with fewer operators
lein cljest --operators fast

# See mutation count without running tests
lein cljest --dry-run

# Generate an HTML report
lein cljest --format html
```

## How It Works

1. **Discover** source and test namespaces from your project
2. **Scan** source code for mutation sites using rewrite-clj
3. **Capture coverage** (once): run the matched suite with the source namespace
   instrumented to learn which tests exercise which function
4. **For each mutation**: apply the mutant in-memory (`load-string`) — the
   working source file is never written — and run only the tests that cover the
   mutated function (see *Coverage-Guided Selection* below)
5. **Record** whether tests caught the mutation (killed) or missed it (survived)
6. **Report** mutation score with details on surviving mutations

Mutations are applied in-memory and the working source file is treated as
read-only input, so an interrupted or `kill -9`'d run can never leave a mutation
baked into your checkout — no cleanup or restore step required.

## Coverage-Guided Selection

Mutation testing's dominant cost is re-running tests once per mutant — and most
of those runs are wasted, since a mutant inside `foo` can only be killed by a
test that exercises `foo`. cljest captures per-function coverage once (attributing
each source-var call to the running test), then runs only the covering tests for
each mutant.

A mutant whose enclosing function has *no* recorded coverage (uncovered code, a
non-fn `def`, a multimethod, or an unresolved position) falls back to running the
**full** test set — so selection only ever *shrinks* the run when there is
positive coverage evidence, and never changes a kill/survive verdict.

This is on by default. Disable it with `--no-coverage` (or `:coverage false`) to
run every matched test for every mutant. Typical speedups range from ~1× on
fast/tiny suites (no test time to reclaim) to several× on slow, broad suites.

### Coverage Cache

The coverage map (which tests cover which source vars) is independent of which
mutations you apply, so cljest caches it to disk and reuses it across runs —
skipping the instrumented suite run a namespace would otherwise repeat every
time. A cached map is reused only when the **whole project's source + test code
is byte-identical** to when it was captured: coverage depends on the behavior of
all code reachable from the tests (an integration test can reach a var through
several namespaces), so any code change conservatively invalidates the cache and
coverage is recomputed. This keeps the cache verdict-safe — a stale or corrupt
entry degrades to the full-suite fallback and never changes a kill/survive
result.

Because the key is the *code* state and not the run parameters, the cache
survives `--operators`/`--threshold`/`--timeout` changes and `--resume`, so
re-running the same checkout (CI retries, switching operator presets, tuning the
threshold) pays the coverage cost only once. It does **not** speed up the
edit-one-file-and-re-run loop — any edit invalidates the project signature;
sound per-namespace invalidation needs the dependency graph (tracked separately).

On by default. Disable with `--no-coverage-cache` (or `:coverage-cache false`).
Entries live under `<output-dir>/coverage-cache`.

## Configuration

Add a `:cljest` key to your `project.clj`:

```clojure
:cljest {:namespaces [#"myapp\.core\..*"]  ; regex include patterns
         :exclude-namespaces [#".*\.web\..*"]
         :operators :standard               ; :fast | :standard | :comprehensive
         :threshold 80                      ; minimum score (%) — exit 1 if below
         :timeout 30000                     ; per-mutation timeout (ms)
         :output-dir "target/cljest"
         :output-format [:text :html]
         :skip-equivalent true
         :coverage true                     ; coverage-guided test selection
         :coverage-cache true}              ; reuse coverage maps across runs
```

## CLI Options

```
  --namespaces REGEX          Regex to filter source namespaces
  --exclude-namespaces REGEX  Regex to exclude source namespaces
  --threshold N               Minimum mutation score (%) [default: 80]
  --operators PRESET          fast | standard | comprehensive [default: standard]
  --format FMT                text | html | both [default: text]
  --timeout MS                Per-mutation timeout (ms) [default: 30000]
  --output-dir DIR            Report output directory [default: target/cljest]
  --dry-run                   Show mutation count without running
  --no-coverage               Disable coverage-guided test selection
  --no-coverage-cache         Disable the cross-run coverage cache
  --jobs N                    Mutate N namespaces in parallel [default: 1]
  --batch-size N              Shard a namespace into mutant batches above N [default: 50]
  --private-tmp MODE          Per-worker private /tmp: auto|off|unshare|sudo [default: auto]
  --verbose                   Verbose output
  --help                      Show help
```

## Parallel Execution

`--jobs N` mutates N namespaces concurrently. Each namespace is launched as an
independent `java` subprocess using a classpath that's resolved once up front,
so there's no shared in-process state between workers — mutants are applied
in-memory (so the source tree is read-only and safe to share) and each worker
gets its own `java.io.tmpdir`. On independent workloads this scales near-linearly
(~6.5× at `--jobs 8`).

The schedulable unit is a *mutant-batch*, not a whole namespace: a namespace
with more than `--batch-size` mutants (default 50) is sharded into batches that
all draw from the same pool, so a single slow namespace can use many workers
instead of bounding the wall clock. Work is dispatched longest-first, preferring
a recorded per-namespace wall time from a prior run (persisted in the checkpoint)
and falling back to mutation count.

If your **tests** hardcode an absolute scratch path like `/tmp/app.db` rather
than deriving it from `java.io.tmpdir`, concurrent workers would otherwise
contend on that shared file. `--private-tmp` gives each worker its own `/tmp`
so even hardcoded `/tmp/...` paths are isolated:

- `auto` (default) — use an unprivileged mount namespace if the host allows it
  (unprivileged user namespaces), otherwise run without private `/tmp`. Never
  escalates privileges silently.
- `sudo` — create the per-worker mount namespace via `sudo`, then drop back to
  the invoking user with `setpriv` so tests don't run as root. Needs passwordless
  `sudo` for `unshare`. Use this on hosts where unprivileged user namespaces are
  disabled (e.g. Ubuntu 23.10+ with the AppArmor restriction).
- `unshare` — force the unprivileged mount-namespace path.
- `off` — share the host `/tmp`.

Each worker gets a fresh private `/tmp` per namespace, cleaned up afterward.
Note that `--private-tmp` removes cross-worker `/tmp` contention but cannot fix
imbalance between namespaces — a single slow namespace still bounds the wall
clock (mutation-level parallelism is tracked separately).

## Mutation Operators

cljest includes 56 mutation operators across 8 categories:

| Category | Count | Examples |
|----------|-------|---------|
| Arithmetic | 8 | `+` to `-`, `*` to `/`, `inc` to `dec` |
| Comparison | 12 | `<` to `>`, `=` to `not=`, boundary mutations |
| Logical | 6 | `and` to `or`, `true` to `false`, negate `if` |
| Collections | 8 | `first` to `last`, `filter` to `remove` |
| Nil/Control | 6 | `nil?` to `some?`, `when` to `when-not` |
| Constants | 6 | `0` to `1`, `""` to `"mutant"` |
| Threading | 4 | `->` to `->>`, `some->` to `some->>` |
| Clojure-Specific | 6 | `defn-` to `defn`, binding swaps |

### Presets

- **`:fast`** (15 operators) — arithmetic + comparison + logical core. Use for quick CI feedback.
- **`:standard`** (56 operators) — all operators. The default.
- **`:comprehensive`** (56 operators) — same as standard in v0.1.

## Report Example

```
================================================================
              CLJEST MUTATION TESTING REPORT
================================================================
  Source namespaces: 3        Mutations generated: 87
  Test namespaces:  5         Mutations killed:   74 (85.1%)
  Duration:         12.3s     Mutations survived: 13 (14.9%)
  Mutation score:   85.1%
================================================================

  Namespace                                Score    Killed/Total
  myapp.core                               91.3%   21/23
  myapp.util                               82.4%   14/17
  myapp.parser                             83.0%   39/47
```

## Architecture

cljest uses a split-JVM design:

- **Leiningen JVM**: source scanning, mutation generation (via rewrite-clj), reporting
- **Project JVM** (via `eval-in-project`): namespace reloading + test execution

One project JVM is launched per source namespace. Inside it, all mutations for that namespace run in a loop with a guaranteed `finally` block that restores the original source.

## Dependencies

cljest has minimal dependencies:

- `rewrite-clj` — source code manipulation with formatting preservation
- `tools.namespace` — namespace discovery
- `tools.cli` — CLI argument parsing

No exotic dependencies. No ClojureStorm. No custom compiler forks.

## Comparison

| Feature | cljest | Heretic |
|---------|--------|---------|
| Build tool | Leiningen | deps.edn |
| Dependencies | 3 standard | 6+ (incl. ClojureStorm) |
| Status | Stable | Experimental |
| License | Apache 2.0 | EPL 2.0 |
| Operators | 56 | 80+ |
| Test selection | Per-namespace | Per-expression |
| Setup | `lein cljest` | Manual ClojureStorm config |

## CI Integration

cljest exits with code 1 when the mutation score falls below the threshold:

```yaml
# GitHub Actions
- name: Mutation Testing
  run: lein cljest --threshold 80 --operators fast
```

## License

Copyright 2026 cljest contributors. Apache License 2.0.
