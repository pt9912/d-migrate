package dev.dmigrate.driver

/**
 * A structured note from the reverse-engineering read path.
 *
 * Semantically distinct from [TransformationNote] (which is
 * DDL-/generator-specific). Reverse notes describe best-effort
 * mappings, dialect-specific limitations, and other observations
 * from reading a live database.
 */
data class SchemaReadNote(
    val severity: SchemaReadSeverity,
    val code: String,
    val objectName: String,
    val message: String,
    val hint: String? = null,
)

/**
 * Severity levels for reverse-engineering notes.
 */
enum class SchemaReadSeverity {
    INFO,
    WARNING,
    ACTION_REQUIRED,
}
