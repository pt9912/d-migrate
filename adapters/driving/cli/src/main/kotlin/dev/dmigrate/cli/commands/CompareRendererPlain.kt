package dev.dmigrate.cli.commands

import dev.dmigrate.core.validation.ValidationResult

/**
 * Plain-text renderer for [SchemaCompareDocument].
 */
internal object CompareRendererPlain {

    fun render(doc: SchemaCompareDocument): String = buildString {
        appendLine("Schema Compare: ${doc.source} <-> ${doc.target}")
        appendLine()

        if (doc.status == "invalid") { renderInvalid(this, doc); return@buildString }
        if (doc.status == "identical") { appendLine("Status: IDENTICAL"); appendLine("No differences found."); return@buildString }

        appendLine("Status: DIFFERENT")
        appendLine()
        renderSummary(this, doc.summary)
        val diff = doc.diff ?: return@buildString
        renderDiffSections(this, diff)
        renderOperandInfo(this, doc.sourceOperand, "source")
        renderOperandInfo(this, doc.targetOperand, "target")
    }.trimEnd()

    private fun renderInvalid(sb: StringBuilder, doc: SchemaCompareDocument) {
        sb.appendLine("Status: INVALID")
        val v = doc.validation ?: return
        v.source?.let { renderValidation(sb, it, "source") }
        v.target?.let { renderValidation(sb, it, "target") }
    }

    private fun renderDiffSections(sb: StringBuilder, diff: DiffView) {
        renderMetadata(sb, diff.schemaMetadata)
        renderCustomTypes(sb, diff)
        renderTables(sb, diff)
        renderViews(sb, diff)
        renderObjectList(sb, "Sequences", diff.sequencesAdded, diff.sequencesRemoved, diff.sequencesChanged)
        renderObjectList(sb, "Functions", diff.functionsAdded, diff.functionsRemoved, diff.functionsChanged)
        renderObjectList(sb, "Procedures", diff.proceduresAdded, diff.proceduresRemoved, diff.proceduresChanged)
        renderObjectList(sb, "Triggers", diff.triggersAdded, diff.triggersRemoved, diff.triggersChanged)
    }

    private fun renderMetadata(sb: StringBuilder, metadata: MetadataChangeView?) {
        if (metadata == null) return
        sb.appendLine()
        sb.appendLine("Schema Metadata:")
        metadata.name?.let { sb.appendLine("  name: ${it.before} -> ${it.after}") }
        metadata.version?.let { sb.appendLine("  version: ${it.before} -> ${it.after}") }
    }

    private fun renderCustomTypes(sb: StringBuilder, diff: DiffView) {
        if (diff.customTypesAdded.isEmpty() && diff.customTypesRemoved.isEmpty() && diff.customTypesChanged.isEmpty()) return
        sb.appendLine(); sb.appendLine("Custom Types:")
        for (e in diff.customTypesAdded) sb.appendLine("  + ${e.name} (${e.kind}): ${e.detail}")
        for (e in diff.customTypesRemoved) sb.appendLine("  - ${e.name} (${e.kind}): ${e.detail}")
        for (e in diff.customTypesChanged) {
            sb.appendLine("  ~ ${e.name} (${e.kind}):")
            for (ch in e.changes) sb.appendLine("      $ch")
        }
    }

    private fun renderTables(sb: StringBuilder, diff: DiffView) {
        if (diff.tablesAdded.isEmpty() && diff.tablesRemoved.isEmpty() && diff.tablesChanged.isEmpty()) return
        sb.appendLine(); sb.appendLine("Tables:")
        for (t in diff.tablesAdded) sb.appendLine("  + ${t.name} (${t.columnCount} columns)")
        for (t in diff.tablesRemoved) sb.appendLine("  - ${t.name} (${t.columnCount} columns)")
        for (t in diff.tablesChanged) { sb.appendLine("  ~ ${t.name}:"); renderTableChange(sb, t) }
    }

    private fun renderViews(sb: StringBuilder, diff: DiffView) {
        if (diff.viewsAdded.isEmpty() && diff.viewsRemoved.isEmpty() && diff.viewsChanged.isEmpty()) return
        sb.appendLine(); sb.appendLine("Views:")
        for (v in diff.viewsAdded) sb.appendLine("  + ${v.name}${if (v.materialized) " (materialized)" else ""}")
        for (v in diff.viewsRemoved) sb.appendLine("  - ${v.name}${if (v.materialized) " (materialized)" else ""}")
        for (v in diff.viewsChanged) {
            sb.appendLine("  ~ ${v.name}:")
            v.materialized?.let { sb.appendLine("      materialized: ${it.before} -> ${it.after}") }
            v.refresh?.let { sb.appendLine("      refresh: ${it.before} -> ${it.after}") }
            if (v.queryChanged) sb.appendLine("      query: changed")
            v.sourceDialect?.let { sb.appendLine("      source_dialect: ${it.before} -> ${it.after}") }
        }
    }

    private fun renderSummary(sb: StringBuilder, s: SchemaCompareSummary) {
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

    private fun renderTableChange(sb: StringBuilder, t: TableChangeView) {
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

    private fun renderValidation(sb: StringBuilder, result: ValidationResult, side: String) {
        for (e in result.errors) sb.appendLine("  Error [$side] [${e.code}]: ${e.message}")
        for (w in result.warnings) sb.appendLine("  Warning [$side] [${w.code}]: ${w.message}")
    }

    private fun renderObjectList(sb: StringBuilder, label: String, added: List<String>, removed: List<String>, changed: List<String>) {
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) return
        sb.appendLine()
        sb.appendLine("$label:")
        for (name in added) sb.appendLine("  + $name")
        for (name in removed) sb.appendLine("  - $name")
        for (name in changed) sb.appendLine("  ~ $name")
    }

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
