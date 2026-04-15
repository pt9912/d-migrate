package dev.dmigrate.format.report

import dev.dmigrate.profiling.model.ColumnProfile
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.profiling.model.ProfileWarning
import dev.dmigrate.profiling.model.TableProfile
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.Severity
import dev.dmigrate.profiling.types.WarningCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ProfileReportWriterTest : FunSpec({

    val writer = ProfileReportWriter()

    val profile = DatabaseProfile(
        databaseProduct = "PostgreSQL",
        databaseVersion = "16.1",
        tables = listOf(
            TableProfile(
                name = "users",
                rowCount = 100,
                columns = listOf(
                    ColumnProfile(
                        name = "id", dbType = "integer", logicalType = LogicalType.INTEGER,
                        nullable = false, rowCount = 100, nonNullCount = 100, nullCount = 0,
                        distinctCount = 100, duplicateValueCount = 0,
                    ),
                    ColumnProfile(
                        name = "email", dbType = "varchar(254)", logicalType = LogicalType.STRING,
                        nullable = true, rowCount = 100, nonNullCount = 95, nullCount = 5,
                        distinctCount = 90, duplicateValueCount = 5,
                        emptyStringCount = 2, blankStringCount = 1,
                        topValues = listOf(
                            ValueFrequency("alice@example.com", 3, 0.03),
                            ValueFrequency("bob@example.com", 2, 0.02),
                        ),
                        warnings = listOf(
                            ProfileWarning(WarningCode.CONTAINS_EMPTY_STRINGS, "2 empty strings", Severity.WARN),
                        ),
                    ),
                ),
            ),
        ),
    )

    test("JSON contains all core fields") {
        val json = writer.renderJson(profile)
        json shouldContain "\"databaseProduct\": \"PostgreSQL\""
        json shouldContain "\"databaseVersion\": \"16.1\""
        json shouldContain "\"name\": \"users\""
        json shouldContain "\"rowCount\": 100"
        json shouldContain "\"name\": \"email\""
        json shouldContain "\"logicalType\": \"STRING\""
        json shouldContain "\"emptyStringCount\": 2"
        json shouldContain "\"topValues\""
        json shouldContain "alice@example.com"
    }

    test("YAML contains all core fields") {
        val yaml = writer.renderYaml(profile)
        yaml shouldContain "databaseProduct: PostgreSQL"
        yaml shouldContain "databaseVersion: 16.1"
        yaml shouldContain "name: users"
        yaml shouldContain "rowCount: 100"
        yaml shouldContain "name: email"
        yaml shouldContain "logicalType: STRING"
        yaml shouldContain "emptyStringCount: 2"
        yaml shouldContain "topValues:"
        yaml shouldContain "alice@example.com"
    }

    test("JSON and YAML contain same warnings") {
        val json = writer.renderJson(profile)
        val yaml = writer.renderYaml(profile)
        json shouldContain "CONTAINS_EMPTY_STRINGS"
        yaml shouldContain "CONTAINS_EMPTY_STRINGS"
        json shouldContain "2 empty strings"
        yaml shouldContain "2 empty strings"
    }

    test("no generatedAt in output") {
        val json = writer.renderJson(profile)
        val yaml = writer.renderYaml(profile)
        json shouldNotContain "generatedAt"
        yaml shouldNotContain "generatedAt"
    }

    test("deterministic output — same input same result") {
        val a = writer.renderJson(profile)
        val b = writer.renderJson(profile)
        a shouldBe b
    }

    test("JSON and YAML carry identical field set for rich profile") {
        val rich = DatabaseProfile(
            databaseProduct = "PostgreSQL",
            databaseVersion = "16.1",
            schemaName = "myschema",
            tables = listOf(
                TableProfile(
                    name = "orders",
                    rowCount = 50,
                    columns = listOf(
                        ColumnProfile(
                            name = "total", dbType = "numeric(10,2)", logicalType = LogicalType.DECIMAL,
                            nullable = false, rowCount = 50, nonNullCount = 50, nullCount = 0,
                            distinctCount = 45, duplicateValueCount = 5,
                            minLength = 3, maxLength = 8,
                            minValue = "1.50", maxValue = "999.99",
                            topValues = listOf(ValueFrequency("42.00", 3, 0.06)),
                            numericStats = dev.dmigrate.profiling.model.NumericStats(
                                min = 1.5, max = 999.99, avg = 123.45, sum = 6172.5,
                                stddev = 87.3, zeroCount = 0, negativeCount = 0,
                            ),
                            targetCompatibility = listOf(
                                dev.dmigrate.profiling.model.TargetTypeCompatibility(
                                    targetType = dev.dmigrate.profiling.types.TargetLogicalType.INTEGER,
                                    checkedValueCount = 50, compatibleCount = 0, incompatibleCount = 50,
                                    exampleInvalidValues = listOf("1.50", "42.00", "999.99"),
                                    determinationStatus = dev.dmigrate.profiling.model.DeterminationStatus.FULL_SCAN,
                                ),
                            ),
                            warnings = listOf(
                                ProfileWarning(WarningCode.DUPLICATE_VALUES, "5 duplicates", Severity.INFO),
                            ),
                        ),
                        ColumnProfile(
                            name = "created", dbType = "timestamp", logicalType = LogicalType.DATETIME,
                            nullable = true, rowCount = 50, nonNullCount = 48, nullCount = 2,
                            distinctCount = 48, duplicateValueCount = 0,
                            temporalStats = dev.dmigrate.profiling.model.TemporalStats(
                                minTimestamp = "2024-01-01T00:00:00",
                                maxTimestamp = "2026-04-15T12:00:00",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val json = writer.renderJson(rich)
        val yaml = writer.renderYaml(rich)

        // Every field present in JSON must also be present in YAML
        val fields = listOf(
            "databaseProduct", "databaseVersion", "schemaName",
            "name", "rowCount", "dbType", "logicalType", "nullable",
            "nonNullCount", "nullCount", "distinctCount", "duplicateValueCount",
            "minLength", "maxLength", "minValue", "maxValue",
            "topValues", "42.00",
            "numericStats", "stddev", "zeroCount", "negativeCount",
            "temporalStats", "minTimestamp", "maxTimestamp",
            "2024-01-01", "2026-04-15",
            "targetCompatibility", "checkedValueCount", "compatibleCount",
            "incompatibleCount", "determinationStatus", "FULL_SCAN",
            "exampleInvalidValues", "1.50", "999.99",
            "warnings", "DUPLICATE_VALUES", "5 duplicates",
        )

        for (field in fields) {
            io.kotest.assertions.withClue("JSON missing: $field") { json shouldContain field }
            io.kotest.assertions.withClue("YAML missing: $field") { yaml shouldContain field }
        }
    }
})
