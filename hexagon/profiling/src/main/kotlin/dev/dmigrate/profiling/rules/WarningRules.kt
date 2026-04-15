package dev.dmigrate.profiling.rules

import dev.dmigrate.profiling.model.ColumnProfile
import dev.dmigrate.profiling.model.ProfileWarning
import dev.dmigrate.profiling.model.TableProfile
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.Severity
import dev.dmigrate.profiling.types.WarningCode

/**
 * A rule that evaluates a single [ColumnProfile] and returns zero or more warnings.
 */
fun interface ColumnWarningRule {
    fun evaluate(column: ColumnProfile): List<ProfileWarning>
}

/**
 * A rule that evaluates a [TableProfile] and returns zero or more warnings.
 */
fun interface TableWarningRule {
    fun evaluate(table: TableProfile): List<ProfileWarning>
}

/**
 * Orchestrates rule evaluation across columns and tables.
 * Purely functional — no I/O, no JDBC.
 */
class WarningEvaluator(
    private val columnRules: List<ColumnWarningRule> = defaultColumnRules(),
    private val tableRules: List<TableWarningRule> = emptyList(),
) {
    fun evaluateColumn(column: ColumnProfile): List<ProfileWarning> =
        columnRules.flatMap { it.evaluate(column) }

    fun evaluateTable(table: TableProfile): List<ProfileWarning> =
        tableRules.flatMap { it.evaluate(table) }
}

// ── Default column rules (migration-relevant catalog) ────────────

fun defaultColumnRules(): List<ColumnWarningRule> = listOf(
    HighNullRatioRule(),
    EmptyStringsRule(),
    BlankStringsRule(),
    HighCardinalityRule(),
    LowCardinalityRule(),
    DuplicateValuesRule(),
    InvalidTargetTypeValuesRule(),
    PlaceholderValuesRule(),
)

internal class HighNullRatioRule(private val threshold: Double = 0.5) : ColumnWarningRule {
    override fun evaluate(column: ColumnProfile): List<ProfileWarning> {
        if (column.rowCount == 0L) return emptyList()
        val ratio = column.nullCount.toDouble() / column.rowCount
        if (ratio >= threshold) {
            return listOf(ProfileWarning(
                code = WarningCode.HIGH_NULL_RATIO,
                message = "Column '${column.name}' has ${(ratio * 100).toInt()}% null values (${ column.nullCount}/${column.rowCount})",
                severity = Severity.WARN,
            ))
        }
        return emptyList()
    }
}

internal class EmptyStringsRule : ColumnWarningRule {
    override fun evaluate(column: ColumnProfile): List<ProfileWarning> {
        if (column.logicalType != LogicalType.STRING) return emptyList()
        if (column.emptyStringCount > 0) {
            return listOf(ProfileWarning(
                code = WarningCode.CONTAINS_EMPTY_STRINGS,
                message = "Column '${column.name}' contains ${column.emptyStringCount} empty strings",
                severity = Severity.WARN,
            ))
        }
        return emptyList()
    }
}

internal class BlankStringsRule : ColumnWarningRule {
    override fun evaluate(column: ColumnProfile): List<ProfileWarning> {
        if (column.logicalType != LogicalType.STRING) return emptyList()
        if (column.blankStringCount > 0) {
            return listOf(ProfileWarning(
                code = WarningCode.CONTAINS_BLANK_STRINGS,
                message = "Column '${column.name}' contains ${column.blankStringCount} blank (whitespace-only) strings",
                severity = Severity.WARN,
            ))
        }
        return emptyList()
    }
}

internal class HighCardinalityRule(private val threshold: Double = 0.95) : ColumnWarningRule {
    override fun evaluate(column: ColumnProfile): List<ProfileWarning> {
        if (column.nonNullCount < 10) return emptyList()
        val ratio = column.distinctCount.toDouble() / column.nonNullCount
        if (ratio >= threshold) {
            return listOf(ProfileWarning(
                code = WarningCode.HIGH_CARDINALITY,
                message = "Column '${column.name}' has very high cardinality (${column.distinctCount} distinct / ${column.nonNullCount} non-null = ${(ratio * 100).toInt()}%)",
                severity = Severity.INFO,
            ))
        }
        return emptyList()
    }
}

internal class LowCardinalityRule(private val maxDistinct: Long = 5) : ColumnWarningRule {
    override fun evaluate(column: ColumnProfile): List<ProfileWarning> {
        if (column.nonNullCount < 10) return emptyList()
        if (column.distinctCount in 1..maxDistinct) {
            return listOf(ProfileWarning(
                code = WarningCode.LOW_CARDINALITY,
                message = "Column '${column.name}' has only ${column.distinctCount} distinct values — candidate for lookup table or enum",
                severity = Severity.INFO,
            ))
        }
        return emptyList()
    }
}

internal class DuplicateValuesRule : ColumnWarningRule {
    override fun evaluate(column: ColumnProfile): List<ProfileWarning> {
        if (column.duplicateValueCount > 0) {
            return listOf(ProfileWarning(
                code = WarningCode.DUPLICATE_VALUES,
                message = "Column '${column.name}' has ${column.duplicateValueCount} duplicate non-null values",
                severity = Severity.INFO,
            ))
        }
        return emptyList()
    }
}

internal class InvalidTargetTypeValuesRule : ColumnWarningRule {
    override fun evaluate(column: ColumnProfile): List<ProfileWarning> =
        column.targetCompatibility
            .filter { it.incompatibleCount > 0 }
            .map { compat ->
                val examples = if (compat.exampleInvalidValues.isNotEmpty())
                    " (e.g. ${compat.exampleInvalidValues.joinToString(", ")})" else ""
                ProfileWarning(
                    code = WarningCode.INVALID_TARGET_TYPE_VALUES,
                    message = "Column '${column.name}' has ${compat.incompatibleCount} values incompatible with target type ${compat.targetType}$examples",
                    severity = Severity.WARN,
                )
            }
}

internal class PlaceholderValuesRule : ColumnWarningRule {
    private val placeholders = setOf("n/a", "na", "null", "none", "-", "--", "tbd", "unknown", "test", "xxx", "dummy")

    override fun evaluate(column: ColumnProfile): List<ProfileWarning> {
        if (column.logicalType != LogicalType.STRING) return emptyList()
        val found = column.topValues
            .filter { it.value != null && it.value.lowercase() in placeholders }
        if (found.isNotEmpty()) {
            val total = found.sumOf { it.count }
            val examples = found.map { "'${it.value}' (${it.count}x)" }.joinToString(", ")
            return listOf(ProfileWarning(
                code = WarningCode.POSSIBLE_PLACEHOLDER_VALUES,
                message = "Column '${column.name}' contains $total likely placeholder values: $examples",
                severity = Severity.WARN,
            ))
        }
        return emptyList()
    }
}
