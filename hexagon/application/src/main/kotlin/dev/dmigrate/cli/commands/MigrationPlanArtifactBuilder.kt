package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DataTransformationContract
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.RenameProjectionReport
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanArtifact
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanArtifactDiagnostic
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanArtifactOperation
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanArtifactRenameProjection
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanRenderedStatement
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanReversibilitySummary
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanRisk
import dev.dmigrate.core.diff.routine.RoutineBodyScrubber
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * F.4 Sub-Slice G.2 (2026-05-19): pure projection from the internal
 * planning + rendering state into the public
 * [MigrationPlanArtifact]. The runner calls this once per
 * `schema migrate` invocation when `--plan-artefact` is set, after
 * the plan + render have completed but before the artefact sink
 * writes the report.
 *
 * The builder does not read mutable state; it consumes the immutable
 * [DiffResult] / [MigrationDdlResult] / dialect + clock parameters
 * and returns a signed-and-gated artifact. The two convenience
 * tail-calls
 * ([MigrationPlanArtifact.withRenameProjectionExtension] +
 * [MigrationPlanArtifact.withComputedHash]) keep the producer side of
 * the F.4 Sub-Slice E semantic-extension gate honest: a non-empty
 * `renameProjections` list always carries the
 * `rename-projections.v1` flag in `semanticExtensions`, so an old
 * consumer is forced to reject the artifact rather than silently
 * running the Drop+Create fallback as an ordinary destructive change.
 */
internal object MigrationPlanArtifactBuilder {

    private val UTC_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun build(
        plan: DiffResult,
        rendered: MigrationDdlResult,
        dialect: DatabaseDialect,
        clock: Clock,
        dMigrateVersion: String,
    ): MigrationPlanArtifact {
        val operations = plan.operations.map(::operationView)
        val diagnostics = plan.diagnostics.map(::diagnosticView)
        val reversibilitySummary = buildReversibilitySummary(plan.operations)
        val renderedStatements = rendered.statements.mapIndexed(::statementView)
        val renameProjections = plan.renameProjections.map(::renameProjectionView)
        val artifact = MigrationPlanArtifact(
            dMigrateVersion = dMigrateVersion,
            sourceFingerprint = plan.current.fingerprint
                ?: error("DiffResult.current.fingerprint must be set before artifact emission"),
            targetFingerprint = plan.desired.fingerprint
                ?: error("DiffResult.desired.fingerprint must be set before artifact emission"),
            fingerprintAlgorithm = dev.dmigrate.core.diff.migration.MigrationFingerprint.ALGORITHM,
            dialect = dialect.name.lowercase(Locale.ROOT),
            operations = operations,
            diagnostics = diagnostics,
            reversibilitySummary = reversibilitySummary,
            renderedStatements = renderedStatements,
            renameProjections = renameProjections,
            createdAt = UTC_FORMATTER.format(clock.instant()),
        )
        return artifact.withRenameProjectionExtension().withComputedHash()
    }

    private fun operationView(op: DiffOperation): MigrationPlanArtifactOperation =
        MigrationPlanArtifactOperation(
            id = op.id,
            kind = op::class.simpleName ?: error("DiffOperation subtype must have a name: $op"),
            objectType = op.objectRef.type.name,
            objectPath = op.objectRef.path,
            phase = op.phase.name,
            reversibility = op.reversibility.name,
            upRisk = riskView(op.risks.up),
            downRisk = op.risks.down?.let(::riskView),
        )

    private fun riskView(risk: OperationRisk): MigrationPlanRisk =
        MigrationPlanRisk(
            destructive = risk.destructive,
            dataLossPossible = risk.dataLossPossible,
            requiresTableRewrite = risk.requiresTableRewrite,
            requiresManualConfirmation = risk.requiresManualConfirmation,
            dataTransformationMode = risk.dataTransformation.mode.name,
            dataTransformationModelVersion = risk.dataTransformation.modelVersion
                .takeIf { contractCarriesModelMetadata(risk.dataTransformation) },
            dataTransformationModelId = risk.dataTransformation.modelId
                .takeIf { contractCarriesModelMetadata(risk.dataTransformation) },
        )

    private fun contractCarriesModelMetadata(contract: DataTransformationContract): Boolean =
        contract.modelVersion != null || contract.modelId != null

    private fun diagnosticView(diagnostic: DiffDiagnostic): MigrationPlanArtifactDiagnostic =
        MigrationPlanArtifactDiagnostic(
            code = diagnostic.code,
            severity = diagnostic.severity.name,
            operationId = diagnostic.operationId,
        )

    private fun buildReversibilitySummary(operations: List<DiffOperation>): MigrationPlanReversibilitySummary {
        val manualRequired = operations.filter { it.reversibility == Reversibility.MANUAL_REQUIRED }.map { it.id }
        val notReversible = operations.filter { it.reversibility == Reversibility.NOT_REVERSIBLE }.map { it.id }
        val fullyReversible = operations.all {
            it.reversibility == Reversibility.AUTOMATIC ||
                it.reversibility == Reversibility.AUTOMATIC_WITH_DATA_RISK
        }
        return MigrationPlanReversibilitySummary(
            fullyReversible = fullyReversible,
            manualRequiredOperationIds = manualRequired,
            notReversibleOperationIds = notReversible,
        )
    }

    private fun statementView(index: Int, statement: MigrationDdlStatement): MigrationPlanRenderedStatement {
        // E.1 Slice F.2 reused: every rendered statement carries a
        // scrubbed canonical hash. The artifact never embeds the SQL
        // body (which may contain secrets); the hash is the public
        // identity.
        val preview = RoutineBodyScrubber.preview(statement.sql)
        return MigrationPlanRenderedStatement(
            statementId = "stmt-${index + 1}",
            operationIds = statement.operationIds.toList(),
            sqlHash = preview.hash.orEmpty(),
            transactionScope = statement.transactionScope.name,
        )
    }

    private fun renameProjectionView(report: RenameProjectionReport): MigrationPlanArtifactRenameProjection =
        MigrationPlanArtifactRenameProjection(
            candidateId = report.candidateId,
            objectType = report.objectType,
            fromPath = report.fromPath,
            toPath = report.toPath,
            overlaySource = report.overlaySource,
            overlayEntryId = report.overlayEntryId,
            overlayHash = report.overlayHash,
            renameOperationId = report.renameOperationId,
            fallbackOperationIds = report.fallbackOperationIds,
            fallbackReason = report.fallbackReason,
        )
}
