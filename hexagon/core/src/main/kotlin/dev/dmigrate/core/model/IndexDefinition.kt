package dev.dmigrate.core.model

data class IndexDefinition(
    val name: String? = null,
    val columns: List<String>,
    val type: IndexType = IndexType.BTREE,
    val unique: Boolean = false
)

enum class IndexType {
    BTREE, HASH, GIN, GIST, BRIN
}
