package dev.dmigrate.profiling.service

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.ProfilingQueryError
import dev.dmigrate.profiling.SchemaIntrospectionError
import dev.dmigrate.profiling.model.ColumnProfile
import dev.dmigrate.profiling.model.TableProfile
import dev.dmigrate.profiling.rules.WarningEvaluator
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.TargetLogicalType

/**
 * Orchestrates profiling for a single table.
 * Loads metadata, profiles each column, resolves types, evaluates warnings.
 */
class ProfileTableService(
    private val adapters: ProfilingAdapterSet,
    private val warningEvaluator: WarningEvaluator = WarningEvaluator(),
    private val targetTypes: List<TargetLogicalType> = listOf(
        TargetLogicalType.INTEGER, TargetLogicalType.DECIMAL,
        TargetLogicalType.BOOLEAN, TargetLogicalType.DATE,
        TargetLogicalType.DATETIME, TargetLogicalType.STRING,
    ),
    private val topN: Int = 10,
) {

    fun profile(pool: ConnectionPool, tableName: String): TableProfile {
        val columns = try {
            adapters.introspection.listColumns(pool, tableName)
        } catch (e: Exception) {
            throw SchemaIntrospectionError("Failed to list columns for table '$tableName': ${e.message}", e)
        }

        val rowCount = try {
            adapters.data.rowCount(pool, tableName)
        } catch (e: Exception) {
            throw ProfilingQueryError("Failed to get row count for '$tableName': ${e.message}", e)
        }

        val columnProfiles = columns.map { col ->
            profileColumn(pool, tableName, col.name, col.dbType, col.nullable, rowCount)
        }

        val tableWarnings = warningEvaluator.evaluateTable(
            TableProfile(tableName, rowCount = rowCount, columns = columnProfiles)
        )

        return TableProfile(
            name = tableName,
            rowCount = rowCount,
            columns = columnProfiles,
            warnings = tableWarnings,
        )
    }

    private fun profileColumn(
        pool: ConnectionPool,
        table: String,
        column: String,
        dbType: String,
        nullable: Boolean,
        rowCount: Long,
    ): ColumnProfile {
        val logicalType = adapters.typeResolver.resolve(dbType)

        val metrics = try {
            adapters.data.columnMetrics(pool, table, column, dbType)
        } catch (e: Exception) {
            throw ProfilingQueryError("Failed to profile column '$table.$column': ${e.message}", e)
        }

        val topValues = try {
            adapters.data.topValues(pool, table, column, topN)
        } catch (e: Exception) {
            throw ProfilingQueryError("Failed to get top values for '$table.$column': ${e.message}", e)
        }

        val numericStats = if (logicalType in setOf(LogicalType.INTEGER, LogicalType.DECIMAL)) {
            try { adapters.data.numericStats(pool, table, column) } catch (_: Exception) { null }
        } else null

        val temporalStats = if (logicalType in setOf(LogicalType.DATE, LogicalType.DATETIME)) {
            try { adapters.data.temporalStats(pool, table, column) } catch (_: Exception) { null }
        } else null

        val compatibility = try {
            adapters.data.targetTypeCompatibility(pool, table, column, targetTypes)
        } catch (_: Exception) { emptyList() }

        val profile = ColumnProfile(
            name = column,
            dbType = dbType,
            logicalType = logicalType,
            nullable = nullable,
            rowCount = rowCount,
            nonNullCount = metrics.nonNullCount,
            nullCount = metrics.nullCount,
            emptyStringCount = metrics.emptyStringCount,
            blankStringCount = metrics.blankStringCount,
            distinctCount = metrics.distinctCount,
            duplicateValueCount = metrics.duplicateValueCount,
            minLength = metrics.minLength,
            maxLength = metrics.maxLength,
            minValue = metrics.minValue,
            maxValue = metrics.maxValue,
            topValues = topValues,
            numericStats = numericStats,
            temporalStats = temporalStats,
            targetCompatibility = compatibility,
        )

        return profile.copy(warnings = warningEvaluator.evaluateColumn(profile))
    }
}
