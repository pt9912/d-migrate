package dev.dmigrate.cli.commands

import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SkippedObject

// ── Operand metadata for structured output ───────────────────────

data class OperandInfo(
    val reference: String,
    val validation: ValidationResult? = null,
    val notes: List<SchemaReadNote> = emptyList(),
    val skippedObjects: List<SkippedObject> = emptyList(),
)

// ── Stable CLI projection (primitive-first) ──────────────────────

data class SchemaCompareDocument(
    val status: String,
    val exitCode: Int,
    val source: String,
    val target: String,
    val summary: SchemaCompareSummary,
    val diff: DiffView?,
    val validation: CompareValidation? = null,
    val sourceOperand: OperandInfo? = null,
    val targetOperand: OperandInfo? = null,
)

data class SchemaCompareSummary(
    val tablesAdded: Int = 0,
    val tablesRemoved: Int = 0,
    val tablesChanged: Int = 0,
    val customTypesAdded: Int = 0,
    val customTypesRemoved: Int = 0,
    val customTypesChanged: Int = 0,
    val viewsAdded: Int = 0,
    val viewsRemoved: Int = 0,
    val viewsChanged: Int = 0,
    val sequencesAdded: Int = 0,
    val sequencesRemoved: Int = 0,
    val sequencesChanged: Int = 0,
    val functionsAdded: Int = 0,
    val functionsRemoved: Int = 0,
    val functionsChanged: Int = 0,
    val proceduresAdded: Int = 0,
    val proceduresRemoved: Int = 0,
    val proceduresChanged: Int = 0,
    val triggersAdded: Int = 0,
    val triggersRemoved: Int = 0,
    val triggersChanged: Int = 0,
) {
    val totalChanges: Int get() = tablesAdded + tablesRemoved + tablesChanged +
        customTypesAdded + customTypesRemoved + customTypesChanged +
        viewsAdded + viewsRemoved + viewsChanged +
        sequencesAdded + sequencesRemoved + sequencesChanged +
        functionsAdded + functionsRemoved + functionsChanged +
        proceduresAdded + proceduresRemoved + proceduresChanged +
        triggersAdded + triggersRemoved + triggersChanged
}

data class CompareValidation(
    val source: ValidationResult? = null,
    val target: ValidationResult? = null,
)

// ── DiffView: stable, primitive-only projection of SchemaDiff ────

data class DiffView(
    val schemaMetadata: MetadataChangeView? = null,
    val customTypesAdded: List<CustomTypeSummaryView> = emptyList(),
    val customTypesRemoved: List<CustomTypeSummaryView> = emptyList(),
    val customTypesChanged: List<CustomTypeChangeView> = emptyList(),
    val tablesAdded: List<TableSummaryView> = emptyList(),
    val tablesRemoved: List<TableSummaryView> = emptyList(),
    val tablesChanged: List<TableChangeView> = emptyList(),
    val viewsAdded: List<ViewSummaryView> = emptyList(),
    val viewsRemoved: List<ViewSummaryView> = emptyList(),
    val viewsChanged: List<ViewChangeView> = emptyList(),
    val sequencesAdded: List<String> = emptyList(),
    val sequencesRemoved: List<String> = emptyList(),
    val sequencesChanged: List<String> = emptyList(),
    val functionsAdded: List<String> = emptyList(),
    val functionsRemoved: List<String> = emptyList(),
    val functionsChanged: List<String> = emptyList(),
    val proceduresAdded: List<String> = emptyList(),
    val proceduresRemoved: List<String> = emptyList(),
    val proceduresChanged: List<String> = emptyList(),
    val triggersAdded: List<String> = emptyList(),
    val triggersRemoved: List<String> = emptyList(),
    val triggersChanged: List<String> = emptyList(),
)

data class MetadataChangeView(
    val name: StringChange? = null,
    val version: StringChange? = null,
)

data class StringChange(val before: String, val after: String)
data class NullableStringChange(val before: String?, val after: String?)
data class StringListChange(val before: List<String>, val after: List<String>)

data class CustomTypeSummaryView(val name: String, val kind: String, val detail: String)
data class CustomTypeChangeView(val name: String, val kind: String, val changes: List<String>)

data class TableSummaryView(val name: String, val columnCount: Int)
data class TableChangeView(
    val name: String,
    val columnsAdded: List<ColumnSummaryView> = emptyList(),
    val columnsRemoved: List<String> = emptyList(),
    val columnsChanged: List<ColumnChangeView> = emptyList(),
    val primaryKey: StringListChange? = null,
    val indicesAdded: List<String> = emptyList(),
    val indicesRemoved: List<String> = emptyList(),
    val indicesChanged: List<StringChange> = emptyList(),
    val constraintsAdded: List<String> = emptyList(),
    val constraintsRemoved: List<String> = emptyList(),
    val constraintsChanged: List<StringChange> = emptyList(),
)

data class ColumnSummaryView(val name: String, val type: String)
data class ColumnChangeView(
    val name: String,
    val type: StringChange? = null,
    val required: StringChange? = null,
    val default: NullableStringChange? = null,
    val unique: StringChange? = null,
    val references: NullableStringChange? = null,
)

data class ViewSummaryView(val name: String, val materialized: Boolean)
data class ViewChangeView(
    val name: String,
    val materialized: StringChange? = null,
    val refresh: NullableStringChange? = null,
    val queryChanged: Boolean = false,
    val sourceDialect: NullableStringChange? = null,
)
