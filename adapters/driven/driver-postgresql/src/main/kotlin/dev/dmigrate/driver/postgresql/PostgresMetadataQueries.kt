package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.metadata.ConstraintProjection
import dev.dmigrate.driver.metadata.ForeignKeyProjection
import dev.dmigrate.driver.metadata.IndexProjection
import dev.dmigrate.driver.metadata.TableRef

/**
 * Shared JDBC metadata queries for PostgreSQL.
 *
 * Operates on an already-borrowed connection via [JdbcOperations].
 * Uses both `information_schema` and `pg_catalog` for comprehensive
 * metadata extraction.
 */
object PostgresMetadataQueries {

    fun listTableRefs(session: JdbcOperations, schemaName: String): List<TableRef> =
        PostgresTableMetadataQueries.listTableRefs(session, schemaName)

    fun listColumns(session: JdbcOperations, schemaName: String, table: String): List<Map<String, Any?>> =
        PostgresTableMetadataQueries.listColumns(session, schemaName, table)

    fun listPrimaryKeyColumns(session: JdbcOperations, schemaName: String, table: String): List<String> =
        PostgresTableMetadataQueries.listPrimaryKeyColumns(session, schemaName, table)

    fun listForeignKeys(session: JdbcOperations, schemaName: String, table: String): List<ForeignKeyProjection> =
        PostgresTableMetadataQueries.listForeignKeys(session, schemaName, table)

    fun listUniqueConstraintColumns(
        session: JdbcOperations,
        schemaName: String,
        table: String,
    ): Map<String, List<String>> = PostgresTableMetadataQueries.listUniqueConstraintColumns(session, schemaName, table)

    fun listCheckConstraints(
        session: JdbcOperations,
        schemaName: String,
        table: String,
    ): List<ConstraintProjection> = PostgresTableMetadataQueries.listCheckConstraints(session, schemaName, table)

    fun listIndices(session: JdbcOperations, schemaName: String, table: String): List<IndexProjection> =
        PostgresTableMetadataQueries.listIndices(session, schemaName, table)

    fun listSequences(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> =
        PostgresTableMetadataQueries.listSequences(session, schemaName)

    fun getPartitionInfo(session: JdbcOperations, schemaName: String, table: String): Map<String, Any?>? =
        PostgresTableMetadataQueries.getPartitionInfo(session, schemaName, table)

    fun listInstalledExtensions(session: JdbcOperations): List<String> =
        PostgresTableMetadataQueries.listInstalledExtensions(session)

    fun listEnumTypes(session: JdbcOperations, schemaName: String): Map<String, List<String>> =
        PostgresTypeMetadataQueries.listEnumTypes(session, schemaName)

    fun listDomainTypes(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> =
        PostgresTypeMetadataQueries.listDomainTypes(session, schemaName)

    fun listCompositeTypes(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> =
        PostgresTypeMetadataQueries.listCompositeTypes(session, schemaName)

    fun listViews(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> =
        PostgresProgrammabilityMetadataQueries.listViews(session, schemaName)

    fun listViewFunctionDependencies(session: JdbcOperations, schemaName: String): Map<String, List<String>> =
        PostgresProgrammabilityMetadataQueries.listViewFunctionDependencies(session, schemaName)

    fun listFunctions(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> =
        PostgresProgrammabilityMetadataQueries.listFunctions(session, schemaName)

    fun listProcedures(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> =
        PostgresProgrammabilityMetadataQueries.listProcedures(session, schemaName)

    fun listRoutineParameters(session: JdbcOperations, schemaName: String, specificName: String): List<Map<String, Any?>> =
        PostgresProgrammabilityMetadataQueries.listRoutineParameters(session, schemaName, specificName)

    fun listTriggers(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> =
        PostgresProgrammabilityMetadataQueries.listTriggers(session, schemaName)
}
