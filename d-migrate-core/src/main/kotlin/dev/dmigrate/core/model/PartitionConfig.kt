package dev.dmigrate.core.model

data class PartitionConfig(
    val type: PartitionType,
    val key: List<String>,
    val partitions: List<PartitionDefinition> = emptyList()
)

enum class PartitionType {
    RANGE, HASH, LIST
}

data class PartitionDefinition(
    val name: String,
    val from: String? = null,
    val to: String? = null,
    val values: List<String>? = null
)
