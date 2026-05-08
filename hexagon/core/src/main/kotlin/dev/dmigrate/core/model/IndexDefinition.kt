package dev.dmigrate.core.model

data class IndexColumn(
    val name: String,
    val direction: IndexSortDirection? = null,
) {
    override fun toString(): String =
        if (direction == null) name else "$name ${direction.name}"
}

enum class IndexSortDirection {
    ASC, DESC
}

data class IndexDefinition(
    val name: String? = null,
    val columns: List<IndexColumn>,
    val type: IndexType = IndexType.BTREE,
    val unique: Boolean = false
) {
    val columnNames: List<String>
        get() = columns.map { it.name }
}

enum class IndexType {
    BTREE, HASH, GIN, GIST, BRIN
}
