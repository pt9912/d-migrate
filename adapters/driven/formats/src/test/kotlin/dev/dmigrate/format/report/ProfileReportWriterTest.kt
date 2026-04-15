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
})
