package dev.dmigrate.format.report

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.NoteType
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText

class TransformationReportWriter {

    fun write(
        output: Path,
        result: DdlResult,
        schema: SchemaDefinition,
        dialect: String,
        sourceFile: Path,
        splitMode: String? = null,
        mysqlNamedSequenceMode: MysqlNamedSequenceMode? = null,
    ) {
        output.writeText(render(result, schema, dialect, sourceFile, splitMode, mysqlNamedSequenceMode))
    }

    fun render(
        result: DdlResult,
        schema: SchemaDefinition,
        dialect: String,
        sourceFile: Path,
        splitMode: String? = null,
        mysqlNamedSequenceMode: MysqlNamedSequenceMode? = null,
    ): String = buildString {
        appendLine("source:")
        appendLine("  schema: \"${escapeYaml(schema.name)}\"")
        appendLine("  version: \"${escapeYaml(schema.version)}\"")
        appendLine("  file: \"${escapeYaml(sourceFile.toString())}\"")
        appendLine("target:")
        appendLine("  dialect: $dialect")
        appendLine("  generated_at: \"${Instant.now()}\"")
        appendLine("  generator: \"d-migrate 0.9.4\"")
        if (mysqlNamedSequenceMode != null) appendLine("  mysql_named_sequences: ${mysqlNamedSequenceMode.cliName}")
        if (splitMode != null) appendLine("  split_mode: $splitMode")
        appendLine()

        val notes = result.notes
        val warnings = notes.count { it.type == NoteType.WARNING }
        val actionRequired = notes.count { it.type == NoteType.ACTION_REQUIRED } +
            result.skippedObjects.count { it.code != null }

        appendLine("summary:")
        appendLine("  statements: ${result.statements.size}")
        appendLine("  notes: ${notes.size}")
        appendLine("  warnings: $warnings")
        appendLine("  action_required: $actionRequired")
        appendLine("  skipped_objects: ${result.skippedObjects.size}")
        appendLine()

        if (notes.isNotEmpty()) {
            appendLine("notes:")
            for (note in notes) {
                appendLine("  - type: ${note.type.name.lowercase()}")
                appendLine("    code: ${note.code}")
                appendLine("    object: \"${escapeYaml(note.objectName)}\"")
                appendLine("    message: \"${escapeYaml(note.message)}\"")
                if (note.hint != null) {
                    appendLine("    hint: \"${escapeYaml(note.hint!!)}\"")
                }
                if (splitMode != null && note.phase != null) {
                    appendLine("    phase: ${phaseToKebab(note.phase!!)}")
                }
            }
            appendLine()
        }

        if (result.skippedObjects.isNotEmpty()) {
            appendLine("skipped_objects:")
            for (skip in result.skippedObjects) {
                appendLine("  - type: ${skip.type}")
                appendLine("    name: \"${escapeYaml(skip.name)}\"")
                appendLine("    reason: \"${escapeYaml(skip.reason)}\"")
                if (skip.code != null) {
                    appendLine("    code: ${skip.code}")
                }
                if (skip.hint != null) {
                    appendLine("    hint: \"${escapeYaml(skip.hint!!)}\"")
                }
                if (splitMode != null && skip.phase != null) {
                    appendLine("    phase: ${phaseToKebab(skip.phase!!)}")
                }
            }
        }
    }

    private fun escapeYaml(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    private fun phaseToKebab(phase: DdlPhase): String = when (phase) {
        DdlPhase.PRE_DATA -> "pre-data"
        DdlPhase.POST_DATA -> "post-data"
    }
}
