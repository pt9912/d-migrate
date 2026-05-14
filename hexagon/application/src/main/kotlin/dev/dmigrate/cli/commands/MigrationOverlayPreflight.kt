package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostic
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayReport
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayReportItem
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidator
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidationContext
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.util.Locale

/**
 * F.0 pre-render gate for versioned migration overlays. The actual
 * feature-specific consumers (PostgreSQL USING, rename mappings, later
 * transformation plans) may still enforce narrower semantics, but every
 * overlay document must first satisfy the shared fingerprint/hash/dialect
 * contract.
 */
internal object MigrationOverlayPreflight {

    /**
     * Plan-2 §F.4 dependency-projection T1: validate overlays
     * **before** the first `DiffPlanner.plan(...)` so a Rename-
     * mapping blocker can surface as a pre-plan failure without
     * forcing the planner to walk a doomed schema diff.
     *
     * Naming inversion: in the migrate pipeline the IS-state lives
     * under `target` (the live DB to mutate) and the SOLL-state lives
     * under `source` (the schema file). The overlay validator's
     * "current"/"desired" semantics align with the IS/SOLL split, so
     * [sourceFingerprint] must carry the IS-state fingerprint and
     * [targetFingerprint] the SOLL-state fingerprint regardless of
     * how the caller labels its variables. Fingerprints are computed
     * up-front by the runner.
     *
     * [dialect] is the engine identifier the overlay must match.
     * Comparison is locale-insensitive: the helper normalises both
     * the incoming value and the overlay's `dialect` field via
     * [Locale.ROOT] before equality so a Turkish JVM cannot turn
     * `"POSTGRESQL"` into `"postgresqı"`.
     */
    @Suppress("LongParameterList")
    fun validateBeforePlan(
        documents: List<MigrationOverlayDocument>,
        sourceFingerprint: String,
        targetFingerprint: String,
        dialect: String,
        loadFailures: List<MigrationOverlayLoadFailure> = emptyList(),
    ): MigrationOverlayPreflightResult {
        if (documents.isEmpty() && loadFailures.isEmpty()) {
            return MigrationOverlayPreflightResult(emptyList(), emptyList())
        }
        val normalisedDialect = dialect.lowercase(Locale.ROOT)
        val validationReports = documents.flatMap { document ->
            val result = MigrationOverlayValidator.validate(
                overlay = document.overlay,
                context = MigrationOverlayValidationContext(
                    expectedSourceFingerprint = sourceFingerprint,
                    expectedTargetFingerprint = targetFingerprint,
                    expectedDialect = normalisedDialect,
                ),
                source = document.source,
            )
            MigrationOverlayReport.fromValidation(result)
        }
        val loadFailureReports = loadFailures.map { failure ->
            MigrationOverlayReportItem(
                source = failure.source,
                entryId = null,
                overlayHash = UNAVAILABLE_OVERLAY_HASH,
                diagnosticCode = failure.diagnosticCode,
                severity = MigrationOverlayDiagnostic.Severity.BLOCKER,
            )
        }
        val reports = validationReports + loadFailureReports
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

    /**
     * Backward-compatible wrapper around [validateBeforePlan] for the
     * pre-T1 call site that already had a [DiffResult] in hand. New
     * call sites should compute fingerprints up-front and call
     * [validateBeforePlan] directly so the gate runs before
     * `DiffPlanner.plan(...)`.
     */
    fun validate(
        plan: DiffResult,
        dialect: DatabaseDialect,
        loadFailures: List<MigrationOverlayLoadFailure> = emptyList(),
    ): MigrationOverlayPreflightResult = validateBeforePlan(
        documents = plan.migrationOverlays,
        sourceFingerprint = plan.current.fingerprint.orEmpty(),
        targetFingerprint = plan.desired.fingerprint.orEmpty(),
        dialect = dialect.name,
        loadFailures = loadFailures,
    )

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

    private const val UNAVAILABLE_OVERLAY_HASH = "<unavailable>"
}

internal data class MigrationOverlayPreflightResult(
    val reportItems: List<MigrationOverlayReportItem>,
    val diagnostics: List<DiffDiagnostic>,
) {
    val hasBlockers: Boolean
        get() = diagnostics.any { it.severity == DiffDiagnostic.Severity.BLOCKER }
}

data class MigrationOverlayLoadFailure(
    val source: String,
    val diagnosticCode: String = MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH,
)
