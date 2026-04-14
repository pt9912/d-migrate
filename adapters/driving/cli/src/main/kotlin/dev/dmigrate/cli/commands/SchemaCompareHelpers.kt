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
        is NeutralType.Geometry -> {
            val gt = type.geometryType.schemaName
            val srid = type.srid
            if (srid != null) "geometry($gt,$srid)" else "geometry($gt)"
        }
    }

    fun defaultValueToString(dv: DefaultValue?): String? = when (dv) {
        null -> null
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

    fun referenceToString(ref: ReferenceDefinition?): String? {
        if (ref == null) return null
        return buildString {
            append("${ref.table}.${ref.column}")
            val parts = mutableListOf<String>()
            ref.onDelete?.let { parts += "onDelete=${it.name.lowercase()}" }
            ref.onUpdate?.let { parts += "onUpdate=${it.name.lowercase()}" }
            if (parts.isNotEmpty()) append(" (${parts.joinToString(", ")})")
        }
    }

    // ── Projection: SchemaDiff → DiffView ─────────────────────────

    fun projectDiff(diff: SchemaDiff): DiffView {
        val metadata = diff.schemaMetadata
        return DiffView(
            schemaMetadata = if (metadata != null) MetadataChangeView(
                name = metadata.name?.let { StringChange(it.before, it.after) },
                version = metadata.version?.let { StringChange(it.before, it.after) },
            ) else null,
            customTypesAdded = diff.customTypesAdded.map {
                CustomTypeSummaryView(
                    it.name,
                    it.definition.kind.name.lowercase(),
                    customTypeDetail(it.definition),
                )
            },
            customTypesRemoved = diff.customTypesRemoved.map {
                CustomTypeSummaryView(
                    it.name,
                    it.definition.kind.name.lowercase(),
                    customTypeDetail(it.definition),
                )
            },
            customTypesChanged = diff.customTypesChanged.map {
                CustomTypeChangeView(
                    it.name,
                    (it.kind?.after ?: it.kind?.before)?.name?.lowercase()
                        ?: "unknown",
                    customTypeChanges(it),
                )
            },
            tablesAdded = diff.tablesAdded.map {
                TableSummaryView(it.name, it.definition.columns.size)
            },
            tablesRemoved = diff.tablesRemoved.map {
                TableSummaryView(it.name, it.definition.columns.size)
            },
            tablesChanged = diff.tablesChanged.map { projectTableDiff(it) },
            viewsAdded = diff.viewsAdded.map {
                ViewSummaryView(it.name, it.definition.materialized)
            },
            viewsRemoved = diff.viewsRemoved.map {
                ViewSummaryView(it.name, it.definition.materialized)
            },
            viewsChanged = diff.viewsChanged.map { projectViewDiff(it) },
            sequencesAdded = diff.sequencesAdded.map { it.name },
            sequencesRemoved = diff.sequencesRemoved.map { it.name },
            sequencesChanged = diff.sequencesChanged.map { it.name },
            functionsAdded = diff.functionsAdded.map { it.name },
            functionsRemoved = diff.functionsRemoved.map { it.name },
            functionsChanged = diff.functionsChanged.map { it.name },
            proceduresAdded = diff.proceduresAdded.map { it.name },
            proceduresRemoved = diff.proceduresRemoved.map { it.name },
            proceduresChanged = diff.proceduresChanged.map { it.name },
            triggersAdded = diff.triggersAdded.map { it.name },
            triggersRemoved = diff.triggersRemoved.map { it.name },
            triggersChanged = diff.triggersChanged.map { it.name },
        )
    }

    private fun projectTableDiff(t: TableDiff): TableChangeView = TableChangeView(
        name = t.name,
        columnsAdded = t.columnsAdded.entries.sortedBy { it.key }.map { (n, c) -> ColumnSummaryView(n, neutralTypeToString(c.type)) },
        columnsRemoved = t.columnsRemoved.keys.sorted(),
        columnsChanged = t.columnsChanged.map { c ->
            ColumnChangeView(
                name = c.name,
                type = c.type?.let { StringChange(neutralTypeToString(it.before), neutralTypeToString(it.after)) },
                required = c.required?.let { StringChange(it.before.toString(), it.after.toString()) },
                default = c.default?.let { NullableStringChange(defaultValueToString(it.before), defaultValueToString(it.after)) },
                unique = c.unique?.let { StringChange(it.before.toString(), it.after.toString()) },
                references = c.references?.let { NullableStringChange(referenceToString(it.before), referenceToString(it.after)) },
            )
        },
        primaryKey = t.primaryKey?.let { StringListChange(it.before, it.after) },
        indicesAdded = t.indicesAdded.map { indexSignature(it) },
        indicesRemoved = t.indicesRemoved.map { indexSignature(it) },
        indicesChanged = t.indicesChanged.map { StringChange(indexSignature(it.before), indexSignature(it.after)) },
        constraintsAdded = t.constraintsAdded.map { constraintSignature(it) },
        constraintsRemoved = t.constraintsRemoved.map { constraintSignature(it) },
        constraintsChanged = t.constraintsChanged.map { StringChange(constraintSignature(it.before), constraintSignature(it.after)) },
    )

    private fun projectViewDiff(v: ViewDiff): ViewChangeView = ViewChangeView(
        name = v.name,
        materialized = v.materialized?.let { StringChange(it.before.toString(), it.after.toString()) },
        refresh = v.refresh?.let { NullableStringChange(it.before, it.after) },
        queryChanged = v.query != null,
        sourceDialect = v.sourceDialect?.let { NullableStringChange(it.before, it.after) },
    )

    // ── Plain-Text Renderer ───────────────────────────────────────

    fun renderPlain(doc: SchemaCompareDocument): String = buildString {
        appendLine("Schema Compare: ${doc.source} <-> ${doc.target}")
        appendLine()

        if (doc.status == "invalid") {
            appendLine("Status: INVALID")
            val v = doc.validation
            if (v != null) {
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

        val diff = doc.diff ?: return@buildString

        val metadata = diff.schemaMetadata
        if (metadata != null) {
            appendLine()
            appendLine("Schema Metadata:")
            metadata.name?.let { appendLine("  name: ${it.before} -> ${it.after}") }
            metadata.version?.let { appendLine("  version: ${it.before} -> ${it.after}") }
        }

        if (diff.customTypesAdded.isNotEmpty() || diff.customTypesRemoved.isNotEmpty() ||
            diff.customTypesChanged.isNotEmpty()) {
            appendLine()
            appendLine("Custom Types:")
            for (e in diff.customTypesAdded) appendLine("  + ${e.name} (${e.kind}): ${e.detail}")
            for (e in diff.customTypesRemoved) appendLine("  - ${e.name} (${e.kind}): ${e.detail}")
            for (e in diff.customTypesChanged) {
                appendLine("  ~ ${e.name} (${e.kind}):")
                for (ch in e.changes) appendLine("      $ch")
            }
        }

        if (diff.tablesAdded.isNotEmpty() || diff.tablesRemoved.isNotEmpty() ||
            diff.tablesChanged.isNotEmpty()) {
            appendLine()
            appendLine("Tables:")
            for (t in diff.tablesAdded) appendLine("  + ${t.name} (${t.columnCount} columns)")
            for (t in diff.tablesRemoved) appendLine("  - ${t.name} (${t.columnCount} columns)")
            for (t in diff.tablesChanged) {
                appendLine("  ~ ${t.name}:")
                renderTableChangePlain(this, t)
            }
        }

        if (diff.viewsAdded.isNotEmpty() || diff.viewsRemoved.isNotEmpty() ||
            diff.viewsChanged.isNotEmpty()) {
            appendLine()
            appendLine("Views:")
            for (v in diff.viewsAdded) appendLine("  + ${v.name}${if (v.materialized) " (materialized)" else ""}")
            for (v in diff.viewsRemoved) appendLine("  - ${v.name}${if (v.materialized) " (materialized)" else ""}")
            for (v in diff.viewsChanged) {
                appendLine("  ~ ${v.name}:")
                v.materialized?.let { appendLine("      materialized: ${it.before} -> ${it.after}") }
                v.refresh?.let { appendLine("      refresh: ${it.before} -> ${it.after}") }
                if (v.queryChanged) appendLine("      query: changed")
                v.sourceDialect?.let { appendLine("      source_dialect: ${it.before} -> ${it.after}") }
            }
        }

        renderObjectList(this, "Sequences", diff.sequencesAdded, diff.sequencesRemoved, diff.sequencesChanged)
        renderObjectList(this, "Functions", diff.functionsAdded, diff.functionsRemoved, diff.functionsChanged)
        renderObjectList(this, "Procedures", diff.proceduresAdded, diff.proceduresRemoved, diff.proceduresChanged)
        renderObjectList(this, "Triggers", diff.triggersAdded, diff.triggersRemoved, diff.triggersChanged)

        // Operand-side info (only if present)
        renderOperandInfo(this, doc.sourceOperand, "source")
        renderOperandInfo(this, doc.targetOperand, "target")
    }.trimEnd()

    private fun renderSummaryPlain(sb: StringBuilder, s: SchemaCompareSummary) {
        sb.appendLine("Summary: ${s.totalChanges} change(s)")
        if (s.tablesAdded > 0) sb.appendLine("  Tables added:    ${s.tablesAdded}")
        if (s.tablesRemoved > 0) sb.appendLine("  Tables removed:  ${s.tablesRemoved}")
        if (s.tablesChanged > 0) sb.appendLine("  Tables changed:  ${s.tablesChanged}")
        if (s.customTypesAdded > 0) sb.appendLine("  Types added:     ${s.customTypesAdded}")
        if (s.customTypesRemoved > 0) sb.appendLine("  Types removed:   ${s.customTypesRemoved}")
        if (s.customTypesChanged > 0) sb.appendLine("  Types changed:   ${s.customTypesChanged}")
        if (s.viewsAdded > 0) sb.appendLine("  Views added:     ${s.viewsAdded}")
        if (s.viewsRemoved > 0) sb.appendLine("  Views removed:   ${s.viewsRemoved}")
        if (s.viewsChanged > 0) sb.appendLine("  Views changed:   ${s.viewsChanged}")
        if (s.sequencesAdded > 0) sb.appendLine("  Sequences added: ${s.sequencesAdded}")
        if (s.sequencesRemoved > 0) sb.appendLine("  Sequences removed: ${s.sequencesRemoved}")
        if (s.sequencesChanged > 0) sb.appendLine("  Sequences changed: ${s.sequencesChanged}")
        if (s.functionsAdded > 0) sb.appendLine("  Functions added: ${s.functionsAdded}")
        if (s.functionsRemoved > 0) sb.appendLine("  Functions removed: ${s.functionsRemoved}")
        if (s.functionsChanged > 0) sb.appendLine("  Functions changed: ${s.functionsChanged}")
        if (s.proceduresAdded > 0) sb.appendLine("  Procedures added: ${s.proceduresAdded}")
        if (s.proceduresRemoved > 0) sb.appendLine("  Procedures removed: ${s.proceduresRemoved}")
        if (s.proceduresChanged > 0) sb.appendLine("  Procedures changed: ${s.proceduresChanged}")
        if (s.triggersAdded > 0) sb.appendLine("  Triggers added:  ${s.triggersAdded}")
        if (s.triggersRemoved > 0) sb.appendLine("  Triggers removed: ${s.triggersRemoved}")
        if (s.triggersChanged > 0) sb.appendLine("  Triggers changed: ${s.triggersChanged}")
    }

    private fun renderTableChangePlain(sb: StringBuilder, t: TableChangeView) {
        for (c in t.columnsAdded) sb.appendLine("      + column ${c.name}: ${c.type}")
        for (n in t.columnsRemoved) sb.appendLine("      - column $n")
        for (c in t.columnsChanged) {
            sb.appendLine("      ~ column ${c.name}:")
            c.type?.let { sb.appendLine("          type: ${it.before} -> ${it.after}") }
            c.required?.let { sb.appendLine("          required: ${it.before} -> ${it.after}") }
            c.default?.let { sb.appendLine("          default: ${it.before ?: "null"} -> ${it.after ?: "null"}") }
            c.unique?.let { sb.appendLine("          unique: ${it.before} -> ${it.after}") }
            c.references?.let { sb.appendLine("          references: ${it.before ?: "null"} -> ${it.after ?: "null"}") }
        }
        t.primaryKey?.let {
            sb.appendLine("      ~ primary_key: [${it.before.joinToString(", ")}] -> [${it.after.joinToString(", ")}]")
        }
        for (sig in t.indicesAdded) sb.appendLine("      + index $sig")
        for (sig in t.indicesRemoved) sb.appendLine("      - index $sig")
        for (ch in t.indicesChanged) sb.appendLine("      ~ index ${ch.before} -> ${ch.after}")
        for (sig in t.constraintsAdded) sb.appendLine("      + constraint $sig")
        for (sig in t.constraintsRemoved) sb.appendLine("      - constraint $sig")
        for (ch in t.constraintsChanged) sb.appendLine("      ~ constraint ${ch.before} -> ${ch.after}")
    }

    private fun renderValidationPlain(sb: StringBuilder, result: ValidationResult, side: String) {
        for (e in result.errors) sb.appendLine("  Error [$side] [${e.code}]: ${e.message}")
        for (w in result.warnings) sb.appendLine("  Warning [$side] [${w.code}]: ${w.message}")
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

        val jv = doc.validation
        if (jv != null) {
            appendLine("""  "validation": {""")
            renderValidationJson(this, jv)
            appendLine("  },")
        }

        val jd = doc.diff
        if (jd != null) {
            appendLine("""  "diff": {""")
            renderDiffJson(this, jd)
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

    private fun renderDiffJson(sb: StringBuilder, diff: DiffView) {
        val parts = mutableListOf<String>()

        val m = diff.schemaMetadata
        if (m != null) {
            val fields = mutableListOf<String>()
            m.name?.let { fields += """"name": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
            m.version?.let { fields += """"version": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
            parts += """    "schema_metadata": {${fields.joinToString(", ")}}"""
        }

        if (diff.customTypesAdded.isNotEmpty())
            parts += """    "custom_types_added": [${diff.customTypesAdded.joinToString(", ") { """{"name": "${esc(it.name)}", "kind": "${esc(it.kind)}"}""" }}]"""
        if (diff.customTypesRemoved.isNotEmpty())
            parts += """    "custom_types_removed": [${diff.customTypesRemoved.joinToString(", ") { """{"name": "${esc(it.name)}", "kind": "${esc(it.kind)}"}""" }}]"""
        if (diff.customTypesChanged.isNotEmpty()) {
            val items = diff.customTypesChanged.joinToString(", ") { e ->
                """{"name": "${esc(e.name)}", "kind": "${esc(e.kind)}", "changes": [${e.changes.joinToString(", ") { "\"${esc(it)}\"" }}]}"""
            }
            parts += """    "custom_types_changed": [$items]"""
        }

        if (diff.tablesAdded.isNotEmpty())
            parts += """    "tables_added": [${diff.tablesAdded.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        if (diff.tablesRemoved.isNotEmpty())
            parts += """    "tables_removed": [${diff.tablesRemoved.joinToString(", ") { "\"${esc(it.name)}\"" }}]"""
        if (diff.tablesChanged.isNotEmpty()) {
            val items = diff.tablesChanged.joinToString(",\n") { renderTableChangeJson(it) }
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
                v.refresh?.let { fields += """"refresh": {"before": ${jsonNullable(it.before)}, "after": ${jsonNullable(it.after)}}""" }
                v.sourceDialect?.let { fields += """"source_dialect": {"before": ${jsonNullable(it.before)}, "after": ${jsonNullable(it.after)}}""" }
                "{${fields.joinToString(", ")}}"
            }
            parts += """    "views_changed": [$items]"""
        }

        sb.appendLine(parts.joinToString(",\n"))
    }

    private fun renderTableChangeJson(t: TableChangeView): String = buildString {
        append("      {\"name\": \"${esc(t.name)}\"")

        if (t.columnsAdded.isNotEmpty()) {
            val cols = t.columnsAdded.joinToString(", ") {
                """{"name": "${esc(it.name)}", "type": "${esc(it.type)}"}"""
            }
            append(""", "columns_added": [$cols]""")
        }
        if (t.columnsRemoved.isNotEmpty())
            append(""", "columns_removed": [${t.columnsRemoved.joinToString(", ") { "\"${esc(it)}\"" }}]""")
        if (t.columnsChanged.isNotEmpty()) {
            val cols = t.columnsChanged.joinToString(", ") { c ->
                val fields = mutableListOf(""""name": "${esc(c.name)}"""")
                c.type?.let { fields += """"type": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
                c.required?.let { fields += """"required": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
                c.default?.let { fields += """"default": {"before": ${jsonNullable(it.before)}, "after": ${jsonNullable(it.after)}}""" }
                c.unique?.let { fields += """"unique": {"before": "${esc(it.before)}", "after": "${esc(it.after)}"}""" }
                c.references?.let { fields += """"references": {"before": ${jsonNullable(it.before)}, "after": ${jsonNullable(it.after)}}""" }
                "{${fields.joinToString(", ")}}"
            }
            append(""", "columns_changed": [$cols]""")
        }
        t.primaryKey?.let {
            append(""", "primary_key": {"before": [${it.before.joinToString(", ") { s -> "\"${esc(s)}\"" }}], "after": [${it.after.joinToString(", ") { s -> "\"${esc(s)}\"" }}]}""")
        }
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

    private fun renderValidationJson(sb: StringBuilder, v: CompareValidation) {
        val sides = mutableListOf<String>()
        v.source?.let { sides += renderValidationSideJson("source", it) }
        v.target?.let { sides += renderValidationSideJson("target", it) }
        sb.appendLine(sides.joinToString(",\n"))
    }

    private fun renderValidationSideJson(side: String, result: ValidationResult): String {
        val items = mutableListOf<String>()
        for (e in result.errors) items += """{"level": "error", "code": "${e.code}", "message": "${esc(e.message)}"}"""
        for (w in result.warnings) items += """{"level": "warning", "code": "${w.code}", "message": "${esc(w.message)}"}"""
        return """    "$side": [${items.joinToString(", ")}]"""
    }

    // ── YAML Renderer ─────────────────────────────────────────────

    fun renderYaml(doc: SchemaCompareDocument): String = buildString {
        appendLine("command: schema.compare")
        appendLine("status: ${doc.status}")
        appendLine("exit_code: ${doc.exitCode}")
        appendLine("source: \"${escY(doc.source)}\"")
        appendLine("target: \"${escY(doc.target)}\"")
        renderSummaryYaml(this, doc.summary)

        val yv = doc.validation
        if (yv != null) {
            appendLine("validation:")
            renderValidationYaml(this, yv)
        }

        val yd = doc.diff
        if (yd != null) {
            appendLine("diff:")
            renderDiffYaml(this, yd)
        } else {
            appendLine("diff: null")
        }
    }.trimEnd()

    private fun renderSummaryYaml(sb: StringBuilder, s: SchemaCompareSummary) {
        sb.appendLine("summary:")
        sb.appendLine("  tables_added: ${s.tablesAdded}")
        sb.appendLine("  tables_removed: ${s.tablesRemoved}")
        sb.appendLine("  tables_changed: ${s.tablesChanged}")
        sb.appendLine("  custom_types_added: ${s.customTypesAdded}")
        sb.appendLine("  custom_types_removed: ${s.customTypesRemoved}")
        sb.appendLine("  custom_types_changed: ${s.customTypesChanged}")
        sb.appendLine("  views_added: ${s.viewsAdded}")
        sb.appendLine("  views_removed: ${s.viewsRemoved}")
        sb.appendLine("  views_changed: ${s.viewsChanged}")
        sb.appendLine("  sequences_added: ${s.sequencesAdded}")
        sb.appendLine("  sequences_removed: ${s.sequencesRemoved}")
        sb.appendLine("  sequences_changed: ${s.sequencesChanged}")
        sb.appendLine("  functions_added: ${s.functionsAdded}")
        sb.appendLine("  functions_removed: ${s.functionsRemoved}")
        sb.appendLine("  functions_changed: ${s.functionsChanged}")
        sb.appendLine("  procedures_added: ${s.proceduresAdded}")
        sb.appendLine("  procedures_removed: ${s.proceduresRemoved}")
        sb.appendLine("  procedures_changed: ${s.proceduresChanged}")
        sb.appendLine("  triggers_added: ${s.triggersAdded}")
        sb.appendLine("  triggers_removed: ${s.triggersRemoved}")
        sb.appendLine("  triggers_changed: ${s.triggersChanged}")
    }

    private fun renderDiffYaml(sb: StringBuilder, diff: DiffView) {
        val m = diff.schemaMetadata
        if (m != null) {
            sb.appendLine("  schema_metadata:")
            m.name?.let { sb.appendLine("    name: {before: \"${escY(it.before)}\", after: \"${escY(it.after)}\"}") }
            m.version?.let { sb.appendLine("    version: {before: \"${escY(it.before)}\", after: \"${escY(it.after)}\"}") }
        }

        if (diff.customTypesAdded.isNotEmpty()) {
            sb.appendLine("  custom_types_added:")
            for (e in diff.customTypesAdded) sb.appendLine("    - {name: \"${escY(e.name)}\", kind: \"${escY(e.kind)}\"}")
        }
        if (diff.customTypesRemoved.isNotEmpty()) {
            sb.appendLine("  custom_types_removed:")
            for (e in diff.customTypesRemoved) sb.appendLine("    - {name: \"${escY(e.name)}\", kind: \"${escY(e.kind)}\"}")
        }
        if (diff.customTypesChanged.isNotEmpty()) {
            sb.appendLine("  custom_types_changed:")
            for (e in diff.customTypesChanged) {
                sb.appendLine("    - name: \"${escY(e.name)}\"")
                sb.appendLine("      kind: \"${escY(e.kind)}\"")
                sb.appendLine("      changes:")
                for (ch in e.changes) sb.appendLine("        - \"${escY(ch)}\"")
            }
        }

        if (diff.tablesAdded.isNotEmpty()) {
            sb.appendLine("  tables_added:")
            for (t in diff.tablesAdded) sb.appendLine("    - \"${escY(t.name)}\"")
        }
        if (diff.tablesRemoved.isNotEmpty()) {
            sb.appendLine("  tables_removed:")
            for (t in diff.tablesRemoved) sb.appendLine("    - \"${escY(t.name)}\"")
        }
        if (diff.tablesChanged.isNotEmpty()) {
            sb.appendLine("  tables_changed:")
            for (t in diff.tablesChanged) renderTableChangeYaml(sb, t)
        }

        if (diff.viewsAdded.isNotEmpty()) {
            sb.appendLine("  views_added:")
            for (v in diff.viewsAdded) sb.appendLine("    - \"${escY(v.name)}\"")
        }
        if (diff.viewsRemoved.isNotEmpty()) {
            sb.appendLine("  views_removed:")
            for (v in diff.viewsRemoved) sb.appendLine("    - \"${escY(v.name)}\"")
        }
        if (diff.viewsChanged.isNotEmpty()) {
            sb.appendLine("  views_changed:")
            for (v in diff.viewsChanged) {
                sb.appendLine("    - name: \"${escY(v.name)}\"")
                v.materialized?.let { sb.appendLine("      materialized: {before: ${it.before}, after: ${it.after}}") }
                v.refresh?.let { sb.appendLine("      refresh: {before: ${yamlNullable(it.before)}, after: ${yamlNullable(it.after)}}") }
                if (v.queryChanged) sb.appendLine("      query: changed")
                v.sourceDialect?.let { sb.appendLine("      source_dialect: {before: ${yamlNullable(it.before)}, after: ${yamlNullable(it.after)}}") }
            }
        }
    }

    private fun renderTableChangeYaml(sb: StringBuilder, t: TableChangeView) {
        sb.appendLine("    - name: \"${escY(t.name)}\"")
        if (t.columnsAdded.isNotEmpty()) {
            sb.appendLine("      columns_added:")
            for (c in t.columnsAdded) {
                sb.appendLine("        - name: \"${escY(c.name)}\"")
                sb.appendLine("          type: \"${escY(c.type)}\"")
            }
        }
        if (t.columnsRemoved.isNotEmpty()) {
            sb.appendLine("      columns_removed:")
            for (n in t.columnsRemoved) sb.appendLine("        - \"${escY(n)}\"")
        }
        if (t.columnsChanged.isNotEmpty()) {
            sb.appendLine("      columns_changed:")
            for (c in t.columnsChanged) {
                sb.appendLine("        - name: \"${escY(c.name)}\"")
                c.type?.let { sb.appendLine("          type: {before: \"${escY(it.before)}\", after: \"${escY(it.after)}\"}") }
                c.required?.let { sb.appendLine("          required: {before: ${it.before}, after: ${it.after}}") }
                c.default?.let { sb.appendLine("          default: {before: ${yamlNullable(it.before)}, after: ${yamlNullable(it.after)}}") }
                c.unique?.let { sb.appendLine("          unique: {before: ${it.before}, after: ${it.after}}") }
                c.references?.let { sb.appendLine("          references: {before: ${yamlNullable(it.before)}, after: ${yamlNullable(it.after)}}") }
            }
        }
        t.primaryKey?.let {
            sb.appendLine("      primary_key: {before: [${it.before.joinToString(", ") { s -> "\"${escY(s)}\"" }}], after: [${it.after.joinToString(", ") { s -> "\"${escY(s)}\"" }}]}")
        }
        if (t.indicesAdded.isNotEmpty()) {
            sb.appendLine("      indices_added:")
            for (sig in t.indicesAdded) sb.appendLine("        - \"${escY(sig)}\"")
        }
        if (t.indicesRemoved.isNotEmpty()) {
            sb.appendLine("      indices_removed:")
            for (sig in t.indicesRemoved) sb.appendLine("        - \"${escY(sig)}\"")
        }
        if (t.indicesChanged.isNotEmpty()) {
            sb.appendLine("      indices_changed:")
            for (ch in t.indicesChanged) sb.appendLine("        - {before: \"${escY(ch.before)}\", after: \"${escY(ch.after)}\"}")
        }
        if (t.constraintsAdded.isNotEmpty()) {
            sb.appendLine("      constraints_added:")
            for (sig in t.constraintsAdded) sb.appendLine("        - \"${escY(sig)}\"")
        }
        if (t.constraintsRemoved.isNotEmpty()) {
            sb.appendLine("      constraints_removed:")
            for (sig in t.constraintsRemoved) sb.appendLine("        - \"${escY(sig)}\"")
        }
        if (t.constraintsChanged.isNotEmpty()) {
            sb.appendLine("      constraints_changed:")
            for (ch in t.constraintsChanged) sb.appendLine("        - {before: \"${escY(ch.before)}\", after: \"${escY(ch.after)}\"}")
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
            sb.appendLine("      code: \"${e.code}\"")
            sb.appendLine("      message: \"${escY(e.message)}\"")
        }
        for (w in result.warnings) {
            sb.appendLine("    - level: warning")
            sb.appendLine("      code: \"${w.code}\"")
            sb.appendLine("      message: \"${escY(w.message)}\"")
        }
    }

    // ── Escaping (shared) ─────────────────────────────────────────

    internal fun esc(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    internal fun escY(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun jsonNullable(s: String?): String =
        if (s == null) "null" else "\"${esc(s)}\""

    private fun yamlNullable(s: String?): String =
        if (s == null) "null" else "\"${escY(s)}\""

    // ── Custom Type Helpers ──────────────────────────────────────────

    private fun customTypeDetail(def: CustomTypeDefinition): String = when (def.kind) {
        CustomTypeKind.ENUM -> def.values?.joinToString(", ") ?: ""
        CustomTypeKind.DOMAIN -> "base: ${def.baseType ?: "?"}"
        CustomTypeKind.COMPOSITE -> "${def.fields?.size ?: 0} fields"
    }

    private fun customTypeChanges(diff: CustomTypeDiff): List<String> {
        val changes = mutableListOf<String>()
        diff.kind?.let { changes += "kind: ${it.before} -> ${it.after}" }
        diff.values?.let { changes += "values: [${it.before.joinToString(", ")}] -> [${it.after.joinToString(", ")}]" }
        diff.baseType?.let { changes += "baseType: ${it.before} -> ${it.after}" }
        diff.precision?.let { changes += "precision: ${it.before} -> ${it.after}" }
        diff.scale?.let { changes += "scale: ${it.before} -> ${it.after}" }
        diff.check?.let { changes += "check: changed" }
        diff.description?.let { changes += "description: changed" }
        diff.fields?.let { changes += "fields: changed" }
        return changes
    }

    // ── Object list rendering (sequences, functions, etc.) ──────────

    private fun renderObjectList(
        sb: StringBuilder,
        label: String,
        added: List<String>,
        removed: List<String>,
        changed: List<String>,
    ) {
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) return
        sb.appendLine()
        sb.appendLine("$label:")
        for (name in added) sb.appendLine("  + $name")
        for (name in removed) sb.appendLine("  - $name")
        for (name in changed) sb.appendLine("  ~ $name")
    }

    // ── Operand info rendering ──────────────────────────────────────

    private fun renderOperandInfo(sb: StringBuilder, info: OperandInfo?, side: String) {
        if (info == null) return
        if (info.notes.isEmpty() && info.skippedObjects.isEmpty()) return
        sb.appendLine()
        sb.appendLine("Operand ($side): ${info.reference}")
        for (note in info.notes) {
            sb.appendLine("  ${note.severity.name.lowercase()} [${note.code}] ${note.objectName}: ${note.message}")
        }
        for (skip in info.skippedObjects) {
            sb.appendLine("  skipped [${skip.code ?: "-"}] ${skip.type} ${skip.name}: ${skip.reason}")
        }
    }
}
