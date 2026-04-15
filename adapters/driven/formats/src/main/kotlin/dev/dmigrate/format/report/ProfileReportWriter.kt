package dev.dmigrate.format.report

import dev.dmigrate.profiling.model.ColumnProfile
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.profiling.model.TableProfile
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Serializes a [DatabaseProfile] to JSON or YAML.
 *
 * Both formats transport the same information — differences are only syntactic.
 * The output is deterministic: no runtime timestamps, stable ordering.
 */
class ProfileReportWriter {

    fun write(profile: DatabaseProfile, format: String, output: Path?) {
        val content = when (format) {
            "yaml" -> renderYaml(profile)
            else -> renderJson(profile)
        }
        if (output != null) {
            output.parent?.toFile()?.mkdirs()
            output.writeText(content)
        } else {
            print(content)
        }
    }

    fun renderJson(profile: DatabaseProfile): String = buildString {
        appendLine("{")
        appendLine("""  "databaseProduct": ${jsonStr(profile.databaseProduct)},""")
        if (profile.databaseVersion != null) appendLine("""  "databaseVersion": ${jsonStr(profile.databaseVersion)},""")
        if (profile.schemaName != null) appendLine("""  "schemaName": ${jsonStr(profile.schemaName)},""")
        appendLine("""  "tables": [""")
        profile.tables.forEachIndexed { i, table ->
            append(renderTableJson(table, "    "))
            if (i < profile.tables.size - 1) appendLine(",") else appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    fun renderYaml(profile: DatabaseProfile): String = buildString {
        appendLine("databaseProduct: ${yamlStr(profile.databaseProduct)}")
        if (profile.databaseVersion != null) appendLine("databaseVersion: ${yamlStr(profile.databaseVersion)}")
        if (profile.schemaName != null) appendLine("schemaName: ${yamlStr(profile.schemaName)}")
        appendLine("tables:")
        for (table in profile.tables) {
            append(renderTableYaml(table, "  "))
        }
    }

    private fun renderTableJson(table: TableProfile, indent: String): String = buildString {
        appendLine("$indent{")
        appendLine("""$indent  "name": ${jsonStr(table.name)},""")
        appendLine("""$indent  "rowCount": ${table.rowCount},""")
        appendLine("""$indent  "columns": [""")
        table.columns.forEachIndexed { i, col ->
            append(renderColumnJson(col, "$indent    "))
            if (i < table.columns.size - 1) appendLine(",") else appendLine()
        }
        appendLine("$indent  ],")
        appendLine("""$indent  "warnings": [${table.warnings.joinToString(", ") { """{"code": "${it.code}", "severity": "${it.severity}", "message": ${jsonStr(it.message)}}""" }}]""")
        append("$indent}")
    }

    private fun renderColumnJson(col: ColumnProfile, indent: String): String = buildString {
        appendLine("$indent{")
        appendLine("""$indent  "name": ${jsonStr(col.name)},""")
        appendLine("""$indent  "dbType": ${jsonStr(col.dbType)},""")
        appendLine("""$indent  "logicalType": "${col.logicalType}",""")
        appendLine("""$indent  "nullable": ${col.nullable},""")
        appendLine("""$indent  "rowCount": ${col.rowCount},""")
        appendLine("""$indent  "nonNullCount": ${col.nonNullCount},""")
        appendLine("""$indent  "nullCount": ${col.nullCount},""")
        appendLine("""$indent  "distinctCount": ${col.distinctCount},""")
        appendLine("""$indent  "duplicateValueCount": ${col.duplicateValueCount},""")
        if (col.emptyStringCount > 0) appendLine("""$indent  "emptyStringCount": ${col.emptyStringCount},""")
        if (col.blankStringCount > 0) appendLine("""$indent  "blankStringCount": ${col.blankStringCount},""")
        if (col.minLength != null) appendLine("""$indent  "minLength": ${col.minLength},""")
        if (col.maxLength != null) appendLine("""$indent  "maxLength": ${col.maxLength},""")
        if (col.minValue != null) appendLine("""$indent  "minValue": ${jsonStr(col.minValue)},""")
        if (col.maxValue != null) appendLine("""$indent  "maxValue": ${jsonStr(col.maxValue)},""")
        if (col.topValues.isNotEmpty()) {
            appendLine("""$indent  "topValues": [""")
            col.topValues.forEachIndexed { i, v ->
                append("""$indent    {"value": ${if (v.value != null) jsonStr(v.value) else "null"}, "count": ${v.count}, "ratio": ${v.ratio}}""")
                if (i < col.topValues.size - 1) appendLine(",") else appendLine()
            }
            appendLine("$indent  ],")
        }
        col.numericStats?.let { s ->
            appendLine("""$indent  "numericStats": {"min": ${s.min}, "max": ${s.max}, "avg": ${s.avg}, "sum": ${s.sum}, "stddev": ${s.stddev}, "zeroCount": ${s.zeroCount}, "negativeCount": ${s.negativeCount}},""")
        }
        col.temporalStats?.let { s ->
            appendLine("""$indent  "temporalStats": {"minTimestamp": ${jsonStr(s.minTimestamp)}, "maxTimestamp": ${jsonStr(s.maxTimestamp)}},""")
        }
        if (col.targetCompatibility.isNotEmpty()) {
            appendLine("""$indent  "targetCompatibility": [""")
            col.targetCompatibility.forEachIndexed { i, c ->
                append("""$indent    {"targetType": "${c.targetType}", "checkedValueCount": ${c.checkedValueCount}, "compatibleCount": ${c.compatibleCount}, "incompatibleCount": ${c.incompatibleCount}, "determinationStatus": "${c.determinationStatus}"}""")
                if (i < col.targetCompatibility.size - 1) appendLine(",") else appendLine()
            }
            appendLine("$indent  ],")
        }
        appendLine("""$indent  "warnings": [${col.warnings.joinToString(", ") { """{"code": "${it.code}", "severity": "${it.severity}", "message": ${jsonStr(it.message)}}""" }}]""")
        append("$indent}")
    }

    private fun renderTableYaml(table: TableProfile, indent: String): String = buildString {
        appendLine("${indent}- name: ${yamlStr(table.name)}")
        appendLine("$indent  rowCount: ${table.rowCount}")
        appendLine("$indent  columns:")
        for (col in table.columns) {
            append(renderColumnYaml(col, "$indent    "))
        }
        if (table.warnings.isNotEmpty()) {
            appendLine("$indent  warnings:")
            for (w in table.warnings) {
                appendLine("$indent    - code: ${w.code}")
                appendLine("$indent      severity: ${w.severity}")
                appendLine("$indent      message: ${yamlStr(w.message)}")
            }
        }
    }

    private fun renderColumnYaml(col: ColumnProfile, indent: String): String = buildString {
        appendLine("${indent}- name: ${yamlStr(col.name)}")
        appendLine("$indent  dbType: ${yamlStr(col.dbType)}")
        appendLine("$indent  logicalType: ${col.logicalType}")
        appendLine("$indent  nullable: ${col.nullable}")
        appendLine("$indent  rowCount: ${col.rowCount}")
        appendLine("$indent  nonNullCount: ${col.nonNullCount}")
        appendLine("$indent  nullCount: ${col.nullCount}")
        appendLine("$indent  distinctCount: ${col.distinctCount}")
        appendLine("$indent  duplicateValueCount: ${col.duplicateValueCount}")
        if (col.emptyStringCount > 0) appendLine("$indent  emptyStringCount: ${col.emptyStringCount}")
        if (col.blankStringCount > 0) appendLine("$indent  blankStringCount: ${col.blankStringCount}")
        if (col.minValue != null) appendLine("$indent  minValue: ${yamlStr(col.minValue)}")
        if (col.maxValue != null) appendLine("$indent  maxValue: ${yamlStr(col.maxValue)}")
        if (col.topValues.isNotEmpty()) {
            appendLine("$indent  topValues:")
            for (v in col.topValues) {
                appendLine("$indent    - value: ${if (v.value != null) yamlStr(v.value) else "null"}")
                appendLine("$indent      count: ${v.count}")
                appendLine("$indent      ratio: ${v.ratio}")
            }
        }
        if (col.warnings.isNotEmpty()) {
            appendLine("$indent  warnings:")
            for (w in col.warnings) {
                appendLine("$indent    - code: ${w.code}")
                appendLine("$indent      severity: ${w.severity}")
                appendLine("$indent      message: ${yamlStr(w.message)}")
            }
        }
    }

    private fun jsonStr(s: String?): String {
        if (s == null) return "null"
        return "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
    }

    private fun yamlStr(s: String?): String {
        if (s == null) return "null"
        return if (s.contains(":") || s.contains("#") || s.contains("\"") || s.contains("'") || s.contains("\n")) {
            "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        } else s
    }
}
