package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.TableMetadata

data class TableDiff(
    val name: String,
    val columnsAdded: Map<String, ColumnDefinition> = emptyMap(),
    val columnsRemoved: Map<String, ColumnDefinition> = emptyMap(),
    val columnsChanged: List<ColumnDiff> = emptyList(),
    val primaryKey: ValueChange<List<String>>? = null,
    val indicesAdded: List<IndexDefinition> = emptyList(),
    val indicesRemoved: List<IndexDefinition> = emptyList(),
    val indicesChanged: List<ValueChange<IndexDefinition>> = emptyList(),
    val constraintsAdded: List<ConstraintDefinition> = emptyList(),
    val constraintsRemoved: List<ConstraintDefinition> = emptyList(),
    val constraintsChanged: List<ValueChange<ConstraintDefinition>> = emptyList(),
    val metadata: ValueChange<TableMetadata?>? = null,
) {
    fun hasChanges(): Boolean =
        columnsAdded.isNotEmpty() ||
            columnsRemoved.isNotEmpty() ||
            columnsChanged.isNotEmpty() ||
            primaryKey != null ||
            indicesAdded.isNotEmpty() ||
            indicesRemoved.isNotEmpty() ||
            indicesChanged.isNotEmpty() ||
            constraintsAdded.isNotEmpty() ||
            constraintsRemoved.isNotEmpty() ||
            constraintsChanged.isNotEmpty() ||
            metadata != null
}
