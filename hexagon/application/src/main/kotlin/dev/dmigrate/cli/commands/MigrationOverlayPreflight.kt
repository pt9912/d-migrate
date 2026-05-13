package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostic
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayReport
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayReportItem
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidator
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidationContext
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * F.0 pre-render gate for versioned migration overlays. The actual
 * feature-specific consumers (PostgreSQL USING, rename mappings, later
 * transformation plans) may still enforce narrower semantics, but every
 * overlay document must first satisfy the shared fingerprint/hash/dialect
 * contract.
 */
internal object MigrationOverlayPreflight {

    fun validate(plan: DiffResult, dialect: DatabaseDialect): MigrationOverlayPreflightResult {
        if (plan.migrationOverlays.isEmpty()) return MigrationOverlayPreflightResult(emptyList(), emptyList())

        val sourceFingerprint = plan.current.fingerprint.orEmpty()
        val targetFingerprint = plan.desired.fingerprint.orEmpty()
        val reports = plan.migrationOverlays.flatMap { document ->
            val result = MigrationOverlayValidator.validate(
                overlay = document.overlay,
                context = MigrationOverlayValidationContext(
                    expectedSourceFingerprint = sourceFingerprint,
                    expectedTargetFingerprint = targetFingerprint,
                    expectedDialect = dialect.name.lowercase(),
                ),
                source = document.source,
            )
            MigrationOverlayReport.fromValidation(result)
        }
        val diagnostics = reports.map { item ->
            DiffDiagnostic(
                code = item.diagnosticCode,
                message = "Migration overlay source=${item.source} entry=${item.entryId ?: "<document>"} " +
                    "hash=${item.overlayHash} failed F.0 contract validation.",
                severity = item.severity.toDiffSeverity(),
                operationId = null,
            )
        }
        return MigrationOverlayPreflightResult(reports, diagnostics)
    }

    fun buildFailureResult(plan: DiffResult, result: MigrationOverlayPreflightResult): MigrationDdlResult {
        val blockerDiagnostics = result.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val skipped = plan.operations.map { it.id }.toSet()
        return MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            operationsSkipped = skipped,
            blockers = listOf(
                MigrationBlocker(
                    reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                    operationIds = skipped,
                    diagnostics = blockerDiagnostics,
                ),
            ),
            primaryBlockedReason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            diagnostics = result.diagnostics,
        )
    }

    private fun MigrationOverlayDiagnostic.Severity.toDiffSeverity(): DiffDiagnostic.Severity = when (this) {
        MigrationOverlayDiagnostic.Severity.INFO -> DiffDiagnostic.Severity.INFO
        MigrationOverlayDiagnostic.Severity.WARNING -> DiffDiagnostic.Severity.WARNING
        MigrationOverlayDiagnostic.Severity.BLOCKER -> DiffDiagnostic.Severity.BLOCKER
    }
}

internal data class MigrationOverlayPreflightResult(
    val reportItems: List<MigrationOverlayReportItem>,
    val diagnostics: List<DiffDiagnostic>,
) {
    val hasBlockers: Boolean
        get() = diagnostics.any { it.severity == DiffDiagnostic.Severity.BLOCKER }
}
