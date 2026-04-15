package dev.dmigrate.profiling.port

/**
 * Outbound port for retrieving raw database schema metadata for profiling.
 *
 * This is NOT a wrapper around [dev.dmigrate.driver.SchemaReader].
 * It delivers a profiling-specific projection with raw `dbType` strings
 * and constraint metadata needed for profiling, not a neutral schema model.
 */
interface SchemaIntrospectionPort {

    /** Lists all user tables in the connected database/schema. */
    fun listTables(pool: dev.dmigrate.driver.connection.ConnectionPool, schema: String? = null): List<TableSchema>

    /** Lists columns for a specific table with raw DB types. */
    fun listColumns(pool: dev.dmigrate.driver.connection.ConnectionPool, table: String, schema: String? = null): List<ColumnSchema>
}

/**
 * Profiling-specific table metadata.
 */
data class TableSchema(
    val name: String,
    val schema: String? = null,
)

/**
 * Profiling-specific column metadata with raw database type.
 */
data class ColumnSchema(
    val name: String,
    val dbType: String,
    val nullable: Boolean,
    val isPrimaryKey: Boolean = false,
    val isForeignKey: Boolean = false,
    val isUnique: Boolean = false,
)
