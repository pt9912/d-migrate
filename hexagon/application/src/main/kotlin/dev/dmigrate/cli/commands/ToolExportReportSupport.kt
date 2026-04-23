package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.ToolExportResult
import java.nio.file.Path

/**
 * Data collected during export for the optional report sidecar.
 */
data class ToolExportReportData(
    val source: Path,
    val tool: MigrationTool,
    val dialect: DatabaseDialect,
    val identity: dev.dmigrate.migration.MigrationIdentity,
    val upResult: DdlResult,
    val downResult: DdlResult?,
    val exportResult: ToolExportResult,
)

/**
 * Renders a minimal YAML report sidecar for tool export.
 */
internal object ToolExportReportRenderer {

    fun render(data: ToolExportReportData): String = buildString {
        appendLine("source: \"${escapeYaml(data.source.toString())}\"")
        appendLine("tool: ${data.tool.name.lowercase()}")
        appendLine("dialect: ${data.dialect.name.lowercase()}")
        appendLine("version: \"${escapeYaml(data.identity.version)}\"")
        appendLine("versionSource: ${data.identity.versionSource.name}")
        appendLine("slug: \"${escapeYaml(data.identity.slug)}\"")

        if (data.upResult.notes.isNotEmpty()) {
            appendLine("notes:")
            for (note in data.upResult.notes) {
                appendLine("  - code: \"${note.code}\"")
                appendLine("    type: ${note.type.name}")
                appendLine("    object: \"${escapeYaml(note.objectName)}\"")
                appendLine("    message: \"${escapeYaml(note.message)}\"")
            }
        }

        if (data.downResult != null && data.downResult.notes.isNotEmpty()) {
            appendLine("rollbackNotes:")
            for (note in data.downResult.notes) {
                appendLine("  - code: \"${note.code}\"")
                appendLine("    type: ${note.type.name}")
                appendLine("    object: \"${escapeYaml(note.objectName)}\"")
                appendLine("    message: \"${escapeYaml(note.message)}\"")
            }
        }

        if (data.upResult.skippedObjects.isNotEmpty()) {
            appendLine("skippedObjects:")
            for (skip in data.upResult.skippedObjects) {
                appendLine("  - type: \"${escapeYaml(skip.type)}\"")
                appendLine("    name: \"${escapeYaml(skip.name)}\"")
                appendLine("    reason: \"${escapeYaml(skip.reason)}\"")
            }
        }

        if (data.downResult != null && data.downResult.skippedObjects.isNotEmpty()) {
            appendLine("rollbackSkippedObjects:")
            for (skip in data.downResult.skippedObjects) {
                appendLine("  - type: \"${escapeYaml(skip.type)}\"")
                appendLine("    name: \"${escapeYaml(skip.name)}\"")
                appendLine("    reason: \"${escapeYaml(skip.reason)}\"")
            }
        }

        if (data.exportResult.exportNotes.isNotEmpty()) {
            appendLine("exportNotes:")
            for (note in data.exportResult.exportNotes) {
                appendLine("  - code: \"${note.code}\"")
                appendLine("    severity: ${note.severity.name}")
                appendLine("    message: \"${escapeYaml(note.message)}\"")
            }
        }

        appendLine("artifacts:")
        for (artifact in data.exportResult.artifacts) {
            appendLine("  - path: \"${escapeYaml(artifact.relativePath.normalized)}\"")
            appendLine("    kind: \"${artifact.kind}\"")
        }
    }

    private fun escapeYaml(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
