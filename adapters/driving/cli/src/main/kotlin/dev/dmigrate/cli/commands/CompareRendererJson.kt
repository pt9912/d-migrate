package dev.dmigrate.cli.commands

import dev.dmigrate.core.validation.ValidationResult

/**
 * JSON renderer for [SchemaCompareDocument].
 */
internal object CompareRendererJson {

    fun render(doc: SchemaCompareDocument): String = buildString {
        appendLine("{")
        appendLine("""  "command": "schema.compare",""")
        appendLine("""  "status": "${doc.status}",""")
        appendLine("""  "exit_code": ${doc.exitCode},""")
        appendLine("""  "source": "${esc(doc.source)}",""")
        appendLine("""  "target": "${esc(doc.target)}",""")
        renderSummary(this, doc.summary)

        val jv = doc.validation
        if (jv != null) {
            appendLine("""  "validation": {""")
            renderValidation(this, jv)
            appendLine("  },")
        }

        renderOperandIfPresent(this, "source_operand", doc.sourceOperand)
        renderOperandIfPresent(this, "target_operand", doc.targetOperand)

        val jd = doc.diff
        if (jd != null) {
            appendLine("""  "diff": {""")
            renderDiff(this, jd)
            appendLine("  }")
        } else {
            appendLine("""  "diff": null""")
        }
        append("}")
    }

    private fun renderSummary(sb: StringBuilder, s: SchemaCompareSummary) {
        sb.appendLine("""  "summary": {""")
        sb.appendLine("""    "tables_added": ${s.tablesAdded},""")
        sb.appendLine("""    "tables_removed": ${s.tablesRemoved},""")
        sb.appendLine("""    "tables_changed": ${s.tablesChanged},""")
        sb.appendLine("""    "custom_types_added": ${s.customTypesAdded},""")
        sb.appendLine("""    "custom_types_removed": ${s.customTypesRemoved},""")
        sb.appendLine("""    "custom_types_changed": ${s.customTypesChanged},""")
        sb.appendLine("""    "views_added": ${s.viewsAdded},""")
        sb.appendLine("""    "views_removed": ${s.viewsRemoved},""")
        sb.appendLine("""    "views_changed": ${s.viewsChanged},""")
        sb.appendLine("""    "sequences_added": ${s.sequencesAdded},""")
        sb.appendLine("""    "sequences_removed": ${s.sequencesRemoved},""")
        sb.appendLine("""    "sequences_changed": ${s.sequencesChanged},""")
        sb.appendLine("""    "functions_added": ${s.functionsAdded},""")
        sb.appendLine("""    "functions_removed": ${s.functionsRemoved},""")
        sb.appendLine("""    "functions_changed": ${s.functionsChanged},""")
        sb.appendLine("""    "procedures_added": ${s.proceduresAdded},""")
        sb.appendLine("""    "procedures_removed": ${s.proceduresRemoved},""")
        sb.appendLine("""    "procedures_changed": ${s.proceduresChanged},""")
        sb.appendLine("""    "triggers_added": ${s.triggersAdded},""")
        sb.appendLine("""    "triggers_removed": ${s.triggersRemoved},""")
        sb.appendLine("""    "triggers_changed": ${s.triggersChanged}""")
        sb.appendLine("  },")
    }

    private fun renderDiff(sb: StringBuilder, diff: DiffView) {
        val parts = mutableListOf<String>()
        fun renderNamedKindEntry(name: String, kind: String): String =
            """{"name": "${esc(name)}", "kind": "${esc(kind)}"}"""

        val m = diff.schemaMetadata
        if (m != null) {
            val fields = mutableListOf<String>()
            m.name?.let { fields += """"name": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
            m.version?.let { fields += """"version": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
            parts += """    "schema_metadata": {${fields.joinToString(", ")}}"""
        }

        if (diff.customTypesAdded.isNotEmpty()) {
            val items = diff.customTypesAdded.joinToString(", ") {
                renderNamedKindEntry(it.name, it.kind)
            }
            parts += """    "custom_types_added": [$items]"""
        }
        if (diff.customTypesRemoved.isNotEmpty()) {
            val items = diff.customTypesRemoved.joinToString(", ") {
                renderNamedKindEntry(it.name, it.kind)
            }
            parts += """    "custom_types_removed": [$items]"""
        }
        if (diff.customTypesChanged.isNotEmpty()) {
            val items = diff.customTypesChanged.joinToString(", ") { e ->
                val changes = renderQuotedStrings(e.changes)
                """{"name": "${esc(e.name)}", "kind": "${esc(e.kind)}", "changes": [$changes]}"""
            }
            parts += """    "custom_types_changed": [$items]"""
        }

        if (diff.tablesAdded.isNotEmpty())
            parts += """    "tables_added": [${diff.tablesAdded.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        if (diff.tablesRemoved.isNotEmpty())
            parts += """    "tables_removed": [${diff.tablesRemoved.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        if (diff.tablesChanged.isNotEmpty()) {
            val items = diff.tablesChanged.joinToString(",\n") { renderTableChange(it) }
            parts += "    \"tables_changed\": [\n$items\n    ]"
        }

        if (diff.viewsAdded.isNotEmpty())
            parts += """    "views_added": [${diff.viewsAdded.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        if (diff.viewsRemoved.isNotEmpty())
            parts += """    "views_removed": [${diff.viewsRemoved.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        if (diff.viewsChanged.isNotEmpty()) {
            val items = diff.viewsChanged.joinToString(", ") { v ->
                val fields = mutableListOf(""""name": "${esc(v.name)}"""")
                v.materialized?.let { fields += """"materialized": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
                if (v.queryChanged) fields += """"query": "changed""""
                v.refresh?.let { fields += """"refresh": {"before": ${nullable(it.before)}, "after": ${nullable(it.after)}}""" }
                v.sourceDialect?.let {
                    fields += renderNullableChangeField("source_dialect", it.before, it.after)
                }
                "{${fields.joinToString(", ")}}"
            }
            parts += """    "views_changed": [$items]"""
        }

        sb.appendLine(parts.joinToString(",\n"))
    }

    private fun renderTableChange(t: TableChangeView): String = buildString {
        append("      {\"name\": \"${esc(t.name)}\"")
        if (t.columnsAdded.isNotEmpty()) {
            val cols = t.columnsAdded.joinToString(", ") { """{"name": "${esc(it.name)}", "type": "${esc(it.type)}"}""" }
            append(""", "columns_added": [$cols]""")
        }
        if (t.columnsRemoved.isNotEmpty())
            append(""", "columns_removed": [${t.columnsRemoved.joinToString(", ") { "\"${esc(it)}\"" }}]""")
        if (t.columnsChanged.isNotEmpty()) {
            val cols = t.columnsChanged.joinToString(", ") { c ->
                val fields = mutableListOf(""""name": "${esc(c.name)}"""")
                c.type?.let { fields += """"type": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
                c.required?.let { fields += """"required": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
                c.default?.let { fields += """"default": {"before": ${nullable(it.before)}, "after": ${nullable(it.after)}}""" }
                c.unique?.let { fields += """"unique": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
                c.references?.let { fields += """"references": {"before": ${nullable(it.before)}, "after": ${nullable(it.after)}}""" }
                "{${fields.joinToString(", ")}}"
            }
            append(""", "columns_changed": [$cols]""")
        }
        t.primaryKey?.let { append(renderPrimaryKeyField(it.before, it.after)) }
        if (t.indicesAdded.isNotEmpty())
            append(""", "indices_added": [${t.indicesAdded.joinToString(", ") { "\"${esc(it)}\"" }}]""")
        if (t.indicesRemoved.isNotEmpty())
            append(""", "indices_removed": [${t.indicesRemoved.joinToString(", ") { "\"${esc(it)}\"" }}]""")
        if (t.indicesChanged.isNotEmpty()) {
            val items = t.indicesChanged.joinToString(", ") { """{"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
            append(""", "indices_changed": [$items]""")
        }
        if (t.constraintsAdded.isNotEmpty())
            append(""", "constraints_added": [${t.constraintsAdded.joinToString(", ") { "\"${esc(it)}\"" }}]""")
        if (t.constraintsRemoved.isNotEmpty())
            append(""", "constraints_removed": [${t.constraintsRemoved.joinToString(", ") { "\"${esc(it)}\"" }}]""")
        if (t.constraintsChanged.isNotEmpty()) {
            val items = t.constraintsChanged.joinToString(", ") { """{"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
            append(""", "constraints_changed": [$items]""")
        }
        append("}")
    }

    private fun renderValidation(sb: StringBuilder, v: CompareValidation) {
        val sides = mutableListOf<String>()
        v.source?.let { sides += renderValidationSide("source", it) }
        v.target?.let { sides += renderValidationSide("target", it) }
        sb.appendLine(sides.joinToString(",\n"))
    }

    private fun renderValidationSide(side: String, result: ValidationResult): String {
        val items = mutableListOf<String>()
        for (e in result.errors) items += """{"level": "error", "code": "${e.code}", "message": "${esc(e.message)}"}"""
        for (w in result.warnings) items += """{"level": "warning", "code": "${w.code}", "message": "${esc(w.message)}"}"""
        return """    "$side": [${items.joinToString(", ")}]"""
    }

    private fun renderOperandIfPresent(sb: StringBuilder, key: String, info: OperandInfo?) {
        if (info == null) return
        if (info.notes.isEmpty() && info.skippedObjects.isEmpty()) return
        sb.appendLine("""  "$key": {""")
        sb.appendLine("""    "reference": "${esc(info.reference)}",""")
        sb.appendLine("""    "notes": [${renderNotes(info)}],""")
        sb.appendLine("""    "skipped_objects": [${renderSkipped(info)}]""")
        sb.appendLine("  },")
    }

    private fun renderNotes(info: OperandInfo): String {
        if (info.notes.isEmpty()) return ""
        return info.notes.joinToString(", ") { n ->
            val fields = mutableListOf<String>()
            fields += """"severity": "${n.severity.name.lowercase()}""""
            fields += """"code": "${esc(n.code)}""""
            fields += """"object_name": "${esc(n.objectName)}""""
            fields += """"message": "${esc(n.message)}""""
            n.hint?.let { fields += """"hint": "${esc(it)}"""" }
            "{${fields.joinToString(", ")}}"
        }
    }

    private fun renderSkipped(info: OperandInfo): String {
        if (info.skippedObjects.isEmpty()) return ""
        return info.skippedObjects.joinToString(", ") { s ->
            val fields = mutableListOf<String>()
            fields += """"type": "${esc(s.type)}""""
            fields += """"name": "${esc(s.name)}""""
            fields += """"reason": "${esc(s.reason)}""""
            s.code?.let { fields += """"code": "${esc(it)}"""" }
            s.hint?.let { fields += """"hint": "${esc(it)}"""" }
            "{${fields.joinToString(", ")}}"
        }
    }

    private fun renderQuotedStrings(values: List<String>): String =
        values.joinToString(", ") { "\"${esc(it)}\"" }

    private fun renderPrimaryKeyField(before: List<String>, after: List<String>): String =
        buildString {
            append(""", "primary_key": {""")
            append(""""before": [${renderQuotedStrings(before)}], """)
            append(""""after": [${renderQuotedStrings(after)}]}""")
        }

    private fun renderNullableChangeField(
        name: String,
        before: String?,
        after: String?,
    ): String = """"$name": {"before": ${nullable(before)}, "after": ${nullable(after)}}"""

    private fun esc(s: String) = SchemaCompareHelpers.esc(s)
    private fun nullable(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""
}
