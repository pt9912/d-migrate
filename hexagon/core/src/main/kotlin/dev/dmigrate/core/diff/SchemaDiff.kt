package dev.dmigrate.core.diff

import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition

data class SchemaMetadataDiff(
    val name: ValueChange<String>? = null,
    val version: ValueChange<String>? = null,
) {
    fun hasChanges(): Boolean = name != null || version != null
}

data class NamedTable(val name: String, val definition: TableDefinition)
data class NamedView(val name: String, val definition: ViewDefinition)

data class SchemaDiff(
    val schemaMetadata: SchemaMetadataDiff? = null,
    val tablesAdded: List<NamedTable> = emptyList(),
    val tablesRemoved: List<NamedTable> = emptyList(),
    val tablesChanged: List<TableDiff> = emptyList(),
    val customTypesAdded: List<NamedCustomType> = emptyList(),
    val customTypesRemoved: List<NamedCustomType> = emptyList(),
    val customTypesChanged: List<CustomTypeDiff> = emptyList(),
    val viewsAdded: List<NamedView> = emptyList(),
    val viewsRemoved: List<NamedView> = emptyList(),
    val viewsChanged: List<ViewDiff> = emptyList(),
    val sequencesAdded: List<NamedSequence> = emptyList(),
    val sequencesRemoved: List<NamedSequence> = emptyList(),
    val sequencesChanged: List<SequenceDiff> = emptyList(),
    val functionsAdded: List<NamedFunction> = emptyList(),
    val functionsRemoved: List<NamedFunction> = emptyList(),
    val functionsChanged: List<FunctionDiff> = emptyList(),
    val proceduresAdded: List<NamedProcedure> = emptyList(),
    val proceduresRemoved: List<NamedProcedure> = emptyList(),
    val proceduresChanged: List<ProcedureDiff> = emptyList(),
    val triggersAdded: List<NamedTrigger> = emptyList(),
    val triggersRemoved: List<NamedTrigger> = emptyList(),
    val triggersChanged: List<TriggerDiff> = emptyList(),
) {
    fun isEmpty(): Boolean =
        (schemaMetadata == null || !schemaMetadata.hasChanges()) &&
            tablesAdded.isEmpty() &&
            tablesRemoved.isEmpty() &&
            tablesChanged.isEmpty() &&
            customTypesAdded.isEmpty() &&
            customTypesRemoved.isEmpty() &&
            customTypesChanged.isEmpty() &&
            viewsAdded.isEmpty() &&
            viewsRemoved.isEmpty() &&
            viewsChanged.isEmpty() &&
            sequencesAdded.isEmpty() &&
            sequencesRemoved.isEmpty() &&
            sequencesChanged.isEmpty() &&
            functionsAdded.isEmpty() &&
            functionsRemoved.isEmpty() &&
            functionsChanged.isEmpty() &&
            proceduresAdded.isEmpty() &&
            proceduresRemoved.isEmpty() &&
            proceduresChanged.isEmpty() &&
            triggersAdded.isEmpty() &&
            triggersRemoved.isEmpty() &&
            triggersChanged.isEmpty()
}
