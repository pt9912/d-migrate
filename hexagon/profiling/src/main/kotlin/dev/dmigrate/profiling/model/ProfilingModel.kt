package dev.dmigrate.profiling.model

import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.Severity
import dev.dmigrate.profiling.types.TargetLogicalType
import dev.dmigrate.profiling.types.WarningCode

/**
 * Root profile for an entire database.
 */
data class DatabaseProfile(
    val databaseProduct: String,
    val databaseVersion: String? = null,
    val schemaName: String? = null,
    val generatedAt: String,
    val tables: List<TableProfile>,
)

/**
 * Profile for a single table.
 */
data class TableProfile(
    val name: String,
    val schema: String? = null,
    val rowCount: Long,
    val columns: List<ColumnProfile>,
    val warnings: List<ProfileWarning> = emptyList(),
)

/**
 * Profile for a single column with counts, statistics, and compatibility checks.
 */
data class ColumnProfile(
    val name: String,
    val dbType: String,
    val logicalType: LogicalType,
    val nullable: Boolean,
    val rowCount: Long,
    val nonNullCount: Long,
    val nullCount: Long,
    val emptyStringCount: Long = 0,
    val blankStringCount: Long = 0,
    val distinctCount: Long = 0,
    val duplicateValueCount: Long = 0,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minValue: String? = null,
    val maxValue: String? = null,
    val topValues: List<ValueFrequency> = emptyList(),
    val numericStats: NumericStats? = null,
    val temporalStats: TemporalStats? = null,
    val targetCompatibility: List<TargetTypeCompatibility> = emptyList(),
    val warnings: List<ProfileWarning> = emptyList(),
)

/**
 * Frequency of a specific value in a column (top-N analysis).
 */
data class ValueFrequency(
    val value: String?,
    val count: Long,
    val ratio: Double,
)

/**
 * Statistics for numeric columns.
 */
data class NumericStats(
    val min: Double? = null,
    val max: Double? = null,
    val avg: Double? = null,
    val sum: Double? = null,
    val stddev: Double? = null,
    val zeroCount: Long? = null,
    val negativeCount: Long? = null,
)

/**
 * Statistics for temporal columns.
 */
data class TemporalStats(
    val minTimestamp: String? = null,
    val maxTimestamp: String? = null,
)

/**
 * A structured profiling warning produced by the rule engine.
 */
data class ProfileWarning(
    val code: WarningCode,
    val message: String,
    val severity: Severity = Severity.INFO,
)

/**
 * Result of checking a column's actual values against a potential target type.
 *
 * The [determinationStatus] indicates how the counts were derived:
 * - [DeterminationStatus.FULL_SCAN]: all rows were checked
 * - [DeterminationStatus.UNKNOWN]: no check was performed yet
 *
 * Later phases must not report sample-based results as FULL_SCAN.
 */
data class TargetTypeCompatibility(
    val targetType: TargetLogicalType,
    val checkedValueCount: Long,
    val compatibleCount: Long,
    val incompatibleCount: Long,
    val exampleInvalidValues: List<String> = emptyList(),
    val determinationStatus: DeterminationStatus = DeterminationStatus.UNKNOWN,
)

/**
 * How the compatibility counts were determined.
 */
enum class DeterminationStatus {
    FULL_SCAN,
    UNKNOWN,
}
