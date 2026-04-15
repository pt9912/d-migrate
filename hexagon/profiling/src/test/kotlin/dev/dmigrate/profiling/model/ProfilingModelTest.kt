package dev.dmigrate.profiling.model

import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.Severity
import dev.dmigrate.profiling.types.TargetLogicalType
import dev.dmigrate.profiling.types.WarningCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ProfilingModelTest : FunSpec({

    test("ColumnProfile carries all core fields") {
        val col = ColumnProfile(
            name = "email",
            dbType = "VARCHAR(254)",
            logicalType = LogicalType.STRING,
            nullable = false,
            rowCount = 1000,
            nonNullCount = 1000,
            nullCount = 0,
            emptyStringCount = 5,
            distinctCount = 950,
        )
        col.name shouldBe "email"
        col.logicalType shouldBe LogicalType.STRING
        col.emptyStringCount shouldBe 5
    }

    test("TableProfile aggregates columns and warnings") {
        val table = TableProfile(
            name = "users",
            rowCount = 100,
            columns = listOf(
                ColumnProfile("id", "INT", LogicalType.INTEGER, false, 100, 100, 0),
                ColumnProfile("name", "TEXT", LogicalType.STRING, true, 100, 90, 10),
            ),
            warnings = listOf(
                ProfileWarning(WarningCode.HIGH_NULL_RATIO, "test", Severity.WARN),
            ),
        )
        table.columns.size shouldBe 2
        table.warnings.size shouldBe 1
    }

    test("DatabaseProfile aggregates tables without generatedAt") {
        val db = DatabaseProfile(
            databaseProduct = "PostgreSQL",
            databaseVersion = "16.1",
            tables = emptyList(),
        )
        db.databaseProduct shouldBe "PostgreSQL"
        db.tables shouldBe emptyList()
    }

    test("DatabaseProfile is deterministic — no runtime-variable fields") {
        val a = DatabaseProfile("PostgreSQL", "16.1", null, emptyList())
        val b = DatabaseProfile("PostgreSQL", "16.1", null, emptyList())
        a shouldBe b
    }

    test("TargetTypeCompatibility with FULL_SCAN") {
        val compat = TargetTypeCompatibility(
            targetType = TargetLogicalType.INTEGER,
            checkedValueCount = 1000,
            compatibleCount = 990,
            incompatibleCount = 10,
            exampleInvalidValues = listOf("abc", "n/a"),
            determinationStatus = DeterminationStatus.FULL_SCAN,
        )
        compat.checkedValueCount shouldBe (compat.compatibleCount + compat.incompatibleCount)
        compat.determinationStatus shouldBe DeterminationStatus.FULL_SCAN
        compat.exampleInvalidValues.size shouldBe 2
    }

    test("TargetTypeCompatibility defaults to UNKNOWN") {
        val compat = TargetTypeCompatibility(
            targetType = TargetLogicalType.BOOLEAN,
            checkedValueCount = 0,
            compatibleCount = 0,
            incompatibleCount = 0,
        )
        compat.determinationStatus shouldBe DeterminationStatus.UNKNOWN
    }

    test("NumericStats carries optional fields") {
        val stats = NumericStats(min = 0.0, max = 100.0, avg = 50.0)
        stats.min shouldBe 0.0
        stats.stddev shouldBe null
    }

    test("TemporalStats carries optional fields") {
        val stats = TemporalStats(minTimestamp = "2020-01-01", maxTimestamp = "2026-04-15")
        stats.minTimestamp shouldNotBe null
    }

    test("ValueFrequency models top-N entry") {
        val vf = ValueFrequency("active", 500, 0.5)
        vf.value shouldBe "active"
        vf.ratio shouldBe 0.5
    }

    test("ProfileWarning defaults to INFO severity") {
        val w = ProfileWarning(WarningCode.LOW_CARDINALITY, "test")
        w.severity shouldBe Severity.INFO
    }
})
