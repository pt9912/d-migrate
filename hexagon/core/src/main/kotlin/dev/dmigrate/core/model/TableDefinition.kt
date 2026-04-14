package dev.dmigrate.core.model

data class TableMetadata(
    val engine: String? = null,
    val withoutRowid: Boolean = false,
)

data class TableDefinition(
    val description: String? = null,
    val columns: Map<String, ColumnDefinition> = emptyMap(),
    val primaryKey: List<String> = emptyList(),
    val indices: List<IndexDefinition> = emptyList(),
    val constraints: List<ConstraintDefinition> = emptyList(),
    val partitioning: PartitionConfig? = null,
    val metadata: TableMetadata? = null,
)
