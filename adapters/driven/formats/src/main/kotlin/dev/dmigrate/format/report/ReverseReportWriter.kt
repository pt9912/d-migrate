package dev.dmigrate.format.report

import dev.dmigrate.driver.SchemaReadReportInput
import dev.dmigrate.driver.SchemaReadSeverity
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText

/**
 * Writes a structured YAML sidecar report for reverse-engineering
 * results.
 *
 * Semantically distinct from [TransformationReportWriter] which is
 * DDL-/generator-specific. This writer serializes [SchemaReadReportInput]
 * containing reverse notes, skipped objects, and a source reference.
 *
 * **Credential scrubbing**: The caller is responsible for scrubbing
 * URL-based source values via `LogScrubber.maskUrl()` before
 * constructing [SchemaReadReportInput]. The writer renders the source
 * value as-is.
 */
class ReverseReportWriter {

    fun write(output: Path, input: SchemaReadReportInput) {
        output.writeText(render(input))
    }

    fun render(input: SchemaReadReportInput): String = buildString {
        // Source — caller is responsible for scrubbing URL values
        appendLine("source:")
        appendLine("  kind: ${input.source.kind.name.lowercase()}")
        appendLine("  value: \"${escapeYaml(input.source.value)}\"")
        appendLine()

        // Schema metadata
        val schema = input.result.schema
        appendLine("schema:")
        appendLine("  name: \"${escapeYaml(schema.name)}\"")
        appendLine("  version: \"${escapeYaml(schema.version)}\"")
        appendLine("  generated_at: \"${Instant.now()}\"")
        appendLine()

        // Summary
        val notes = input.result.notes
        val warnings = notes.count { it.severity == SchemaReadSeverity.WARNING }
        val actionRequired = notes.count { it.severity == SchemaReadSeverity.ACTION_REQUIRED }
        val skippedCount = input.result.skippedObjects.size

        appendLine("summary:")
        appendLine("  notes: ${notes.size}")
        appendLine("  warnings: $warnings")
        appendLine("  action_required: $actionRequired")
        appendLine("  skipped_objects: $skippedCount")
        appendLine()

        // Notes
        if (notes.isNotEmpty()) {
            appendLine("notes:")
            for (note in notes) {
                appendLine("  - severity: ${note.severity.name.lowercase()}")
                appendLine("    code: ${note.code}")
                appendLine("    object: \"${escapeYaml(note.objectName)}\"")
                appendLine("    message: \"${escapeYaml(note.message)}\"")
                if (note.hint != null) {
                    appendLine("    hint: \"${escapeYaml(note.hint!!)}\"")
                }
            }
            appendLine()
        }

        // Skipped objects
        if (input.result.skippedObjects.isNotEmpty()) {
            appendLine("skipped_objects:")
            for (skip in input.result.skippedObjects) {
                appendLine("  - type: ${skip.type}")
                appendLine("    name: \"${escapeYaml(skip.name)}\"")
                appendLine("    reason: \"${escapeYaml(skip.reason)}\"")
                if (skip.code != null) {
                    appendLine("    code: ${skip.code}")
                }
                if (skip.hint != null) {
                    appendLine("    hint: \"${escapeYaml(skip.hint!!)}\"")
                }
            }
        }
    }

    private fun escapeYaml(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
