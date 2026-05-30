package dev.dmigrate.cli.commands.perf

import dev.dmigrate.cli.commands.RollbackArtefactBuilder
import dev.dmigrate.cli.commands.RollbackArtefactParser
import dev.dmigrate.cli.commands.statementsFromIndex
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.profiling.perf.PerfMeasure
import dev.dmigrate.profiling.perf.PerfReport
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val PerfTag = NamedTag("perf")

/**
 * Phase A hotpath: [RollbackArtefactBuilder.build] +
 * [RollbackArtefactParser.parse] round-trip on a synthetic
 * 100-statement Down bundle.
 *
 * **Plan-Doc**: `docs/planning/in-progress/quality-coverage-expansion-plan.md`
 * §5.1 / §6 (Sub-Slice A-Vervollständigung). Third consumer of
 * [PerfMeasure] / [PerfReport]. The round-trip is measured as one
 * unit (build → parse → assert Success) so the spec captures the
 * canonical-body construction, SHA-256 hash, header JSON canonicalisation
 * and the parser's MiniJson + hash-verify path in a single sample.
 *
 * Each iteration produces a [RollbackArtefactParser.Result.Success]
 * — a parse failure under measurement would silently inflate the
 * sample with parser error paths instead of round-trip cost, so the
 * spec asserts success per iteration.
 *
 * Run opt-in only:
 * ```
 * make docker-perf MODULES=":hexagon:application"
 * ```
 */
class RollbackArtefactRoundTripPerfSpec : FunSpec({

    tags(PerfTag)

    test("RollbackArtefactBuilder.build + Parser.parse for 100-statement bundle stays within smoke budget") {
        val input = SyntheticRollbackInput.build(opCount = OP_COUNT)

        // Sanity check: the builder produces a valid v2 artefact that
        // the parser accepts. Run once outside the loop so a faulty
        // fixture cannot mask itself as a perf regression.
        val warmupArtefact = RollbackArtefactBuilder.build(input)
        val warmupResult = RollbackArtefactParser.parse(warmupArtefact)
        warmupResult.shouldBeInstanceOf<RollbackArtefactParser.Result.Success>()

        val sample = PerfMeasure.run(warmup = WARMUP, iterations = ITERATIONS) {
            val artefact = RollbackArtefactBuilder.build(input)
            val parsed = RollbackArtefactParser.parse(artefact)
            check(parsed is RollbackArtefactParser.Result.Success) {
                "Perf round-trip must produce Success, got ${(parsed as RollbackArtefactParser.Result.Failure).code}"
            }
            // Return the artefact length so the JIT cannot eliminate
            // the build → parse chain — [PerfMeasure.Sink] keeps the
            // return value alive.
            artefact.length
        }

        sample.medianMs shouldBeLessThan ARTEFACT_SMOKE_MAX_MS
        sample.p95Ms shouldBeLessThan ARTEFACT_SMOKE_MAX_MS

        PerfReport.write(
            hotpath = HOTPATH,
            sample = sample,
            smokeMaxMs = ARTEFACT_SMOKE_MAX_MS,
            baselineMs = ARTEFACT_BASELINE_MS,
        )

        // Belt-and-braces: assert the round-trip preserves the
        // canonical statement count after the loop, so a regression
        // that silently drops statements during canonicalisation
        // still fails the spec rather than just shifting the timing.
        val parsed = (RollbackArtefactParser.parse(warmupArtefact) as RollbackArtefactParser.Result.Success).parsed
        parsed.statementsFromIndex().size shouldBe OP_COUNT
    }
}) {
    companion object {
        private const val HOTPATH = "rollback-artefact-round-trip"
        private const val OP_COUNT = 100
        private const val WARMUP = 5
        private const val ITERATIONS = 20

        /** Runaway-Smoke guard. */
        private const val ARTEFACT_SMOKE_MAX_MS = 5_000.0

        /** Nightly baseline; diagnostic-only on shared-CI. */
        private const val ARTEFACT_BASELINE_MS = 250.0
    }
}

/**
 * Deterministic synthetic builder input. Each statement carries a
 * unique `op-<n>` ID so the `statementIndex` and `operationIds` sets
 * have realistic cardinality, but the SQL body stays short to keep
 * the measurement focused on canonicalisation / hashing / parsing
 * rather than raw-string throughput.
 */
private object SyntheticRollbackInput {

    fun build(opCount: Int): RollbackArtefactBuilder.Input {
        require(opCount > 0) { "opCount must be > 0, was $opCount" }
        val statements = (1..opCount).map { i ->
            MigrationDdlStatement(
                sql = "DROP TABLE t_$i;",
                operationIds = setOf("op-$i"),
                risk = OperationRisk.SAFE,
                phase = DiffPhase.TABLES,
            )
        }
        val operationIds = (1..opCount).map { "op-$it" }.toSet()
        return RollbackArtefactBuilder.Input(
            dialect = DatabaseDialect.POSTGRESQL,
            currentFingerprint = "fp-current",
            desiredFingerprint = "fp-desired",
            postUpFingerprint = "fp-desired",
            operationIds = operationIds,
            risk = RollbackArtefactBuilder.Risk(
                destructive = true,
                dataLossPossible = false,
                requiresManualConfirmation = false,
                operationIds = operationIds,
            ),
            downStatements = statements,
            createdByVersion = "perf-spec",
        )
    }
}
