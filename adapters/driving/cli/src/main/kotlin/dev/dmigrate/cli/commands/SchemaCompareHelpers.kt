package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.*
import dev.dmigrate.core.model.*
import dev.dmigrate.core.validation.ValidationResult

internal object SchemaCompareHelpers {

    // ── Canonical string representations ──────────────────────────

    fun neutralTypeToString(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> if (type.autoIncrement) "identifier(auto)" else "identifier"
        is NeutralType.Text -> if (type.maxLength != null) "text(${type.maxLength})" else "text"
        is NeutralType.Char -> "char(${type.length})"
        is NeutralType.Integer -> "integer"
        is NeutralType.SmallInt -> "smallint"
        is NeutralType.BigInteger -> "biginteger"
        is NeutralType.Float -> "float(${type.floatPrecision.name.lowercase()})"
        is NeutralType.Decimal -> "decimal(${type.precision},${type.scale})"
        is NeutralType.BooleanType -> "boolean"
        is NeutralType.DateTime -> if (type.timezone) "datetime(tz)" else "datetime"
        is NeutralType.Date -> "date"
        is NeutralType.Time -> "time"
        is NeutralType.Uuid -> "uuid"
        is NeutralType.Json -> "json"
        is NeutralType.Xml -> "xml"
        is NeutralType.Binary -> "binary"
        is NeutralType.Email -> "email"
        is NeutralType.Enum -> when {
            type.refType != null -> "enum(ref:${type.refType})"
            type.values != null -> "enum(${type.values!!.joinToString(",")})"
            else -> "enum"
        }
        is NeutralType.Array -> "array(${type.elementType})"
    }

    fun defaultValueToString(dv: DefaultValue?): String = when (dv) {
        null -> "null"
        is DefaultValue.StringLiteral -> "\"${dv.value}\""
        is DefaultValue.NumberLiteral -> dv.value.toString()
        is DefaultValue.BooleanLiteral -> dv.value.toString()
        is DefaultValue.FunctionCall -> "${dv.name}()"
    }

    fun indexSignature(idx: IndexDefinition): String = buildString {
        if (idx.name != null) append(idx.name) else append(idx.columns.joinToString(","))
        append(" [${idx.type.name.lowercase()}")
        if (idx.unique) append(",unique")
        append("]")
    }

    fun constraintSignature(c: ConstraintDefinition): String = buildString {
        append("${c.name} (${c.type.name.lowercase()}")
        val cols = c.columns
        if (!cols.isNullOrEmpty()) append(" on [${cols.joinToString(",")}]")
        val refs = c.references
        if (refs != null) append(" -> ${refs.table}[${refs.columns.joinToString(",")}]")
        append(")")
    }

    // ── Plain-Text Renderer ───────────────────────────────────────

    fun renderPlain(doc: SchemaCompareDocument): String = buildString {
        appendLine("Schema Compare: ${doc.source} <-> ${doc.target}")
        appendLine()

        if (doc.status == "invalid") {
            appendLine("Status: INVALID")
            doc.validation?.let { v ->
                v.source?.let { renderValidationPlain(this, it, "source") }
                v.target?.let { renderValidationPlain(this, it, "target") }
            }
            return@buildString
        }

        if (doc.status == "identical") {
            appendLine("Status: IDENTICAL")
            appendLine("No differences found.")
            return@buildString
        }

        appendLine("Status: DIFFERENT")
        appendLine()
        renderSummaryPlain(this, doc.summary)

        doc.diff?.let { diff ->
            val metadata = diff.schemaMetadata
            if (metadata != null) {
                appendLine()
                appendLine("Schema Metadata:")
                metadata.name?.let {
                    appendLine("  name: ${it.before} -> ${it.after}")
                }
                metadata.version?.let {
                    appendLine("  version: ${it.before} -> ${it.after}")
                }
            }

            if (diff.enumTypesAdded.isNotEmpty() || diff.enumTypesRemoved.isNotEmpty() ||
                diff.enumTypesChanged.isNotEmpty()) {
                appendLine()
                appendLine("Enum Types:")
                for (e in diff.enumTypesAdded) appendLine("  + ${e.name}: [${e.definition.values.orEmpty().joinToString(", ")}]")
                for (e in diff.enumTypesRemoved) appendLine("  - ${e.name}: [${e.definition.values.orEmpty().joinToString(", ")}]")
                for (e in diff.enumTypesChanged) {
                    appendLine("  ~ ${e.name}:")
                    appendLine("      before: [${e.values.before.joinToString(", ")}]")
                    appendLine("      after:  [${e.values.after.joinToString(", ")}]")
                }
            }

            if (diff.tablesAdded.isNotEmpty() || diff.tablesRemoved.isNotEmpty() ||
                diff.tablesChanged.isNotEmpty()) {
                appendLine()
                appendLine("Tables:")
                for (t in diff.tablesAdded) appendLine("  + ${t.name} (${t.definition.columns.size} columns)")
                for (t in diff.tablesRemoved) appendLine("  - ${t.name} (${t.definition.columns.size} columns)")
                for (t in diff.tablesChanged) {
                    appendLine("  ~ ${t.name}:")
                    renderTableDiffPlain(this, t)
                }
            }

            if (diff.viewsAdded.isNotEmpty() || diff.viewsRemoved.isNotEmpty() ||
                diff.viewsChanged.isNotEmpty()) {
                appendLine()
                appendLine("Views:")
                for (v in diff.viewsAdded) appendLine("  + ${v.name}${if (v.definition.materialized) " (materialized)" else ""}")
                for (v in diff.viewsRemoved) appendLine("  - ${v.name}${if (v.definition.materialized) " (materialized)" else ""}")
                for (v in diff.viewsChanged) {
                    appendLine("  ~ ${v.name}:")
                    v.materialized?.let { appendLine("      materialized: ${it.before} -> ${it.after}") }
                    v.refresh?.let { appendLine("      refresh: ${it.before} -> ${it.after}") }
                    v.query?.let { appendLine("      query: changed") }
                    v.sourceDialect?.let { appendLine("      source_dialect: ${it.before} -> ${it.after}") }
                }
            }
        }
    }.trimEnd()

    private fun renderSummaryPlain(sb: StringBuilder, s: SchemaCompareSummary) {
        sb.appendLine("Summary: ${s.totalChanges} change(s)")
        if (s.tablesAdded > 0) sb.appendLine("  Tables added:    ${s.tablesAdded}")
        if (s.tablesRemoved > 0) sb.appendLine("  Tables removed:  ${s.tablesRemoved}")
        if (s.tablesChanged > 0) sb.appendLine("  Tables changed:  ${s.tablesChanged}")
        if (s.enumTypesAdded > 0) sb.appendLine("  Enums added:     ${s.enumTypesAdded}")
        if (s.enumTypesRemoved > 0) sb.appendLine("  Enums removed:   ${s.enumTypesRemoved}")
        if (s.enumTypesChanged > 0) sb.appendLine("  Enums changed:   ${s.enumTypesChanged}")
        if (s.viewsAdded > 0) sb.appendLine("  Views added:     ${s.viewsAdded}")
        if (s.viewsRemoved > 0) sb.appendLine("  Views removed:   ${s.viewsRemoved}")
        if (s.viewsChanged > 0) sb.appendLine("  Views changed:   ${s.viewsChanged}")
    }

    private fun renderTableDiffPlain(sb: StringBuilder, t: TableDiff) {
        for ((name, col) in t.columnsAdded) {
            sb.appendLine("      + column $name: ${neutralTypeToString(col.type)}")
        }
        for ((name, _) in t.columnsRemoved) {
            sb.appendLine("      - column $name")
        }
        for (c in t.columnsChanged) {
            sb.appendLine("      ~ column ${c.name}:")
            c.type?.let { sb.appendLine("          type: ${neutralTypeToString(it.before)} -> ${neutralTypeToString(it.after)}") }
            c.required?.let { sb.appendLine("          required: ${it.before} -> ${it.after}") }
            c.default?.let { sb.appendLine("          default: ${defaultValueToString(it.before)} -> ${defaultValueToString(it.after)}") }
        }
        t.primaryKey?.let {
            sb.appendLine("      ~ primary_key: [${it.before.joinToString(", ")}] -> [${it.after.joinToString(", ")}]")
        }
        for (idx in t.indicesAdded) sb.appendLine("      + index ${indexSignature(idx)}")
        for (idx in t.indicesRemoved) sb.appendLine("      - index ${indexSignature(idx)}")
        for (ch in t.indicesChanged) {
            sb.appendLine("      ~ index ${indexSignature(ch.before)} -> ${indexSignature(ch.after)}")
        }
        for (c in t.constraintsAdded) sb.appendLine("      + constraint ${constraintSignature(c)}")
        for (c in t.constraintsRemoved) sb.appendLine("      - constraint ${constraintSignature(c)}")
        for (ch in t.constraintsChanged) {
            sb.appendLine("      ~ constraint ${constraintSignature(ch.before)} -> ${constraintSignature(ch.after)}")
        }
    }

    private fun renderValidationPlain(sb: StringBuilder, result: ValidationResult, side: String) {
        if (result.errors.isNotEmpty()) {
            for (e in result.errors) {
                sb.appendLine("  Error [$side] [${e.code}]: ${e.message}")
            }
        }
        if (result.warnings.isNotEmpty()) {
            for (w in result.warnings) {
                sb.appendLine("  Warning [$side] [${w.code}]: ${w.message}")
            }
        }
    }

    // ── JSON Renderer ─────────────────────────────────────────────

    fun renderJson(doc: SchemaCompareDocument): String = buildString {
        appendLine("{")
        appendLine("""  "command": "schema.compare",""")
        appendLine("""  "status": "${doc.status}",""")
        appendLine("""  "exit_code": ${doc.exitCode},""")
        appendLine("""  "source": "${esc(doc.source)}",""")
        appendLine("""  "target": "${esc(doc.target)}",""")
        renderSummaryJson(this, doc.summary)

        val jsonValidation = doc.validation
        if (jsonValidation != null) {
            appendLine("""  "validation": {""")
            renderValidationJson(this, jsonValidation)
            appendLine("  },")
        }

        val jsonDiff = doc.diff
        if (jsonDiff != null) {
            appendLine("""  "diff": {""")
            renderDiffJson(this, jsonDiff)
            appendLine("  }")
        } else {
            appendLine("""  "diff": null""")
        }
        append("}")
    }

    private fun renderSummaryJson(sb: StringBuilder, s: SchemaCompareSummary) {
        sb.appendLine("""  "summary": {""")
        sb.appendLine("""    "tables_added": ${s.tablesAdded},""")
        sb.appendLine("""    "tables_removed": ${s.tablesRemoved},""")
        sb.appendLine("""    "tables_changed": ${s.tablesChanged},""")
        sb.appendLine("""    "enum_types_added": ${s.enumTypesAdded},""")
        sb.appendLine("""    "enum_types_removed": ${s.enumTypesRemoved},""")
        sb.appendLine("""    "enum_types_changed": ${s.enumTypesChanged},""")
        sb.appendLine("""    "views_added": ${s.viewsAdded},""")
        sb.appendLine("""    "views_removed": ${s.viewsRemoved},""")
        sb.appendLine("""    "views_changed": ${s.viewsChanged}""")
        sb.appendLine("  },")
    }

    private fun renderDiffJson(sb: StringBuilder, diff: SchemaDiff) {
        val parts = mutableListOf<String>()

        val jsonMeta = diff.schemaMetadata
        if (jsonMeta != null) {
            parts += buildString {
                append("""    "schema_metadata": {""")
                val fields = mutableListOf<String>()
                jsonMeta.name?.let { fields += """"name": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
                jsonMeta.version?.let { fields += """"version": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
                append(fields.joinToString(", "))
                append("}")
            }
        }

        if (diff.enumTypesAdded.isNotEmpty()) {
            parts += """    "enum_types_added": [${diff.enumTypesAdded.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        }
        if (diff.enumTypesRemoved.isNotEmpty()) {
            parts += """    "enum_types_removed": [${diff.enumTypesRemoved.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        }
        if (diff.enumTypesChanged.isNotEmpty()) {
            val items = diff.enumTypesChanged.joinToString(", ") { e ->
                """{"name": "${esc(e.name)}", "before": [${e.values.before.joinToString(", ") { "\"${esc(it)}\"" }}], "after": [${e.values.after.joinToString(", ") { "\"${esc(it)}\"" }}]}"""
            }
            parts += """    "enum_types_changed": [$items]"""
        }

        if (diff.tablesAdded.isNotEmpty()) {
            parts += """    "tables_added": [${diff.tablesAdded.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        }
        if (diff.tablesRemoved.isNotEmpty()) {
            parts += """    "tables_removed": [${diff.tablesRemoved.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        }
        if (diff.tablesChanged.isNotEmpty()) {
            val items = diff.tablesChanged.joinToString(",\n") { t -> renderTableDiffJson(t) }
            parts += "    \"tables_changed\": [\n$items\n    ]"
        }

        if (diff.viewsAdded.isNotEmpty()) {
            parts += """    "views_added": [${diff.viewsAdded.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        }
        if (diff.viewsRemoved.isNotEmpty()) {
            parts += """    "views_removed": [${diff.viewsRemoved.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        }
        if (diff.viewsChanged.isNotEmpty()) {
            val items = diff.viewsChanged.joinToString(", ") { v ->
                val fields = mutableListOf<String>()
                fields += """"name": "${esc(v.name)}""""
                v.materialized?.let { fields += """"materialized": {"before": ${it.before}, "after": ${it.after}}""" }
                v.query?.let { fields += """"query": {"before": ${jsonStringOrNull(it.before)}, "after": ${jsonStringOrNull(it.after)}}""" }
                "{${fields.joinToString(", ")}}"
            }
            parts += """    "views_changed": [$items]"""
        }

        sb.appendLine(parts.joinToString(",\n"))
    }

    private fun renderTableDiffJson(t: TableDiff): String = buildString {
        append("      {\"name\": \"${esc(t.name)}\"")

        if (t.columnsAdded.isNotEmpty()) {
            val cols = t.columnsAdded.entries.joinToString(", ") { (n, c) ->
                """{"name": "${esc(n)}", "type": "${esc(neutralTypeToString(c.type))}"}"""
            }
            append(""", "columns_added": [$cols]""")
        }
        if (t.columnsRemoved.isNotEmpty()) {
            append(""", "columns_removed": [${t.columnsRemoved.keys.joinToString(", ") { "\"${esc(it)}\"" }}]""")
        }
        if (t.columnsChanged.isNotEmpty()) {
            val cols = t.columnsChanged.joinToString(", ") { c ->
                val fields = mutableListOf(""""name": "${esc(c.name)}"""")
                c.type?.let { fields += """"type": {"before": "${esc(neutralTypeToString(it.before))}", "after": "${esc(neutralTypeToString(it.after))}"}""" }
                c.required?.let { fields += """"required": {"before": ${it.before}, "after": ${it.after}}""" }
                c.default?.let { fields += """"default": {"before": "${esc(defaultValueToString(it.before))}", "after": "${esc(defaultValueToString(it.after))}"}""" }
                "{${fields.joinToString(", ")}}"
            }
            append(""", "columns_changed": [$cols]""")
        }
        t.primaryKey?.let {
            append(""", "primary_key": {"before": [${it.before.joinToString(", ") { s -> "\"${esc(s)}\"" }}], "after": [${it.after.joinToString(", ") { s -> "\"${esc(s)}\"" }}]}""")
        }

        append("}")
    }

    private fun renderValidationJson(sb: StringBuilder, v: CompareValidation) {
        val sides = mutableListOf<String>()
        v.source?.let { sides += renderValidationSideJson("source", it) }
        v.target?.let { sides += renderValidationSideJson("target", it) }
        sb.appendLine(sides.joinToString(",\n"))
    }

    private fun renderValidationSideJson(side: String, result: ValidationResult): String {
        val items = mutableListOf<String>()
        for (e in result.errors) {
            items += """{"level": "error", "code": "${e.code}", "message": "${esc(e.message)}"}"""
        }
        for (w in result.warnings) {
            items += """{"level": "warning", "code": "${w.code}", "message": "${esc(w.message)}"}"""
        }
        return """    "$side": [${items.joinToString(", ")}]"""
    }

    // ── YAML Renderer ─────────────────────────────────────────────

    fun renderYaml(doc: SchemaCompareDocument): String = buildString {
        appendLine("command: schema.compare")
        appendLine("status: ${doc.status}")
        appendLine("exit_code: ${doc.exitCode}")
        appendLine("source: \"${escYaml(doc.source)}\"")
        appendLine("target: \"${escYaml(doc.target)}\"")
        renderSummaryYaml(this, doc.summary)

        val yamlValidation = doc.validation
        if (yamlValidation != null) {
            appendLine("validation:")
            renderValidationYaml(this, yamlValidation)
        }

        val yamlDiff = doc.diff
        if (yamlDiff != null) {
            appendLine("diff:")
            renderDiffYaml(this, yamlDiff)
        } else {
            appendLine("diff: null")
        }
    }.trimEnd()

    private fun renderSummaryYaml(sb: StringBuilder, s: SchemaCompareSummary) {
        sb.appendLine("summary:")
        sb.appendLine("  tables_added: ${s.tablesAdded}")
        sb.appendLine("  tables_removed: ${s.tablesRemoved}")
        sb.appendLine("  tables_changed: ${s.tablesChanged}")
        sb.appendLine("  enum_types_added: ${s.enumTypesAdded}")
        sb.appendLine("  enum_types_removed: ${s.enumTypesRemoved}")
        sb.appendLine("  enum_types_changed: ${s.enumTypesChanged}")
        sb.appendLine("  views_added: ${s.viewsAdded}")
        sb.appendLine("  views_removed: ${s.viewsRemoved}")
        sb.appendLine("  views_changed: ${s.viewsChanged}")
    }

    private fun renderDiffYaml(sb: StringBuilder, diff: SchemaDiff) {
        val yamlMeta = diff.schemaMetadata
        if (yamlMeta != null) {
            sb.appendLine("  schema_metadata:")
            yamlMeta.name?.let { sb.appendLine("    name: {before: \"${escYaml(it.before)}\", after: \"${escYaml(it.after)}\"}") }
            yamlMeta.version?.let { sb.appendLine("    version: {before: \"${escYaml(it.before)}\", after: \"${escYaml(it.after)}\"}") }
        }

        if (diff.enumTypesAdded.isNotEmpty()) {
            sb.appendLine("  enum_types_added:")
            for (e in diff.enumTypesAdded) sb.appendLine("    - ${e.name}")
        }
        if (diff.enumTypesRemoved.isNotEmpty()) {
            sb.appendLine("  enum_types_removed:")
            for (e in diff.enumTypesRemoved) sb.appendLine("    - ${e.name}")
        }
        if (diff.enumTypesChanged.isNotEmpty()) {
            sb.appendLine("  enum_types_changed:")
            for (e in diff.enumTypesChanged) {
                sb.appendLine("    - name: ${e.name}")
                sb.appendLine("      before: [${e.values.before.joinToString(", ")}]")
                sb.appendLine("      after: [${e.values.after.joinToString(", ")}]")
            }
        }

        if (diff.tablesAdded.isNotEmpty()) {
            sb.appendLine("  tables_added:")
            for (t in diff.tablesAdded) sb.appendLine("    - ${t.name}")
        }
        if (diff.tablesRemoved.isNotEmpty()) {
            sb.appendLine("  tables_removed:")
            for (t in diff.tablesRemoved) sb.appendLine("    - ${t.name}")
        }
        if (diff.tablesChanged.isNotEmpty()) {
            sb.appendLine("  tables_changed:")
            for (t in diff.tablesChanged) renderTableDiffYaml(sb, t)
        }

        if (diff.viewsAdded.isNotEmpty()) {
            sb.appendLine("  views_added:")
            for (v in diff.viewsAdded) sb.appendLine("    - ${v.name}")
        }
        if (diff.viewsRemoved.isNotEmpty()) {
            sb.appendLine("  views_removed:")
            for (v in diff.viewsRemoved) sb.appendLine("    - ${v.name}")
        }
        if (diff.viewsChanged.isNotEmpty()) {
            sb.appendLine("  views_changed:")
            for (v in diff.viewsChanged) {
                sb.appendLine("    - name: ${v.name}")
                v.materialized?.let { sb.appendLine("      materialized: {before: ${it.before}, after: ${it.after}}") }
                v.query?.let { sb.appendLine("      query: changed") }
            }
        }
    }

    private fun renderTableDiffYaml(sb: StringBuilder, t: TableDiff) {
        sb.appendLine("    - name: ${t.name}")
        if (t.columnsAdded.isNotEmpty()) {
            sb.appendLine("      columns_added:")
            for ((n, c) in t.columnsAdded) sb.appendLine("        - {name: $n, type: ${neutralTypeToString(c.type)}}")
        }
        if (t.columnsRemoved.isNotEmpty()) {
            sb.appendLine("      columns_removed:")
            for (n in t.columnsRemoved.keys) sb.appendLine("        - $n")
        }
        if (t.columnsChanged.isNotEmpty()) {
            sb.appendLine("      columns_changed:")
            for (c in t.columnsChanged) {
                sb.appendLine("        - name: ${c.name}")
                c.type?.let { sb.appendLine("          type: {before: ${neutralTypeToString(it.before)}, after: ${neutralTypeToString(it.after)}}") }
                c.required?.let { sb.appendLine("          required: {before: ${it.before}, after: ${it.after}}") }
                c.default?.let { sb.appendLine("          default: {before: ${defaultValueToString(it.before)}, after: ${defaultValueToString(it.after)}}") }
            }
        }
        t.primaryKey?.let {
            sb.appendLine("      primary_key: {before: [${it.before.joinToString(", ")}], after: [${it.after.joinToString(", ")}]}")
        }
    }

    private fun renderValidationYaml(sb: StringBuilder, v: CompareValidation) {
        v.source?.let { renderValidationSideYaml(sb, "source", it) }
        v.target?.let { renderValidationSideYaml(sb, "target", it) }
    }

    private fun renderValidationSideYaml(sb: StringBuilder, side: String, result: ValidationResult) {
        if (result.errors.isEmpty() && result.warnings.isEmpty()) {
            sb.appendLine("  $side: []")
            return
        }
        sb.appendLine("  $side:")
        for (e in result.errors) {
            sb.appendLine("    - level: error")
            sb.appendLine("      code: ${e.code}")
            sb.appendLine("      message: \"${escYaml(e.message)}\"")
        }
        for (w in result.warnings) {
            sb.appendLine("    - level: warning")
            sb.appendLine("      code: ${w.code}")
            sb.appendLine("      message: \"${escYaml(w.message)}\"")
        }
    }

    // ── Escaping ──────────────────────────────────────────────────

    private fun esc(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun escYaml(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    private fun jsonStringOrNull(s: String?): String =
        if (s == null) "null" else "\"${esc(s)}\""
}
