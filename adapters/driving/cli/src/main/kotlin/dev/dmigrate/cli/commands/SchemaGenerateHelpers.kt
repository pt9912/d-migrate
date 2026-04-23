package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import java.nio.file.Path

/**
 * Reine Helfer-Funktionen für [SchemaGenerateCommand]. Ausgelagert, damit
 * die Pfad-Berechnung und JSON-Serialisierung unit-testbar sind, ohne einen
 * Clikt-Command oder das Dateisystem zu benötigen.
 */
internal object SchemaGenerateHelpers {

    /**
     * Baut den Pfad für eine Sidecar-Datei, die neben dem eigentlichen
     * Output liegt (z.B. `schema.sql` → `schema.report.yaml`).
     *
     * - Wenn der Dateiname einen Punkt enthält, wird die Extension ersetzt.
     * - Wenn nicht (z.B. `schema`), wird das Suffix angehängt.
     * - Der Parent wird, falls vorhanden, übernommen; sonst wird ein relativer
     *   Pfad zurückgegeben.
     */
    fun sidecarPath(outputPath: Path, suffix: String): Path {
        val fileName = outputPath.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        val sidecarName = if (dotIndex > 0) {
            "${fileName.substring(0, dotIndex)}$suffix"
        } else {
            "$fileName$suffix"
        }
        return outputPath.parent?.resolve(sidecarName) ?: Path.of(sidecarName)
    }

    /**
     * Baut den Pfad für die Rollback-DDL neben dem Haupt-Output.
     * `schema.sql` → `schema.rollback.sql`, `schema` → `schema.rollback`.
     */
    fun rollbackPath(outputPath: Path): Path {
        val fileName = outputPath.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        val rollbackName = if (dotIndex > 0) {
            "${fileName.substring(0, dotIndex)}.rollback${fileName.substring(dotIndex)}"
        } else {
            "$fileName.rollback"
        }
        return outputPath.parent?.resolve(rollbackName) ?: Path.of(rollbackName)
    }

    /** Split-Dateipfad: `schema.sql` → `schema.pre-data.sql`. */
    fun splitPath(outputPath: Path, phase: DdlPhase): Path {
        val phaseSuffix = when (phase) {
            DdlPhase.PRE_DATA -> "pre-data"
            DdlPhase.POST_DATA -> "post-data"
        }
        val fileName = outputPath.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        val splitName = if (dotIndex > 0) {
            "${fileName.substring(0, dotIndex)}.$phaseSuffix${fileName.substring(dotIndex)}"
        } else {
            "$fileName.$phaseSuffix"
        }
        return outputPath.parent?.resolve(splitName) ?: Path.of(splitName)
    }

    /**
     * Serialisiert das [DdlResult] mit Schema- und Dialekt-Metadaten als
     * JSON-String. Wird von `--output-format json` verwendet.
     *
     * Implementiert wird hier ein minimaler handgeschriebener JSON-Encoder
     * (kein Jackson/DSL-JSON im CLI-Modul) — alle String-Werte werden via
     * [escapeJson] korrekt escaped.
     */
    fun formatJsonOutput(
        result: DdlResult,
        schema: SchemaDefinition,
        dialect: String,
        splitMode: SplitMode = SplitMode.SINGLE,
        mysqlNamedSequenceMode: dev.dmigrate.driver.MysqlNamedSequenceMode? = null,
    ): String {
        val isSplit = splitMode == SplitMode.PRE_POST
        val notes = result.notes.joinToString(",\n") { note ->
            renderJsonNote(note, isSplit)
        }
        val skipped = result.skippedObjects.joinToString(",\n") { s ->
            val fields = mutableListOf<String>()
            fields += """"type": "${s.type}""""
            fields += """"name": "${escapeJson(s.name)}""""
            fields += """"reason": "${escapeJson(s.reason)}""""
            if (s.code != null) fields += """"code": "${escapeJson(s.code!!)}""""
            if (s.hint != null) fields += """"hint": "${escapeJson(s.hint!!)}""""
            if (isSplit && s.phase != null) fields += """"phase": "${phaseToKebab(s.phase!!)}""""
            "    {${fields.joinToString(", ")}}"
        }
        val actionRequiredCount = result.notes.count { it.type == NoteType.ACTION_REQUIRED } +
            result.skippedObjects.count { it.code != null }
        return buildString {
            appendLine("{")
            appendLine("""  "command": "schema.generate",""")
            appendLine("""  "status": "completed",""")
            appendLine("""  "exit_code": 0,""")
            appendLine("""  "generator": "d-migrate 0.9.4",""")
            appendLine("""  "target": "$dialect",""")
            if (mysqlNamedSequenceMode != null) {
                appendLine("""  "mysql_named_sequences": "${mysqlNamedSequenceMode.cliName}",""")
            }
            appendLine("""  "schema": {"name": "${escapeJson(schema.name)}", "version": "${escapeJson(schema.version)}"},""")
            if (isSplit) {
                appendLine("""  "split_mode": "pre-post",""")
                appendLine("""  "ddl_parts": {""")
                appendLine("""    "pre_data": "${escapeJson(result.renderPhase(DdlPhase.PRE_DATA))}",""")
                appendLine("""    "post_data": "${escapeJson(result.renderPhase(DdlPhase.POST_DATA))}"""")
                appendLine("""  },""")
            } else {
                appendLine("""  "ddl": "${escapeJson(result.render())}",""")
            }
            appendLine("""  "warnings": ${result.notes.count { it.type == NoteType.WARNING }},""")
            appendLine("""  "action_required": $actionRequiredCount,""")
            if (notes.isEmpty()) appendLine("""  "notes": [],""") else {
                appendLine("""  "notes": ["""); appendLine(notes); appendLine("  ],")
            }
            if (skipped.isEmpty()) appendLine("""  "skipped_objects": []""") else {
                appendLine("""  "skipped_objects": ["""); appendLine(skipped); appendLine("  ]")
            }
            append("}")
        }
    }

    private fun renderJsonNote(note: TransformationNote, isSplit: Boolean): String {
        val phaseField = if (isSplit && note.phase != null) {
            """, "phase": "${phaseToKebab(note.phase!!)}" """
        } else {
            ""
        }
        return buildString {
            append("""    {"type": "${note.type.name.lowercase()}", """)
            append(""""code": "${note.code}", """)
            append(""""object": "${escapeJson(note.objectName)}", """)
            append(""""message": "${escapeJson(note.message)}"$phaseField}""")
        }
    }

    private fun phaseToKebab(phase: DdlPhase): String = when (phase) {
        DdlPhase.PRE_DATA -> "pre-data"
        DdlPhase.POST_DATA -> "post-data"
    }

    /**
     * JSON-String-Escaping für [formatJsonOutput] — handles backslash,
     * quote, newline, carriage return, tab.
     */
    internal fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
