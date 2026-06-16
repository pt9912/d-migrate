package dev.dmigrate.core.diff.migration.perf

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.profiling.perf.PerfMeasure
import dev.dmigrate.profiling.perf.PerfReport
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

private val PerfTag = NamedTag("perf")

/**
 * Phase A hotpath: [DiffPlanner.plan] for a synthetic 100-table
 * CreateTable plan, no overlays, no rename projection.
 *
 * **Plan-Doc**: `docs/planning/done-archive/quality-coverage-expansion-plan.md`
 * §5.1 / §6 (Sub-Slice A-Vervollständigung). Second consumer of
 * [PerfMeasure] / [PerfReport]; the SchemaComparator step runs once
 * outside the loop so the measurement isolates the planner cost
 * (OperationMapper → DependencyAnalyzer → TopologicalSorter), not
 * the comparator.
 *
 * Budget calibration mirrors
 * `SchemaMigrateRenderPipelinePerfSpec`: a generous runaway-Smoke
 * guard plus a baseline value written into the JSON report. The
 * baseline is not asserted on shared-CI.
 *
 * Run opt-in only:
 * ```
 * make docker-perf MODULES=":hexagon:core"
 * ```
 */
class DiffPlannerPerfSpec : FunSpec({

    tags(PerfTag)

    test("DiffPlanner.plan for 100-table CreateTable plan stays within smoke budget") {
        val current = SchemaDefinition(name = "App", version = "1")
        val desired = SchemaDefinition(
            name = "App",
            version = "1",
            tables = (1..TABLE_COUNT).associate { i ->
                "t_$i" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "name" to ColumnDefinition(NeutralType.Text(), required = true),
                    ),
                    primaryKey = listOf("id"),
                )
            },
        )
        val schemaDiff = SchemaComparator().compare(current, desired)
        val planner = DiffPlanner()

        // Sanity: the planner must produce at least one op per
        // synthetic table; otherwise a regression in the mapper could
        // silently shrink the workload.
        val initial = planner.plan(current, desired, schemaDiff)
        initial.operations.size shouldBeGreaterThanOrEqual TABLE_COUNT

        val sample = PerfMeasure.run(warmup = WARMUP, iterations = ITERATIONS) {
            planner.plan(current, desired, schemaDiff)
        }

        sample.medianMs shouldBeLessThan PLAN_SMOKE_MAX_MS
        sample.p95Ms shouldBeLessThan PLAN_SMOKE_MAX_MS

        PerfReport.write(
            hotpath = HOTPATH,
            sample = sample,
            smokeMaxMs = PLAN_SMOKE_MAX_MS,
            baselineMs = PLAN_BASELINE_MS,
        )
    }
}) {
    companion object {
        private const val HOTPATH = "diff-planner"
        private const val TABLE_COUNT = 100
        private const val WARMUP = 5
        private const val ITERATIONS = 20

        /** Runaway-Smoke guard. */
        private const val PLAN_SMOKE_MAX_MS = 5_000.0

        /** Nightly baseline; diagnostic-only on shared-CI. */
        private const val PLAN_BASELINE_MS = 250.0
    }
}
