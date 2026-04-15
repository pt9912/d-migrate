package dev.dmigrate.profiling.service

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.SchemaIntrospectionError
import dev.dmigrate.profiling.model.DatabaseProfile

/**
 * Orchestrates profiling for an entire database.
 * Delegates each table to [ProfileTableService].
 */
class ProfileDatabaseService(
    private val adapters: ProfilingAdapterSet,
    private val tableService: ProfileTableService = ProfileTableService(adapters),
) {

    /**
     * Profiles the database, optionally filtering to specific tables.
     *
     * @param schema database schema to profile (PostgreSQL only, e.g. "public")
     * @param tables if non-null, only these tables are profiled (deterministic order preserved)
     */
    fun profile(
        pool: ConnectionPool,
        databaseProduct: String,
        databaseVersion: String? = null,
        schema: String? = null,
        tables: List<String>? = null,
    ): DatabaseProfile {
        val allTables = try {
            adapters.introspection.listTables(pool, schema)
        } catch (e: Exception) {
            throw SchemaIntrospectionError("Failed to list tables: ${e.message}", e)
        }

        val targetTables = if (tables != null) {
            val available = allTables.map { it.name }.toSet()
            tables.filter { it in available }
        } else {
            allTables.map { it.name }.sorted()
        }

        val tableProfiles = targetTables.map { tableName ->
            tableService.profile(pool, tableName, schema)
        }

        return DatabaseProfile(
            databaseProduct = databaseProduct,
            databaseVersion = databaseVersion,
            schemaName = schema,
            tables = tableProfiles,
        )
    }
}
