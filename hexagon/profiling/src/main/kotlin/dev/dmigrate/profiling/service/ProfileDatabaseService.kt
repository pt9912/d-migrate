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
            val availableByName = allTables.associateBy { it.name }
            tables.mapNotNull { availableByName[it] }
        } else {
            allTables.sortedBy { it.name }
        }

        val tableProfiles = targetTables.map { table ->
            tableService.profile(pool, table.name, schema ?: table.schema)
        }

        return DatabaseProfile(
            databaseProduct = databaseProduct,
            databaseVersion = databaseVersion,
            schemaName = schema,
            tables = tableProfiles,
        )
    }
}
