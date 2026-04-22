package dev.dmigrate.cli.commands

import dev.dmigrate.core.validation.ValidationResult

/**
 * YAML renderer for [SchemaCompareDocument].
 */
internal object CompareRendererYaml {

    fun render(doc: SchemaCompareDocument): String = buildString {
        appendLine("command: schema.compare")
        appendLine("status: ${doc.status}")
        appendLine("exit_code: ${doc.exitCode}")
        appendLine("source: \"${esc(doc.source)}\"")
        appendLine("target: \"${esc(doc.target)}\"")
        renderSummary(this, doc.summary)

        val yv = doc.validation
        if (yv != null) {
            appendLine("validation:")
            renderValidation(this, yv)
        }

        renderOperandIfPresent(this, "source_operand", doc.sourceOperand)
        renderOperandIfPresent(this, "target_operand", doc.targetOperand)

        val yd = doc.diff
        if (yd != null) {
            appendLine("diff:")
            renderDiff(this, yd)
        } else {
            appendLine("diff: null")
        }
    }.trimEnd()

    private fun renderSummary(sb: StringBuilder, s: SchemaCompareSummary) {
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

    private fun renderDiff(sb: StringBuilder, diff: DiffView) {
        renderDiffMetadata(sb, diff.schemaMetadata)
        renderDiffCustomTypes(sb, diff)
        renderDiffTables(sb, diff)
        renderDiffViews(sb, diff)
    }

    private fun renderDiffMetadata(sb: StringBuilder, m: MetadataChangeView?) {
        if (m == null) return
        sb.appendLine("  schema_metadata:")
        m.name?.let { sb.appendLine("    name: {before: \"${esc(it.before)}\", after: \"${esc(it.after)}\"}") }
        m.version?.let { sb.appendLine("    version: {before: \"${esc(it.before)}\", after: \"${esc(it.after)}\"}") }
    }

    private fun renderDiffCustomTypes(sb: StringBuilder, diff: DiffView) {
        if (diff.customTypesAdded.isNotEmpty()) {
            sb.appendLine("  custom_types_added:")
            for (e in diff.customTypesAdded) sb.appendLine("    - {name: \"${esc(e.name)}\", kind: \"${esc(e.kind)}\"}")
        }
        if (diff.customTypesRemoved.isNotEmpty()) {
            sb.appendLine("  custom_types_removed:")
            for (e in diff.customTypesRemoved) sb.appendLine("    - {name: \"${esc(e.name)}\", kind: \"${esc(e.kind)}\"}")
        }
        if (diff.customTypesChanged.isNotEmpty()) {
            sb.appendLine("  custom_types_changed:")
            for (e in diff.customTypesChanged) {
                sb.appendLine("    - name: \"${esc(e.name)}\"")
                sb.appendLine("      kind: \"${esc(e.kind)}\"")
                sb.appendLine("      changes:")
                for (ch in e.changes) sb.appendLine("        - \"${esc(ch)}\"")
            }
        }
    }

    private fun renderDiffTables(sb: StringBuilder, diff: DiffView) {
        if (diff.tablesAdded.isNotEmpty()) { sb.appendLine("  tables_added:"); for (t in diff.tablesAdded) sb.appendLine("    - \"${esc(t.name)}\"") }
        if (diff.tablesRemoved.isNotEmpty()) { sb.appendLine("  tables_removed:"); for (t in diff.tablesRemoved) sb.appendLine("    - \"${esc(t.name)}\"") }
        if (diff.tablesChanged.isNotEmpty()) { sb.appendLine("  tables_changed:"); for (t in diff.tablesChanged) renderTableChange(sb, t) }
    }

    private fun renderDiffViews(sb: StringBuilder, diff: DiffView) {
        if (diff.viewsAdded.isNotEmpty()) { sb.appendLine("  views_added:"); for (v in diff.viewsAdded) sb.appendLine("    - \"${esc(v.name)}\"") }
        if (diff.viewsRemoved.isNotEmpty()) { sb.appendLine("  views_removed:"); for (v in diff.viewsRemoved) sb.appendLine("    - \"${esc(v.name)}\"") }
        if (diff.viewsChanged.isNotEmpty()) {
            sb.appendLine("  views_changed:")
            for (v in diff.viewsChanged) {
                sb.appendLine("    - name: \"${esc(v.name)}\"")
                v.materialized?.let { sb.appendLine("      materialized: {before: ${it.before}, after: ${it.after}}") }
                v.refresh?.let { sb.appendLine("      refresh: {before: ${nullable(it.before)}, after: ${nullable(it.after)}}") }
                if (v.queryChanged) sb.appendLine("      query: changed")
                v.sourceDialect?.let { sb.appendLine("      source_dialect: {before: ${nullable(it.before)}, after: ${nullable(it.after)}}") }
            }
        }
    }

    private fun renderTableChange(sb: StringBuilder, t: TableChangeView) {
        sb.appendLine("    - name: \"${esc(t.name)}\"")
        renderTableColumns(sb, t)
        t.primaryKey?.let {
            sb.appendLine("      primary_key: {before: [${it.before.joinToString(", ") { s -> "\"${esc(s)}\"" }}], after: [${it.after.joinToString(", ") { s -> "\"${esc(s)}\"" }}]}")
        }
        renderTableIndicesAndConstraints(sb, t)
    }

    private fun renderTableColumns(sb: StringBuilder, t: TableChangeView) {
        if (t.columnsAdded.isNotEmpty()) {
            sb.appendLine("      columns_added:")
            for (c in t.columnsAdded) { sb.appendLine("        - name: \"${esc(c.name)}\""); sb.appendLine("          type: \"${esc(c.type)}\"") }
        }
        if (t.columnsRemoved.isNotEmpty()) { sb.appendLine("      columns_removed:"); for (n in t.columnsRemoved) sb.appendLine("        - \"${esc(n)}\"") }
        if (t.columnsChanged.isNotEmpty()) {
            sb.appendLine("      columns_changed:")
            for (c in t.columnsChanged) {
                sb.appendLine("        - name: \"${esc(c.name)}\"")
                c.type?.let { sb.appendLine("          type: {before: \"${esc(it.before)}\", after: \"${esc(it.after)}\"}") }
                c.required?.let { sb.appendLine("          required: {before: ${it.before}, after: ${it.after}}") }
                c.default?.let { sb.appendLine("          default: {before: ${nullable(it.before)}, after: ${nullable(it.after)}}") }
                c.unique?.let { sb.appendLine("          unique: {before: ${it.before}, after: ${it.after}}") }
                c.references?.let { sb.appendLine("          references: {before: ${nullable(it.before)}, after: ${nullable(it.after)}}") }
            }
        }
    }

    private fun renderTableIndicesAndConstraints(sb: StringBuilder, t: TableChangeView) {
        if (t.indicesAdded.isNotEmpty()) { sb.appendLine("      indices_added:"); for (sig in t.indicesAdded) sb.appendLine("        - \"${esc(sig)}\"") }
        if (t.indicesRemoved.isNotEmpty()) { sb.appendLine("      indices_removed:"); for (sig in t.indicesRemoved) sb.appendLine("        - \"${esc(sig)}\"") }
        if (t.indicesChanged.isNotEmpty()) { sb.appendLine("      indices_changed:"); for (ch in t.indicesChanged) sb.appendLine("        - {before: \"${esc(ch.before)}\", after: \"${esc(ch.after)}\"}") }
        if (t.constraintsAdded.isNotEmpty()) { sb.appendLine("      constraints_added:"); for (sig in t.constraintsAdded) sb.appendLine("        - \"${esc(sig)}\"") }
        if (t.constraintsRemoved.isNotEmpty()) { sb.appendLine("      constraints_removed:"); for (sig in t.constraintsRemoved) sb.appendLine("        - \"${esc(sig)}\"") }
        if (t.constraintsChanged.isNotEmpty()) { sb.appendLine("      constraints_changed:"); for (ch in t.constraintsChanged) sb.appendLine("        - {before: \"${esc(ch.before)}\", after: \"${esc(ch.after)}\"}") }
    }

    private fun renderValidation(sb: StringBuilder, v: CompareValidation) {
        v.source?.let { renderValidationSide(sb, "source", it) }
        v.target?.let { renderValidationSide(sb, "target", it) }
    }

    private fun renderValidationSide(sb: StringBuilder, side: String, result: ValidationResult) {
        if (result.errors.isEmpty() && result.warnings.isEmpty()) {
            sb.appendLine("  $side: []")
            return
        }
        sb.appendLine("  $side:")
        for (e in result.errors) {
            sb.appendLine("    - level: error")
            sb.appendLine("      code: \"${e.code}\"")
            sb.appendLine("      message: \"${esc(e.message)}\"")
        }
        for (w in result.warnings) {
            sb.appendLine("    - level: warning")
            sb.appendLine("      code: \"${w.code}\"")
            sb.appendLine("      message: \"${esc(w.message)}\"")
        }
    }

    private fun renderOperandIfPresent(sb: StringBuilder, key: String, info: OperandInfo?) {
        if (info == null) return
        if (info.notes.isEmpty() && info.skippedObjects.isEmpty()) return
        sb.appendLine("$key:")
        sb.appendLine("  reference: \"${esc(info.reference)}\"")
        if (info.notes.isNotEmpty()) {
            sb.appendLine("  notes:")
            for (n in info.notes) {
                sb.appendLine("    - severity: ${n.severity.name.lowercase()}")
                sb.appendLine("      code: \"${esc(n.code)}\"")
                sb.appendLine("      object_name: \"${esc(n.objectName)}\"")
                sb.appendLine("      message: \"${esc(n.message)}\"")
                n.hint?.let { sb.appendLine("      hint: \"${esc(it)}\"") }
            }
        }
        if (info.skippedObjects.isNotEmpty()) {
            sb.appendLine("  skipped_objects:")
            for (s in info.skippedObjects) {
                sb.appendLine("    - type: \"${esc(s.type)}\"")
                sb.appendLine("      name: \"${esc(s.name)}\"")
                sb.appendLine("      reason: \"${esc(s.reason)}\"")
                s.code?.let { sb.appendLine("      code: \"${esc(it)}\"") }
                s.hint?.let { sb.appendLine("      hint: \"${esc(it)}\"") }
            }
        }
    }

    private fun esc(s: String) = SchemaCompareHelpers.esc(s)
    private fun nullable(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""
}
