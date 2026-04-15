package dev.dmigrate.profiling.port

import dev.dmigrate.profiling.model.NumericStats
import dev.dmigrate.profiling.model.TargetTypeCompatibility
import dev.dmigrate.profiling.model.TemporalStats
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.types.TargetLogicalType

/**
 * Outbound port for retrieving aggregate profiling data from a database.
 *
 * All methods execute actual SQL queries — no heuristics or estimates.
 * Implementations must document any dialect-specific fallbacks (e.g.,
 * SQLite lacking `stddev_pop`).
 */
interface ProfilingDataPort {

    /** Row count for a table. */
    fun rowCount(pool: dev.dmigrate.driver.connection.ConnectionPool, table: String): Long

    /** Per-column aggregate metrics. */
    fun columnMetrics(
        pool: dev.dmigrate.driver.connection.ConnectionPool,
        table: String,
        column: String,
        dbType: String,
    ): ColumnMetrics

    /** Top-N most frequent values, deterministically sorted by count desc, value asc. */
    fun topValues(
        pool: dev.dmigrate.driver.connection.ConnectionPool,
        table: String,
        column: String,
        limit: Int = 10,
    ): List<ValueFrequency>

    /** Numeric statistics for numeric columns. Returns null for non-numeric. */
    fun numericStats(
        pool: dev.dmigrate.driver.connection.ConnectionPool,
        table: String,
        column: String,
    ): NumericStats?

    /** Temporal min/max for date/time columns. Returns null for non-temporal. */
    fun temporalStats(
        pool: dev.dmigrate.driver.connection.ConnectionPool,
        table: String,
        column: String,
    ): TemporalStats?

    /**
     * Full-scan target type compatibility check.
     * Must not use heuristics — every non-null value is checked.
     */
    fun targetTypeCompatibility(
        pool: dev.dmigrate.driver.connection.ConnectionPool,
        table: String,
        column: String,
        targetTypes: List<TargetLogicalType>,
    ): List<TargetTypeCompatibility>
}

/**
 * Intermediate per-column metrics from a single aggregate query.
 */
data class ColumnMetrics(
    val nonNullCount: Long,
    val nullCount: Long,
    val distinctCount: Long,
    val duplicateValueCount: Long,
    val emptyStringCount: Long = 0,
    val blankStringCount: Long = 0,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minValue: String? = null,
    val maxValue: String? = null,
)
