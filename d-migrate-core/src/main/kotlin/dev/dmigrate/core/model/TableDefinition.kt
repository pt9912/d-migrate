package dev.dmigrate.core.model

data class TableDefinition(
    val description: String? = null,
    val columns: Map<String, ColumnDefinition> = emptyMap(),
    val primaryKey: List<String> = emptyList(),
    val indices: List<IndexDefinition> = emptyList(),
    val constraints: List<ConstraintDefinition> = emptyList(),
    val partitioning: PartitionConfig? = null
)
