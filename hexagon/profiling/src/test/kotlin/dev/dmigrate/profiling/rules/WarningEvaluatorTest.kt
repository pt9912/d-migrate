package dev.dmigrate.profiling.rules

import dev.dmigrate.profiling.model.ColumnProfile
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.model.TargetTypeCompatibility
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.TargetLogicalType
import dev.dmigrate.profiling.types.WarningCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class WarningEvaluatorTest : FunSpec({

    val evaluator = WarningEvaluator()

    fun stringColumn(
        name: String = "col",
        rowCount: Long = 100,
        nonNullCount: Long = 100,
        nullCount: Long = 0,
        emptyStringCount: Long = 0,
        blankStringCount: Long = 0,
        distinctCount: Long = 50,
        duplicateValueCount: Long = 0,
        topValues: List<ValueFrequency> = emptyList(),
        targetCompatibility: List<TargetTypeCompatibility> = emptyList(),
    ) = ColumnProfile(
        name = name, dbType = "TEXT", logicalType = LogicalType.STRING,
        nullable = true, rowCount = rowCount, nonNullCount = nonNullCount,
        nullCount = nullCount, emptyStringCount = emptyStringCount,
        blankStringCount = blankStringCount, distinctCount = distinctCount,
        duplicateValueCount = duplicateValueCount, topValues = topValues,
        targetCompatibility = targetCompatibility,
    )

    fun intColumn(
        name: String = "id",
        rowCount: Long = 100,
        nonNullCount: Long = 100,
        nullCount: Long = 0,
        distinctCount: Long = 80,
    ) = ColumnProfile(
        name = name, dbType = "INT", logicalType = LogicalType.INTEGER,
        nullable = false, rowCount = rowCount, nonNullCount = nonNullCount,
        nullCount = nullCount, distinctCount = distinctCount,
    )

    // ── HIGH_NULL_RATIO ──────────────────────────────────

    test("high null ratio fires at 50%+") {
        val warnings = evaluator.evaluateColumn(stringColumn(nullCount = 60, nonNullCount = 40))
        warnings.any { it.code == WarningCode.HIGH_NULL_RATIO } shouldBe true
    }

    test("no high null ratio below threshold") {
        val warnings = evaluator.evaluateColumn(stringColumn(nullCount = 10, nonNullCount = 90))
        warnings.any { it.code == WarningCode.HIGH_NULL_RATIO } shouldBe false
    }

    test("no high null ratio on empty table") {
        val warnings = evaluator.evaluateColumn(stringColumn(rowCount = 0, nonNullCount = 0, nullCount = 0))
        warnings.any { it.code == WarningCode.HIGH_NULL_RATIO } shouldBe false
    }

    // ── CONTAINS_EMPTY_STRINGS ───────────────────────────

    test("empty strings fires on string column") {
        val warnings = evaluator.evaluateColumn(stringColumn(emptyStringCount = 5))
        warnings.any { it.code == WarningCode.CONTAINS_EMPTY_STRINGS } shouldBe true
    }

    test("no empty strings on integer column") {
        val col = intColumn().copy(emptyStringCount = 5)
        val warnings = evaluator.evaluateColumn(col)
        warnings.any { it.code == WarningCode.CONTAINS_EMPTY_STRINGS } shouldBe false
    }

    // ── CONTAINS_BLANK_STRINGS ───────────────────────────

    test("blank strings fires on string column") {
        val warnings = evaluator.evaluateColumn(stringColumn(blankStringCount = 3))
        warnings.any { it.code == WarningCode.CONTAINS_BLANK_STRINGS } shouldBe true
    }

    // ── HIGH_CARDINALITY ─────────────────────────────────

    test("high cardinality fires at 95%+") {
        val warnings = evaluator.evaluateColumn(stringColumn(distinctCount = 98, nonNullCount = 100))
        warnings.any { it.code == WarningCode.HIGH_CARDINALITY } shouldBe true
    }

    test("no high cardinality below threshold") {
        val warnings = evaluator.evaluateColumn(stringColumn(distinctCount = 50, nonNullCount = 100))
        warnings.any { it.code == WarningCode.HIGH_CARDINALITY } shouldBe false
    }

    test("no high cardinality on small tables") {
        val warnings = evaluator.evaluateColumn(stringColumn(rowCount = 5, nonNullCount = 5, distinctCount = 5))
        warnings.any { it.code == WarningCode.HIGH_CARDINALITY } shouldBe false
    }

    // ── LOW_CARDINALITY ──────────────────────────────────

    test("low cardinality fires for 5 or fewer distinct values") {
        val warnings = evaluator.evaluateColumn(stringColumn(distinctCount = 3, nonNullCount = 100))
        warnings.any { it.code == WarningCode.LOW_CARDINALITY } shouldBe true
    }

    test("no low cardinality above threshold") {
        val warnings = evaluator.evaluateColumn(stringColumn(distinctCount = 20, nonNullCount = 100))
        warnings.any { it.code == WarningCode.LOW_CARDINALITY } shouldBe false
    }

    // ── DUPLICATE_VALUES ─────────────────────────────────

    test("duplicate values fires when present") {
        val warnings = evaluator.evaluateColumn(stringColumn(duplicateValueCount = 10))
        warnings.any { it.code == WarningCode.DUPLICATE_VALUES } shouldBe true
    }

    test("no duplicate values when zero") {
        val warnings = evaluator.evaluateColumn(stringColumn(duplicateValueCount = 0))
        warnings.any { it.code == WarningCode.DUPLICATE_VALUES } shouldBe false
    }

    // ── INVALID_TARGET_TYPE_VALUES ───────────────────────

    test("invalid target type values fires when incompatible count > 0") {
        val compat = TargetTypeCompatibility(
            targetType = TargetLogicalType.INTEGER,
            checkedValueCount = 100, compatibleCount = 90, incompatibleCount = 10,
            exampleInvalidValues = listOf("abc"),
            determinationStatus = DeterminationStatus.FULL_SCAN,
        )
        val warnings = evaluator.evaluateColumn(stringColumn(targetCompatibility = listOf(compat)))
        warnings.any { it.code == WarningCode.INVALID_TARGET_TYPE_VALUES } shouldBe true
        warnings.first { it.code == WarningCode.INVALID_TARGET_TYPE_VALUES }.message shouldBe
            "Column 'col' has 10 values incompatible with target type INTEGER (e.g. abc)"
    }

    test("no invalid target type values when all compatible") {
        val compat = TargetTypeCompatibility(
            targetType = TargetLogicalType.INTEGER,
            checkedValueCount = 100, compatibleCount = 100, incompatibleCount = 0,
        )
        val warnings = evaluator.evaluateColumn(stringColumn(targetCompatibility = listOf(compat)))
        warnings.any { it.code == WarningCode.INVALID_TARGET_TYPE_VALUES } shouldBe false
    }

    // ── POSSIBLE_PLACEHOLDER_VALUES ──────────────────────

    test("placeholder values fires on known patterns") {
        val topValues = listOf(
            ValueFrequency("N/A", 50, 0.5),
            ValueFrequency("active", 30, 0.3),
        )
        val warnings = evaluator.evaluateColumn(stringColumn(topValues = topValues))
        warnings.any { it.code == WarningCode.POSSIBLE_PLACEHOLDER_VALUES } shouldBe true
    }

    test("no placeholder values when top values are clean") {
        val topValues = listOf(
            ValueFrequency("active", 50, 0.5),
            ValueFrequency("inactive", 30, 0.3),
        )
        val warnings = evaluator.evaluateColumn(stringColumn(topValues = topValues))
        warnings.any { it.code == WarningCode.POSSIBLE_PLACEHOLDER_VALUES } shouldBe false
    }

    // ── Clean column produces no warnings ────────────────

    test("clean column produces no warnings") {
        val warnings = evaluator.evaluateColumn(intColumn())
        warnings.shouldBeEmpty()
    }

    // ── Evaluator with all default rules ─────────────────

    test("evaluator uses all 8 default column rules") {
        defaultColumnRules() shouldHaveSize 8
    }

    // ── Table-level evaluation ───────────────────────────

    test("evaluateTable with no table rules returns empty") {
        val table = dev.dmigrate.profiling.model.TableProfile(
            name = "users", rowCount = 100,
            columns = listOf(intColumn()),
        )
        evaluator.evaluateTable(table).shouldBeEmpty()
    }

    test("evaluateTable with custom table rule fires") {
        val customRule = TableWarningRule { table ->
            if (table.rowCount == 0L) listOf(
                dev.dmigrate.profiling.model.ProfileWarning(
                    WarningCode.LOW_CARDINALITY,
                    "Table '${table.name}' is empty",
                    dev.dmigrate.profiling.types.Severity.WARN,
                )
            ) else emptyList()
        }
        val eval = WarningEvaluator(tableRules = listOf(customRule))
        val table = dev.dmigrate.profiling.model.TableProfile(
            name = "empty_table", rowCount = 0, columns = emptyList(),
        )
        val warnings = eval.evaluateTable(table)
        warnings shouldHaveSize 1
        warnings[0].message shouldBe "Table 'empty_table' is empty"
    }
})
