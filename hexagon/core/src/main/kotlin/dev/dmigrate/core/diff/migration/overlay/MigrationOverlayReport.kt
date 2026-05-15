package dev.dmigrate.core.diff.migration.overlay

/**
 * Carrier used by the CLI/report layer to surface one overlay
 * finding. F.4 rename-mapping-invalid-enum slice extends this with
 * the same structured provenance fields as
 * [MigrationOverlayDiagnostic] ([entryKind], [renameObjectType]) so
 * the application-layer reason-classifier can decide between
 * `RENAME_MAPPING_INVALID` and `MANUAL_ACTION_REQUIRED` without
 * parsing free-form text.
 */
data class MigrationOverlayReportItem(
    val source: String,
    val entryId: String?,
    val overlayHash: String,
    val diagnosticCode: String,
    val severity: MigrationOverlayDiagnostic.Severity,
    val entryKind: String? = null,
    val renameObjectType: String? = null,
    /**
     * F.4 cli-inline-overlay slice §3.4: the validator's own
     * fact-bearing message text (or `null` for entries we accept
     * as provenance). The preflight uses this instead of a
     * synthesised "failed F.0 contract validation" string so
     * downstream Diagnostics carry the actual finding.
     */
    val message: String? = null,
)

object MigrationOverlayReport {
    /**
     * Placeholder used in the `overlayHash` slot when the underlying
     * overlay document could not be parsed / loaded, so no canonical
     * hash exists. Exposed publicly so report consumers can match on
     * the same constant the preflight emits instead of typing the
     * sentinel string by hand.
     */
    const val UNAVAILABLE_OVERLAY_HASH: String = "<unavailable>"

    /**
     * Builds the per-document report rows: one row per validator
     * diagnostic (BLOCKER/WARNING/INFO that the validator emits),
     * plus one INFO-`OVERLAY_ACCEPTED` provenance row per entry that
     * passed without a BLOCKER finding ([MigrationOverlayValidationResult.acceptedEntries]).
     * The accepted rows are pure provenance and MUST NOT become
     * Failure-`DiffDiagnostic`s downstream.
     */
    fun fromValidation(result: MigrationOverlayValidationResult): List<MigrationOverlayReportItem> {
        val diagnosticItems = result.diagnostics.map { diagnostic ->
            MigrationOverlayReportItem(
                source = result.source,
                entryId = diagnostic.entryId,
                overlayHash = diagnostic.overlayHash,
                diagnosticCode = diagnostic.code,
                severity = diagnostic.severity,
                entryKind = diagnostic.entryKind,
                renameObjectType = diagnostic.renameObjectType,
                message = diagnostic.message,
            )
        }
        val acceptedItems = result.acceptedEntries.map { entry ->
            MigrationOverlayReportItem(
                source = result.source,
                entryId = entry.id,
                overlayHash = result.overlayHash,
                diagnosticCode = MigrationOverlayDiagnostics.OVERLAY_ACCEPTED,
                severity = MigrationOverlayDiagnostic.Severity.INFO,
                entryKind = entry.kind,
                renameObjectType = (entry as? RenameMappingOverlayEntry)?.objectType,
                message = null,
            )
        }
        return diagnosticItems + acceptedItems
    }
}
