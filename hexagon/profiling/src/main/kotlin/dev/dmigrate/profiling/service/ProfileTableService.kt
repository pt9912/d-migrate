package dev.dmigrate.profiling.service

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.ProfilingQueryError
import dev.dmigrate.profiling.SchemaIntrospectionError
import dev.dmigrate.profiling.UnsupportedProfilingFeatureException
import dev.dmigrate.profiling.model.ColumnProfile
import dev.dmigrate.profiling.model.TableProfile
import dev.dmigrate.profiling.rules.WarningEvaluator
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.TargetLogicalType

/**
 * Orchestrates profiling for a single table.
 * Loads metadata, profiles each column, resolves types, evaluates warnings.
 */
open class ProfileTableService(
    private val adapters: ProfilingAdapterSet,
    private val warningEvaluator: WarningEvaluator = WarningEvaluator(),
    private val targetTypes: List<TargetLogicalType> = listOf(
        TargetLogicalType.INTEGER, TargetLogicalType.DECIMAL,
        TargetLogicalType.BOOLEAN, TargetLogicalType.DATE,
        TargetLogicalType.DATETIME, TargetLogicalType.STRING,
    ),
    private val topN: Int = 10,
) {

    open fun profile(
        pool: ConnectionPool,
        tableName: String,
        schema: String? = null,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): TableProfile {
        cancellationToken.throwIfCancellationRequested()
        val columns = try {
            adapters.introspection.listColumns(pool, tableName, schema)
        } catch (e: Exception) {
            throw SchemaIntrospectionError("Failed to list columns for table '$tableName': ${e.message}", e)
        }

        cancellationToken.throwIfCancellationRequested()
        val rowCount = try {
            adapters.data.rowCount(pool, tableName, schema)
        } catch (e: Exception) {
            throw ProfilingQueryError("Failed to get row count for '$tableName': ${e.message}", e)
        }

        val columnProfiles = columns.map { col ->
            cancellationToken.throwIfCancellationRequested()
            profileColumn(pool, tableName, col.name, col.dbType, col.nullable, rowCount, schema, cancellationToken)
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
        schema: String? = null,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): ColumnProfile {
        val logicalType = try {
            adapters.typeResolver.resolve(dbType)
        } catch (e: Exception) {
            throw dev.dmigrate.profiling.TypeResolutionError(
                "Failed to resolve type for '$table.$column' (dbType: $dbType): ${e.message}", e)
        }

        cancellationToken.throwIfCancellationRequested()
        val metrics = try {
            adapters.data.columnMetrics(pool, table, column, dbType, schema)
        } catch (e: Exception) {
            throw ProfilingQueryError("Failed to profile column '$table.$column': ${e.message}", e)
        }

        cancellationToken.throwIfCancellationRequested()
        val topValues = try {
            adapters.data.topValues(pool, table, column, topN, schema)
        } catch (e: Exception) {
            throw ProfilingQueryError("Failed to get top values for '$table.$column': ${e.message}", e)
        }

        val numericStats = if (logicalType in setOf(LogicalType.INTEGER, LogicalType.DECIMAL)) {
            cancellationToken.throwIfCancellationRequested()
            optionalProfilingValue(
                operation = "numericStats",
                table = table,
                column = column,
                fallback = null,
            ) {
                adapters.data.numericStats(pool, table, column, schema)
            }
        } else null

        val temporalStats = if (logicalType in setOf(LogicalType.DATE, LogicalType.DATETIME)) {
            cancellationToken.throwIfCancellationRequested()
            optionalProfilingValue(
                operation = "temporalStats",
                table = table,
                column = column,
                fallback = null,
            ) {
                adapters.data.temporalStats(pool, table, column, schema)
            }
        } else null

        cancellationToken.throwIfCancellationRequested()
        val compatibility = optionalProfilingValue(
            operation = "targetTypeCompatibility",
            table = table,
            column = column,
            fallback = emptyList(),
        ) {
            adapters.data.targetTypeCompatibility(pool, table, column, targetTypes, schema)
        }

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

    private inline fun <T> optionalProfilingValue(
        operation: String,
        table: String,
        column: String,
        fallback: T,
        block: () -> T,
    ): T {
        return try {
            block()
        } catch (e: Exception) {
            if (e.isExpectedOptionalProfilingFailure()) {
                fallback
            } else {
                throw ProfilingQueryError(
                    "Failed optional profiling step '$operation' for '$table.$column': ${e.message}",
                    e,
                )
            }
        }
    }

    private fun Exception.isExpectedOptionalProfilingFailure(): Boolean =
        this is UnsupportedOperationException ||
            this is UnsupportedProfilingFeatureException ||
            cause is UnsupportedProfilingFeatureException
}
