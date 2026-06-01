package dev.dmigrate.cli.commands.perf

import dev.dmigrate.cli.commands.CompareOperand
import dev.dmigrate.cli.commands.MigrationOverlayPreflightResult
import dev.dmigrate.cli.commands.SchemaMigrateRenderPipeline
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.profiling.perf.PerfMeasure
import dev.dmigrate.profiling.perf.PerfReport
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import java.nio.file.Path

private val PerfTag = NamedTag("perf")

/**
 * Phase A hotpath: [SchemaMigrateRenderPipeline.run] for a synthetic
 * 100-op `CreateTable` plan, file-mode operand, PostgreSQL dialect.
 *
 * **Plan-Doc**: `docs/planning/done/quality-coverage-expansion-plan.md`
 * §5.1 / §7 first checklist box. First consumer of the new
 * [PerfMeasure] / [PerfReport] library; A-Vervollständigung mirrors
 * the same two-budget contract for `DiffPlanner` and the
 * `RollbackArtefactBuilder`↔`RollbackArtefactParser` round-trip.
 *
 * **Measured surface**: the request sets `generateRollback = true`,
 * so the pipeline runs the full path — probe + preflight-plan +
 * sequence-preserve + cast/check preflight + render Up + destructive
 * guard + render Down + merge + transactionScope guard. Review
 * finding #5 (without `generateRollback = true` the down render +
 * merge are skipped and the spec under-reports orchestration cost).
 *
 * **Two budgets per hotpath**:
 * - [RENDER_SMOKE_MAX_MS] is the runaway guard; asserted against
 *   median **and** p95 separately so an ugly tail iteration alone is
 *   enough to fail. Calibrated generously so shared-container CI
 *   jitter does not flap.
 * - [RENDER_BASELINE_MS] is the nightly / `perf-stable-runner`
 *   expectation. Diagnostic on shared-CI; hard gate when
 *   `make docker-perf PERF_GATE=true` forwards `-PperfGate=true`,
 *   which the root `build.gradle.kts` translates into the system
 *   property `d-migrate.perf.gate=true` that [PerfReport.write]
 *   consults.
 *
 * Run opt-in only:
 * ```
 * make docker-perf
 * # or
 * ./gradlew :hexagon:application:test -Dkotest.tags=perf
 * ```
 */
class SchemaMigrateRenderPipelinePerfSpec : FunSpec({

    tags(PerfTag)

    test("SchemaMigrateRenderPipeline.run for 100-op plan stays within smoke budget") {
        val plan = SyntheticPlans.createTables(opCount = OP_COUNT)
        // Sanity: the synthetic generator must really produce OP_COUNT
        // ops, otherwise a regression in DiffPlanner / OperationMapper
        // could silently shrink the workload and mask drift.
        plan.operations.size shouldBeGreaterThanOrEqual OP_COUNT

        val pipeline = SchemaMigrateRenderPipeline(
            sqliteLiveCatalogProbe = null,
            sqliteCastPreflightPlanner = null,
            sqliteCastPreflightProbe = null,
            checkPreflightProbe = null,
            mysqlSequenceCanonicityProbe = null,
        )
        val request = SchemaMigrateRequest(
            source = "file:source.yaml",
            target = "file:target.yaml",
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
            // Review finding #5: exercise the Down render + merge path.
            // Without this the pipeline's renderer.generateDown call and
            // the mergeDownIntoUp branch (RenderPipeline.run:134-143) are
            // skipped, so a regression in the Down generator or the
            // merge orchestration never moves this hotpath's budget.
            generateRollback = true,
        )
        val targetOp: CompareOperand = CompareOperand.File(Path.of("target.yaml"))
        val renderer = SyntheticDiffDdlGenerator(DatabaseDialect.POSTGRESQL)
        val overlayPreflight = MigrationOverlayPreflightResult(emptyList(), emptyList())

        val sample = PerfMeasure.run(warmup = WARMUP, iterations = ITERATIONS) {
            pipeline.run(
                request = request,
                targetOp = targetOp,
                dialect = DatabaseDialect.POSTGRESQL,
                renderer = renderer,
                plan = plan,
                overlayPreflight = overlayPreflight,
                cancellationToken = CancellationToken.none(),
            )
        }

        sample.medianMs shouldBeLessThan RENDER_SMOKE_MAX_MS
        sample.p95Ms shouldBeLessThan RENDER_SMOKE_MAX_MS

        PerfReport.write(
            hotpath = HOTPATH,
            sample = sample,
            smokeMaxMs = RENDER_SMOKE_MAX_MS,
            baselineMs = RENDER_BASELINE_MS,
        )
    }
}) {
    companion object {
        private const val HOTPATH = "schema-migrate-render-pipeline"
        private const val OP_COUNT = 100
        private const val WARMUP = 5
        private const val ITERATIONS = 20

        /**
         * Runaway-Smoke guard. 5 s leaves enough room for shared-CI
         * cold starts on a 100-op plan with a synthetic renderer that
         * does no actual SQL work — the same budget will need to be
         * re-derived per hotpath in A-Vervollständigung.
         */
        private const val RENDER_SMOKE_MAX_MS = 5_000.0

        /**
         * Nightly / `perf-stable-runner` baseline; drift here is a
         * diagnostic signal in the trend dashboard. Bump only with a
         * commit note quoting old/new median + p95.
         */
        private const val RENDER_BASELINE_MS = 250.0
    }
}

/**
 * Deterministic synthetic [DiffResult] generators for the Phase A
 * hotpath spec. Lives in the spec file (no fixture spreading) until
 * A-Vervollständigung introduces additional hotpaths that share it —
 * at which point it migrates next to [PerfMeasure] / [PerfReport].
 */
private object SyntheticPlans {

    fun createTables(opCount: Int): DiffResult {
        require(opCount > 0) { "opCount must be > 0, was $opCount" }
        val current = SchemaDefinition(name = "App", version = "1")
        val desired = SchemaDefinition(
            name = "App",
            version = "1",
            tables = (1..opCount).associate { i ->
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
        return DiffPlanner().plan(current, desired, schemaDiff)
    }
}

/**
 * Renders one synthetic statement per operation. The body string is
 * intentionally short so the spec measures *pipeline orchestration*
 * cost — stage dispatch, options assembly, destructive guard,
 * Down render, combined-stream merge, transactionScope guard — rather
 * than dialect-specific DDL generation. A realistic dialect renderer
 * is tracked separately once the orchestration baseline is in place.
 *
 * Review finding #10: [generateDown] deliberately delegates to
 * [generateUp]. The pipeline does not require the down stream to be
 * the semantic inverse of the up stream — it only walks the operations
 * to build statements and feeds them into [mergeDownIntoUp]. A
 * symmetric renderer is therefore the cheapest way to keep both
 * sides realistic in shape; correctness of the down direction is the
 * concern of the real dialect renderers, covered by their own tests.
 */
private class SyntheticDiffDdlGenerator(
    override val dialect: DatabaseDialect,
) : DiffDdlGenerator {

    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult {
        val statements = diff.operations.map { op ->
            MigrationDdlStatement(
                sql = "-- $dialect ${op.objectType} ${op.id};",
                operationIds = setOf(op.id),
                risk = OperationRisk.SAFE,
                phase = DiffPhase.TABLES,
            )
        }
        return MigrationDdlResult(
            statements = statements,
            operationsRendered = diff.operations.map { it.id }.toSet(),
        )
    }

    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        generateUp(diff, options)
}
