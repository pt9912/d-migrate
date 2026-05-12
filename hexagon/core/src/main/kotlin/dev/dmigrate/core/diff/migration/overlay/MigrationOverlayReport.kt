package dev.dmigrate.core.diff.migration.overlay

data class MigrationOverlayReportItem(
    val source: String,
    val entryId: String?,
    val overlayHash: String,
    val diagnosticCode: String,
    val severity: MigrationOverlayDiagnostic.Severity,
)

object MigrationOverlayReport {
    fun fromValidation(result: MigrationOverlayValidationResult): List<MigrationOverlayReportItem> =
        result.diagnostics.map { diagnostic ->
            MigrationOverlayReportItem(
                source = result.source,
                entryId = diagnostic.entryId,
                overlayHash = diagnostic.overlayHash,
                diagnosticCode = diagnostic.code,
                severity = diagnostic.severity,
            )
        }
}
