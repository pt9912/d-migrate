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

    fun fromValidation(result: MigrationOverlayValidationResult): List<MigrationOverlayReportItem> =
        result.diagnostics.map { diagnostic ->
            MigrationOverlayReportItem(
                source = result.source,
                entryId = diagnostic.entryId,
                overlayHash = diagnostic.overlayHash,
                diagnosticCode = diagnostic.code,
                severity = diagnostic.severity,
                entryKind = diagnostic.entryKind,
                renameObjectType = diagnostic.renameObjectType,
            )
        }
}
