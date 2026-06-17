package dev.dmigrate.core.model

data class IndexColumn(
    val name: String,
    val direction: IndexSortDirection? = null,
    /**
     * MySQL prefix-index key length (`col(n)`), e.g. for indexing the first `n`
     * characters of a TEXT/BLOB column. Null = index the full column. PG/SQLite
     * have no prefix-index concept and drop it (with a note) on generate.
     */
    val prefixLength: Int? = null,
) {
    override fun toString(): String = buildString {
        append(name)
        if (prefixLength != null) append("($prefixLength)")
        if (direction != null) append(" ${direction.name}")
    }
}

enum class IndexSortDirection {
    ASC, DESC
}

data class IndexDefinition(
    val name: String? = null,
    val columns: List<IndexColumn>,
    val type: IndexType = IndexType.BTREE,
    val unique: Boolean = false,
    val where: String? = null,
) {
    val columnNames: List<String>
        get() = columns.map { it.name }
}

enum class IndexType {
    BTREE, HASH, GIN, GIST, BRIN
}
