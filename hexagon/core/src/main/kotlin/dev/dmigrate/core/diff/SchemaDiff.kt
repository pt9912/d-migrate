package dev.dmigrate.core.diff

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition

data class SchemaMetadataDiff(
    val name: ValueChange<String>? = null,
    val version: ValueChange<String>? = null,
) {
    fun hasChanges(): Boolean = name != null || version != null
}

data class NamedTable(val name: String, val definition: TableDefinition)
data class NamedEnumType(val name: String, val definition: CustomTypeDefinition)
data class NamedView(val name: String, val definition: ViewDefinition)

data class SchemaDiff(
    val schemaMetadata: SchemaMetadataDiff? = null,
    val tablesAdded: List<NamedTable> = emptyList(),
    val tablesRemoved: List<NamedTable> = emptyList(),
    val tablesChanged: List<TableDiff> = emptyList(),
    val enumTypesAdded: List<NamedEnumType> = emptyList(),
    val enumTypesRemoved: List<NamedEnumType> = emptyList(),
    val enumTypesChanged: List<EnumTypeDiff> = emptyList(),
    val viewsAdded: List<NamedView> = emptyList(),
    val viewsRemoved: List<NamedView> = emptyList(),
    val viewsChanged: List<ViewDiff> = emptyList(),
) {
    fun isEmpty(): Boolean =
        (schemaMetadata == null || !schemaMetadata.hasChanges()) &&
            tablesAdded.isEmpty() &&
            tablesRemoved.isEmpty() &&
            tablesChanged.isEmpty() &&
            enumTypesAdded.isEmpty() &&
            enumTypesRemoved.isEmpty() &&
            enumTypesChanged.isEmpty() &&
            viewsAdded.isEmpty() &&
            viewsRemoved.isEmpty() &&
            viewsChanged.isEmpty()
}
