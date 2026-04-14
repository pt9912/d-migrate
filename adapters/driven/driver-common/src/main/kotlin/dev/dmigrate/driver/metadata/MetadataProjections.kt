package dev.dmigrate.driver.metadata

/**
 * Typed projections for common JDBC metadata query results.
 *
 * These are pure data carriers — mapping to the neutral model
 * happens in dialect-specific SchemaReader implementations (Phase D).
 * No [Map]<String, Any> escape hatches.
 */

data class TableRef(
    val name: String,
    val schema: String? = null,
    val type: String = "BASE TABLE",
)

data class ColumnProjection(
    val name: String,
    val dataType: String,
    val isNullable: Boolean,
    val columnDefault: String?,
    val ordinalPosition: Int,
    val characterMaxLength: Int? = null,
    val numericPrecision: Int? = null,
    val numericScale: Int? = null,
    val isAutoIncrement: Boolean = false,
)

data class PrimaryKeyProjection(
    val columns: List<String>,
)

data class ForeignKeyProjection(
    val name: String,
    val columns: List<String>,
    val referencedTable: String,
    val referencedColumns: List<String>,
    val onDelete: String? = null,
    val onUpdate: String? = null,
)

data class IndexProjection(
    val name: String,
    val columns: List<String>,
    val isUnique: Boolean,
    val type: String? = null,
)

data class ConstraintProjection(
    val name: String,
    val type: String,
    val columns: List<String>? = null,
    val expression: String? = null,
)
