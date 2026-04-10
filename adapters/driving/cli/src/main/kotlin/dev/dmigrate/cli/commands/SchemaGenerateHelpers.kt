package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import java.nio.file.Path

/**
 * Reine Helfer-Funktionen für [SchemaGenerateCommand]. Ausgelagert, damit
 * die Pfad-Berechnung, JSON-Serialisierung und Dialect→Generator-Auflösung
 * unit-testbar sind, ohne einen Clikt-Command oder das Dateisystem zu
 * benötigen.
 */
internal object SchemaGenerateHelpers {

    /**
     * Wählt den konkreten [DdlGenerator] für den gegebenen [DatabaseDialect].
     * Separate Helper-Funktion, damit das Routing auch ohne Clikt-Command
     * testbar ist.
     */
    fun getGenerator(dialect: DatabaseDialect): DdlGenerator = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgresDdlGenerator()
        DatabaseDialect.MYSQL -> MysqlDdlGenerator()
        DatabaseDialect.SQLITE -> SqliteDdlGenerator()
    }

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

    /**
     * Serialisiert das [DdlResult] mit Schema- und Dialekt-Metadaten als
     * JSON-String. Wird von `--output-format json` verwendet.
     *
     * Implementiert wird hier ein minimaler handgeschriebener JSON-Encoder
     * (kein Jackson/DSL-JSON im CLI-Modul) — alle String-Werte werden via
     * [escapeJson] korrekt escaped.
     */
    fun formatJsonOutput(result: DdlResult, schema: SchemaDefinition, dialect: String): String {
        val notes = result.notes.joinToString(",\n") { n ->
            """    {"type": "${n.type.name.lowercase()}", "code": "${n.code}", "object": "${escapeJson(n.objectName)}", "message": "${escapeJson(n.message)}"}"""
        }
        val skipped = result.skippedObjects.joinToString(",\n") { s ->
            """    {"type": "${s.type}", "name": "${escapeJson(s.name)}", "reason": "${escapeJson(s.reason)}"}"""
        }
        return buildString {
            appendLine("{")
            appendLine("""  "command": "schema.generate",""")
            appendLine("""  "status": "completed",""")
            appendLine("""  "exit_code": 0,""")
            appendLine("""  "target": "$dialect",""")
            appendLine("""  "schema": {"name": "${escapeJson(schema.name)}", "version": "${escapeJson(schema.version)}"},""")
            appendLine("""  "ddl": "${escapeJson(result.render())}",""")
            appendLine("""  "warnings": ${result.notes.count { it.type == NoteType.WARNING }},""")
            appendLine("""  "action_required": ${result.notes.count { it.type == NoteType.ACTION_REQUIRED }},""")
            if (notes.isEmpty()) appendLine("""  "notes": [],""") else {
                appendLine("""  "notes": ["""); appendLine(notes); appendLine("  ],")
            }
            if (skipped.isEmpty()) appendLine("""  "skipped_objects": []""") else {
                appendLine("""  "skipped_objects": ["""); appendLine(skipped); appendLine("  ]")
            }
            append("}")
        }
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
