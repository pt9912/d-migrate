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
 * Plan-Doc: `docs/planning/done/quality-coverage-expansion-plan.md`
 * §5.4 (Sub-Slice D).
 *
 * For each [Scale] the spec builds a synthetic mixed schema
 * (tables + sequences + views + functions + triggers) via
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
 *   - N=100  (renderSmokeMaxMs = 30 s, maxHeapMb = 256)
 *   - N=1000 (renderSmokeMaxMs = 120 s, maxHeapMb = 1024)
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
         * 5×n objects (tables + sequences + views + functions +
         * triggers), and the cold-CI JIT warmup adds substantial
         * tail latency to the first iterations.
         */
        internal data class Scale(
            val n: Int,
            val renderSmokeMaxMs: Long,
            val renderBaselineMs: Long,
            val maxHeapMb: Long,
        )

        internal val SCALES: List<Scale> = listOf(
            Scale(n = 100, renderSmokeMaxMs = 30_000L, renderBaselineMs = 2_000L, maxHeapMb = 256L),
            Scale(n = 1000, renderSmokeMaxMs = 120_000L, renderBaselineMs = 30_000L, maxHeapMb = 1024L),
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
