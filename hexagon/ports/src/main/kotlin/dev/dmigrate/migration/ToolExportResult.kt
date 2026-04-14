package dev.dmigrate.migration

/**
 * Severity for tool-export-specific diagnostic notes.
 * Deliberately separate from [dev.dmigrate.driver.TransformationNote]
 * to keep generator diagnostics and export diagnostics distinct.
 */
enum class ToolExportSeverity { INFO, WARNING, ACTION_REQUIRED }

/**
 * A diagnostic note produced during tool-specific export rendering.
 * Not to be confused with [dev.dmigrate.driver.TransformationNote]
 * (generator-level) or [dev.dmigrate.driver.SchemaReadNote] (reverse-level).
 */
data class ToolExportNote(
    val severity: ToolExportSeverity,
    val code: String,
    val message: String,
    val objectName: String? = null,
    val hint: String? = null,
)

/**
 * A single file artifact produced by a tool adapter.
 *
 * [relativePath] is always relative to the output directory and
 * guaranteed to be normalized (no `..`, no absolute paths).
 */
data class MigrationArtifact(
    val relativePath: ArtifactRelativePath,
    val kind: String,
    val content: String,
)

/**
 * Result of a tool-specific export: artifacts plus optional export notes.
 *
 * Generator-level notes ([DdlResult.notes]) and skipped objects remain
 * accessible through the [MigrationBundle]; this result carries only
 * export-specific diagnostics.
 */
data class ToolExportResult(
    val artifacts: List<MigrationArtifact>,
    val exportNotes: List<ToolExportNote> = emptyList(),
)
