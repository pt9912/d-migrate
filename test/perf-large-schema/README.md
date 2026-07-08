# test:perf-large-schema

Large-schema scale tests for the `SchemaMigrateRenderPipeline`.

Plan-Doc:
[quality-coverage-expansion-plan.md](../../docs/planning/done-archive/quality-coverage-expansion-plan.md)
§5.4 (Sub-Slice D + D-N10k).

## Why this module exists

The Phase A `SchemaMigrateRenderPipelinePerfSpec`
(`hexagon:application`) pins the 100-op orchestration baseline.
Phase D scales that baseline to mixed schemas with thousands of
objects and asserts that the render pipeline:

1. Finishes within a runaway wall-clock budget per scale, **and**
2. Does not balloon the JVM heap-pool peak past the per-scale
   advisory limit.

Together these guard against accidental quadratic blow-ups in the
planner or renderer that the small 100-op hotpath would miss.

## Pinned scales

| Scale (N) | objects | smoke (ms) | baseline (ms) | heap budget (MB) | status |
| --------- | ------- | ---------- | ------------- | ---------------- | ------ |
| 100       | ~500    | 30 000     | 2 000         | 256              | pinned (Sub-Slice D) |
| 1 000     | ~5 000  | 120 000    | 30 000        | 1 024            | pinned (Sub-Slice D) |
| 10 000    | ~50 000 | TBD        | TBD           | 2 048            | deferred (Sub-Slice D-N10k) |

Each scale produces **5 × N** objects: N tables, N sequences, N
views, N triggers, plus one shared audit function the triggers
reference. The PostgreSQL renderer is the cross-section under
test; MySQL/SQLite at this scale follow in later tranches if the
need surfaces.

## Heap-budget strategy

`HeapBudget.start(maxMb)` does three things:

1. Two `System.gc()` hints with a short sleep between, giving the
   GC a chance to drain pending finalisers from the previous
   scale run.
2. `resetPeakUsage()` on every `MemoryPoolMXBean` whose `type`
   is `HEAP`. Non-heap pools (metaspace, code cache) are excluded
   — they reflect JVM lifecycle costs, not workload pressure.
3. Returns a fresh budget handle. `peakUsedMb()` reports the
   maximum observed `peakUsage.used` across all heap pools in
   MiB.

This is the canonical strategy from the plan-doc §5.4 ("Erste
Wahl"). Alternatives (JFR recording, async-profiler) are valid
follow-ups but require a per-scale-Sub-Slice commit note before
the implementation is changed.

The KDoc earlier described the heap-pool value as `MAXIMUM
observed peakUsage.used`; F-Fixes (2026-05-31) re-aligned the
docstring with the actual `.sumOf { ... }` implementation — the
peak is the **sum** of every heap-typed pool's `peakUsage.used`,
which approximates the total heap working-set peak. A per-pool
max would under-state load when Eden and Old peak at overlapping
times.

F5-Followup (2026-05-31) added `-XX:+HeapDumpOnOutOfMemoryError`
plus `-XX:HeapDumpPath=build/test-heap-dumps/` as module-local
test-`jvmArgs`. On OOM the operator gets a forensic `hprof` under
`build/test-heap-dumps/`; the flag is intentionally module-local
so unit-spec OOMs in unrelated modules do not fill `build/` with
multi-GB heap dumps.

## Running

The scale tests carry the `perf` Kotest tag and the additional
`large-schema` tag for IDE-side filtering. They run only via the
shared opt-in `perf` entry point:

```
make docker-perf MODULES=":test:perf-large-schema"
```

Without `-Dkotest.tags=perf` (the default `!perf` filter), the
test task discovers them but skips at execution time.

When `make docker-perf PERF_GATE=true` is invoked, the
`d-migrate.perf.gate=true` system property forwarded to the
forked test JVM turns `baselineMs` into a hard assertion via
[PerfReport.write](../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/perf/PerfReport.kt).
On shared-CI without the gate flag, baselines stay diagnostic.

## Adding a new scale

1. Append a new `Scale(...)` entry to `LargeSchemaScaleSpec.SCALES`.
2. Pick budgets generously — the spec is opt-in and PR-CI flake
   here erodes trust in the existing scales.
3. If the new scale is N=10000 or larger, place it in a **separate**
   spec class with the `large-schema-10k` Kotest tag so it can be
   nightly-gated independently of the standard opt-in budget. The
   plan-doc names this Sub-Slice D-N10k.
