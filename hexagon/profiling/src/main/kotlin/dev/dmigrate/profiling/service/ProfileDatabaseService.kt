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
     * @param tables if non-null, only these tables are profiled (deterministic order preserved)
     */
    fun profile(
        pool: ConnectionPool,
        databaseProduct: String,
        databaseVersion: String? = null,
        tables: List<String>? = null,
    ): DatabaseProfile {
        val allTables = try {
            adapters.introspection.listTables(pool)
        } catch (e: Exception) {
            throw SchemaIntrospectionError("Failed to list tables: ${e.message}", e)
        }

        val targetTables = if (tables != null) {
            // Preserve caller order, validate existence
            val available = allTables.map { it.name }.toSet()
            tables.filter { it in available }
        } else {
            // Stable alphabetical order
            allTables.map { it.name }.sorted()
        }

        val tableProfiles = targetTables.map { tableName ->
            tableService.profile(pool, tableName)
        }

        return DatabaseProfile(
            databaseProduct = databaseProduct,
            databaseVersion = databaseVersion,
            tables = tableProfiles,
        )
    }
}
