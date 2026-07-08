# hexagon:profiling

Domain model, ports, and rules for d-migrate's data profiling (0.7.5+),
plus the shared `PerfMeasure` / `PerfReport` micro-benchmark library
introduced in 0.9.7 Quality-/Coverage-Expansion Sub-Slice A.

## PerfSpec convention

Plan-Doc: [quality-coverage-expansion-plan.md](../../docs/planning/done-archive/quality-coverage-expansion-plan.md)
§5.1, §7 (Phase A).

`PerfMeasure` / `PerfReport` (under `dev.dmigrate.profiling.perf`) are
the contract every opt-in performance spec in the repo must use. The
Bestands-Specs in `adapters:driven:formats` and `adapters:driven:streaming`
were migrated to this contract during A-Vervollständigung; net-new
PerfSpecs go straight to it.

**Hotpaths in use** (per Phase A + A-Vervollständigung + F3-Followup):

| Hotpath slug                    | Spec                                       | Modul                  |
| ------------------------------- | ------------------------------------------ | ---------------------- |
| `schema-migrate-render-pipeline` | `SchemaMigrateRenderPipelinePerfSpec`       | `hexagon:application`  |
| `diff-planner`                  | `DiffPlannerPerfSpec`                       | `hexagon:core`         |
| `rollback-artefact-round-trip`  | `RollbackArtefactRoundTripPerfSpec`         | `hexagon:application`  |
| `large-json-pull-spike`         | `LargeJsonPullSpikePerfTest` (migriert)     | `adapters/driven/formats` |
| `format-json-chunk-reader-100mb` | `JsonChunkReaderPerfTest` (F3-Followup-Migration 2026-05-31) | `adapters/driven/formats` |
| `format-yaml-chunk-reader-100k` | `YamlChunkReaderPerfTest` (F3-Followup-Migration 2026-05-31) | `adapters/driven/formats` |
| `streaming-importer-reorder`    | `StreamingImporterReorderPerfTest` (migriert) | `adapters/driven/streaming` |
| `large-schema-render-n100`      | `LargeSchemaScaleSpec` (Phase D, N=100)     | `test/perf-large-schema` |
| `large-schema-render-n1000`     | `LargeSchemaScaleSpec` (Phase D, N=1000)    | `test/perf-large-schema` |

Each writes its trend record to `<module>/build/reports/perf/<slug>.json`.

### Tagging

Every spec lives under a `perf/` sub-package and tags its `FunSpec`
with `NamedTag("perf")`:

```kotlin
private val PerfTag = NamedTag("perf")

class MyHotpathPerfSpec : FunSpec({
    tags(PerfTag)
    test("Hotpath under SMOKE budget") { /* … */ }
})
```

The root `build.gradle.kts` defaults the Kotest filter to `!perf` so
PR-Sweep runs skip perf specs entirely. An explicit
`-Dkotest.tags=perf` selects only tagged specs and is forwarded into
the forked test JVM (see `build.gradle.kts:89-102`).

### Two budgets per hotpath

Pin two constants per hotpath:

- `*_SMOKE_MAX_MS` — runaway-Smoke guard. Assert against
  `sample.medianMs` **and** `sample.p95Ms` separately. Calibrated
  generously so shared-container CI jitter does not flap.
- `*_BASELINE_MS` — nightly / `perf-stable-runner` expectation.
  Written into the JSON report but **not** asserted on shared CI.
  `PERF_GATE=true` on `make docker-perf` flips it to a hard gate.

### Default sampling

`PerfMeasure.run` defaults to 5 warmup + 20 measured iterations.
Bump only with a commit note explaining why and quoting old/new
median + p95.

### Report shape

Each spec writes one JSON record per hotpath to
`<module>/build/reports/perf/<hotpath>.json` via `PerfReport.write`:

```json
{
  "hotpath": "schema-migrate-render-pipeline",
  "timestamp": "2026-05-30T12:34:56Z",
  "iterations": 20,
  "medianMs": 18.3,
  "p95Ms": 21.7,
  "p99Ms": 24.0,
  "minMs": 16.1,
  "maxMs": 25.4,
  "smokeMaxMs": 5000.0,
  "baselineMs": 250.0
}
```

The trend dashboard (planned for A-Vervollständigung) aggregates
these files across nightly runs to plot baseline drift.

### Running locally

```
# Whole repo
make docker-perf

# Single module
make docker-perf MODULES=":hexagon:application"

# Treat baseline budget as a hard gate (perf-stable-runner only)
make docker-perf PERF_GATE=true
```

`make docker-perf` is intentionally outside `make ci` and
`make gates` — it runs nightly or on demand, never on PR.

### Adding a new PerfSpec

1. Create the spec under `<module>/src/test/kotlin/.../perf/<Name>PerfSpec.kt`.
2. Tag the spec with `NamedTag("perf")`.
3. Wrap the workload in `PerfMeasure.run(warmup = 5, iterations = 20) { … }`.
4. Pin `*_SMOKE_MAX_MS` and `*_BASELINE_MS` as `private const val`
   on the spec's `companion object`.
5. Call `PerfReport.write(hotpath = "<stable-slug>", sample, smokeMaxMs, baselineMs)`.
6. Verify the Tag-filter gegenlauf locally:
   `./gradlew :<module>:test -Dkotest.tags=perf` runs only the spec,
   `./gradlew :<module>:test -Dkotest.tags=!perf` runs everything except it.

`<stable-slug>` must match the `kebab-case` form accepted by
`PerfReport.write` (regex `[a-z0-9][a-z0-9-]*[a-z0-9]`) so trend
dashboards can correlate hotpaths across runs.
