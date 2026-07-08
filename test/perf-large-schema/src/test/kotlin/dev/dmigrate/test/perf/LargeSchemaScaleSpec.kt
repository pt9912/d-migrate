package dev.dmigrate.test.perf

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.postgresql.PostgresDiffDdlGenerator
import dev.dmigrate.profiling.perf.PerfMeasure
import dev.dmigrate.profiling.perf.PerfReport
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan

private val PerfTag = NamedTag("perf")
private val LargeSchemaTag = NamedTag("large-schema")

/**
 * Phase D large-schema scale spec.
 *
 * Plan-Doc: `docs/planning/done-archive/quality-coverage-expansion-plan.md`
 * §5.4 (Sub-Slice D).
 *
 * For each [Scale] the spec builds a synthetic mixed schema
 * (4×n + 1: n tables + n sequences + n views + n triggers + 1 shared
 * trigger function — NOT 5×n; the triggers share a single function) via
 * [LargeSchemaGenerator], runs the full
 * `current=empty → desired=schema` planner + PostgreSQL renderer
 * pipeline, and asserts both the wall-clock smoke budget
 * (`renderSmokeMaxMs`) and the heap-peak budget (`maxHeapMb`).
 * Baseline values are written into the JSON trend report via
 * [PerfReport.write] but are **not** asserted on shared-CI — the
 * `d-migrate.perf.gate=true` system property (forwarded from
 * `make docker-perf PERF_GATE=true`) flips them into a hard gate
 * matching the Phase-A contract.
 *
 * Scales pinned in Sub-Slice D:
 *   - N=100  (renderSmokeMaxMs = 10 s, maxHeapMb = 256)
 *   - N=1000 (renderSmokeMaxMs = 30 s, maxHeapMb = 1024)
 *
 * N=10000 is deferred to Sub-Slice D-N10k as a nightly-only
 * opt-in (separate spec class so the standard `make docker-perf`
 * does not pull a multi-minute scale into every nightly run).
 *
 * Run opt-in only:
 * ```
 * make docker-perf MODULES=":test:perf-large-schema"
 * ```
 */
class LargeSchemaScaleSpec : FunSpec({

    tags(PerfTag, LargeSchemaTag)

    SCALES.forEach { scale ->
        test("LargeSchemaRender — N=${scale.n} stays within smoke + heap budgets") {
            val schema = LargeSchemaGenerator.mixedSchema(
                tables = scale.n,
                sequences = scale.n,
                views = scale.n,
                triggers = scale.n,
                seed = "n${scale.n}",
            )

            val budget = HeapBudget.start(scale.maxHeapMb)
            val sample = PerfMeasure.run(warmup = 0, iterations = 1) {
                runMigratePipeline(schema)
            }
            val peakHeapMb = budget.peakUsedMb()

            sample.medianMs shouldBeLessThan scale.renderSmokeMaxMs.toDouble()
            peakHeapMb shouldBeLessThan scale.maxHeapMb

            PerfReport.write(
                hotpath = "large-schema-render-n${scale.n}",
                sample = sample,
                smokeMaxMs = scale.renderSmokeMaxMs.toDouble(),
                baselineMs = scale.renderBaselineMs.toDouble(),
            )
        }
    }

    // LN-004 (Skalierbarkeit): „DDL-Generierung für 1.000 Tabellen in unter 30 s".
    // Bewusst REINE Tabellen (sequences/views/triggers = 0). Der gemischte 4×n-Scale
    // oben misst 4001 Objekte + Dependency-Topologie (Views→Tables, Trigger→Function)
    // und ist DESHALB NICHT die literale LN-004-Metrik — er ist ein umfassenderer
    // Stress-Check. Dieser Test bildet LN-004 treu ab (nur Tabellen-DDL).
    test("LN-004 — DDL-Generierung für 1000 reine Tabellen unter 30 s") {
        val schema = LargeSchemaGenerator.mixedSchema(
            tables = 1000, sequences = 0, views = 0, triggers = 0, seed = "ln004",
        )
        val budget = HeapBudget.start(1024L)
        val sample = PerfMeasure.run(warmup = 0, iterations = 1) {
            runMigratePipeline(schema)
        }
        sample.medianMs shouldBeLessThan 120_000.0   // Smoke-Runaway-Guard
        budget.peakUsedMb() shouldBeLessThan 1024L
        PerfReport.write(
            hotpath = "ddl-1000-tables-ln004",
            sample = sample,
            smokeMaxMs = 120_000.0,
            baselineMs = 30_000.0,   // LN-004; hart nur unter PERF_GATE (designierter Runner)
        )
    }
}) {
    companion object {
        /**
         * Per-scale budget tuple. `renderSmokeMaxMs` is the runaway
         * guard (asserted), `renderBaselineMs` is the
         * nightly/dedicated-runner expectation (written into the
         * trend report; gate-only under `d-migrate.perf.gate=true`).
         *
         * Smoke budgets are deliberately generous — the synthetic
         * schemas exercise the full planner + renderer chain for
         * 4×n + 1 objects (n tables + n sequences + n views + n
         * triggers + 1 shared trigger function), and the cold-CI JIT
         * warmup adds substantial tail latency to the first iterations.
         */
        internal data class Scale(
            val n: Int,
            val renderSmokeMaxMs: Long,
            val renderBaselineMs: Long,
            val maxHeapMb: Long,
        )

        // Der gemischte 4×n-Scale ist ein UMFASSENDER Stress-Check (Tabellen +
        // Sequenzen + Views + Trigger + Dependency-Topologie), NICHT die LN-004-Metrik
        // („1.000 Tabellen") — die deckt der separate `ddl-1000-tables-ln004`-Test ab.
        // Früher skalierte das 4×n stark super-linear (N=100 ~0,4 s vs. N=1000 ~52 s) wegen
        // eines kubischen `TopologicalSorter` (pro Schritt voller `remaining`-Scan + `List`-
        // `in` + Re-Sort). Seit der Kahn-/PriorityQueue-Linearisierung (2026-06-25,
        // `open/large-schema-superlinear-scaling.md`) ist es linearithmisch — gemessen kalt
        // N=100 ~0,2 s, N=1000 ~0,13 s, ln004 ~0,02 s. Die Budgets sind entsprechend
        // gestrafft (Baseline = nightly-Erwartung, Smoke = Runaway-Guard mit Headroom für
        // kalte-CI-JIT-Tails), kein LF-Abnahmebudget (LN-004 = separater Test).
        internal val SCALES: List<Scale> = listOf(
            Scale(n = 100, renderSmokeMaxMs = 10_000L, renderBaselineMs = 2_000L, maxHeapMb = 256L),
            Scale(n = 1000, renderSmokeMaxMs = 30_000L, renderBaselineMs = 5_000L, maxHeapMb = 1024L),
        )

        /**
         * Build a [DiffResult] from `current=empty → desired=schema`
         * and feed it through the real PostgreSQL diff renderer.
         * Returns the rendered statement count so the
         * [PerfMeasure.Sink] keeps a real object alive (no JIT DCE
         * of the workload).
         */
        internal fun runMigratePipeline(schema: SchemaDefinition): Int {
            val current = SchemaDefinition(name = schema.name, version = schema.version)
            val schemaDiff = SchemaComparator().compare(current, schema)
            val plan = DiffPlanner().plan(current, schema, schemaDiff)
            val renderer = PostgresDiffDdlGenerator()
            val result = renderer.generateUp(plan, DdlGenerationOptions())
            return result.statements.size
        }
    }
}
