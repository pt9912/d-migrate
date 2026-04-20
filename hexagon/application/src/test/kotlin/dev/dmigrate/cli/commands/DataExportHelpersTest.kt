package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.streaming.ExportResult
import dev.dmigrate.streaming.TableExportSummary
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * Unit-Tests für [DataExportHelpers] — die reinen Helfer-Funktionen, die
 * [DataExportCommand] aus seiner `run()`-Methode aufruft.
 *
 * Diese Tests laufen ohne Clikt-Kontext, ohne Connection-Pool und ohne
 * Datenbank — sie decken die Verzweigungslogik ab, die vorher nur über
 * die teureren E2E-Round-Trip-Tests erreichbar war.
 */
class DataExportHelpersTest : FunSpec({

    // ─── TABLE_IDENTIFIER / firstInvalidTableIdentifier ───────────

    context("firstInvalidTableIdentifier") {

        test("accepts simple identifiers") {
            DataExportHelpers.firstInvalidTableIdentifier(listOf("users")).shouldBeNull()
            DataExportHelpers.firstInvalidTableIdentifier(listOf("users", "orders")).shouldBeNull()
            DataExportHelpers.firstInvalidTableIdentifier(listOf("_private")).shouldBeNull()
            DataExportHelpers.firstInvalidTableIdentifier(listOf("Users42")).shouldBeNull()
        }

        test("accepts schema-qualified identifiers") {
            DataExportHelpers.firstInvalidTableIdentifier(listOf("public.users")).shouldBeNull()
            DataExportHelpers.firstInvalidTableIdentifier(listOf("my_schema.table_1")).shouldBeNull()
        }

        test("rejects identifier with whitespace") {
            DataExportHelpers.firstInvalidTableIdentifier(listOf("weird name")) shouldBe "weird name"
        }

        test("rejects identifier with SQL injection attempt") {
            DataExportHelpers.firstInvalidTableIdentifier(
                listOf("users; DROP TABLE users")
            ) shouldBe "users; DROP TABLE users"
        }

        test("rejects identifier with hyphen") {
            DataExportHelpers.firstInvalidTableIdentifier(listOf("user-table")) shouldBe "user-table"
        }

        test("rejects identifier starting with digit") {
            DataExportHelpers.firstInvalidTableIdentifier(listOf("1users")) shouldBe "1users"
        }

        test("rejects empty identifier") {
            DataExportHelpers.firstInvalidTableIdentifier(listOf("")) shouldBe ""
        }

        test("rejects identifier with three segments (schema.schema.table)") {
            DataExportHelpers.firstInvalidTableIdentifier(listOf("a.b.c")) shouldBe "a.b.c"
        }

        test("returns the FIRST invalid entry when multiple are present") {
            DataExportHelpers.firstInvalidTableIdentifier(
                listOf("users", "bad-one", "worse one")
            ) shouldBe "bad-one"
        }

        test("empty list → null (no invalid entries)") {
            DataExportHelpers.firstInvalidTableIdentifier(emptyList()).shouldBeNull()
        }
    }

    context("firstInvalidQualifiedIdentifier") {

        test("accepts the same qualified identifier grammar as --tables") {
            DataExportHelpers.firstInvalidQualifiedIdentifier("updated_at").shouldBeNull()
            DataExportHelpers.firstInvalidQualifiedIdentifier("public.updated_at").shouldBeNull()
        }

        test("rejects invalid --since-column values") {
            DataExportHelpers.firstInvalidQualifiedIdentifier("bad column") shouldBe "bad column"
            DataExportHelpers.firstInvalidQualifiedIdentifier("a.b.c") shouldBe "a.b.c"
        }
    }

    // ─── parseCsvDelimiter ────────────────────────────────────────

    context("parseCsvDelimiter") {

        test("single character returns that char") {
            DataExportHelpers.parseCsvDelimiter(",") shouldBe ','
            DataExportHelpers.parseCsvDelimiter(";") shouldBe ';'
            DataExportHelpers.parseCsvDelimiter("\t") shouldBe '\t'
            DataExportHelpers.parseCsvDelimiter("|") shouldBe '|'
        }

        test("empty string returns null") {
            DataExportHelpers.parseCsvDelimiter("").shouldBeNull()
        }

        test("multi-character string returns null") {
            DataExportHelpers.parseCsvDelimiter("::").shouldBeNull()
            DataExportHelpers.parseCsvDelimiter(",,").shouldBeNull()
            DataExportHelpers.parseCsvDelimiter("abc").shouldBeNull()
        }
    }

    // ─── resolveFilter ────────────────────────────────────────────

    context("resolveFilter") {

        test("null returns null") {
            DataExportHelpers.resolveFilter(null).shouldBeNull()
        }

        test("empty string returns null") {
            DataExportHelpers.resolveFilter("").shouldBeNull()
        }

        test("whitespace-only returns null") {
            DataExportHelpers.resolveFilter("   ").shouldBeNull()
            DataExportHelpers.resolveFilter("\t\n").shouldBeNull()
        }

        test("non-blank DSL string produces ParameterizedClause") {
            val filter = DataExportHelpers.resolveFilter(
                rawFilter = "id = 42",
                dialect = DatabaseDialect.POSTGRESQL,
            )
            filter.shouldNotBeNull()
            val clause = filter.shouldBeInstanceOf<DataFilter.ParameterizedClause>()
            clause.sql shouldBe "\"id\" = ?"
            clause.params shouldBe listOf(42L)
        }

        test("whitespace around DSL is trimmed before parsing") {
            val filter = DataExportHelpers.resolveFilter(
                rawFilter = "  id = 42  ",
                dialect = DatabaseDialect.POSTGRESQL,
            )
            filter.shouldNotBeNull()
            val clause = filter.shouldBeInstanceOf<DataFilter.ParameterizedClause>()
            clause.sql shouldBe "\"id\" = ?"
            clause.params shouldBe listOf(42L)
        }

        test("invalid DSL throws FilterResolveException") {
            shouldThrow<DataExportHelpers.FilterResolveException> {
                DataExportHelpers.resolveFilter(
                    rawFilter = "LIMIT 10",
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            }
        }

        test("builds a parameterized --since clause when no raw filter is present") {
            val filter = DataExportHelpers.resolveFilter(
                rawFilter = null,
                dialect = DatabaseDialect.SQLITE,
                sinceColumn = "updated_at",
                since = "2026-01-01",
            )
            val clause = filter.shouldBeInstanceOf<DataFilter.ParameterizedClause>()
            clause.sql shouldBe "\"updated_at\" >= ?"
            clause.params shouldBe listOf(LocalDate.parse("2026-01-01"))
        }

        test("combines DSL filter and --since into a Compound") {
            val filter = DataExportHelpers.resolveFilter(
                rawFilter = "active = 1",
                dialect = DatabaseDialect.MYSQL,
                sinceColumn = "audit.updated_at",
                since = "2026-01-01T10:15:30",
            )
            val compound = filter.shouldBeInstanceOf<DataFilter.Compound>()
            val dslPart = compound.parts[0].shouldBeInstanceOf<DataFilter.ParameterizedClause>()
            dslPart.sql shouldBe "`active` = ?"
            dslPart.params shouldBe listOf(1L)
            val marker = compound.parts[1].shouldBeInstanceOf<DataFilter.ParameterizedClause>()
            marker.sql shouldBe "`audit`.`updated_at` >= ?"
            marker.params shouldBe listOf(LocalDateTime.parse("2026-01-01T10:15:30"))
        }
    }

    // containsLiteralQuestionMark removed in 0.9.3: --filter now produces
    // ParameterizedClause via DSL parser, M-R5 check is obsolete.

    context("canonicalizeFilter") {

        test("returns null for null or blank") {
            DataExportHelpers.canonicalizeFilter(null) shouldBe null
            DataExportHelpers.canonicalizeFilter("") shouldBe null
            DataExportHelpers.canonicalizeFilter("   ") shouldBe null
        }

        test("normalizes keyword case and whitespace") {
            DataExportHelpers.canonicalizeFilter("status = 'OPEN'  and  active = true") shouldBe
                "status = 'OPEN' AND active = true"
        }

        test("throws FilterResolveException for invalid DSL") {
            shouldThrow<DataExportHelpers.FilterResolveException> {
                DataExportHelpers.canonicalizeFilter("CONCAT(a, b) = 'x'")
            }
        }
    }

    context("parseSinceLiteral") {

        test("parses ISO dates and datetimes into typed values") {
            DataExportHelpers.parseSinceLiteral("2026-01-01") shouldBe LocalDate.parse("2026-01-01")
            DataExportHelpers.parseSinceLiteral("2026-01-01T10:15:30") shouldBe LocalDateTime.parse("2026-01-01T10:15:30")
            DataExportHelpers.parseSinceLiteral("2026-01-01T10:15:30+01:00") shouldBe
                OffsetDateTime.parse("2026-01-01T10:15:30+01:00")
        }

        test("parses integer and decimal numerics conservatively") {
            DataExportHelpers.parseSinceLiteral("42") shouldBe 42L
            DataExportHelpers.parseSinceLiteral("3.14") shouldBe BigDecimal("3.14")
            DataExportHelpers.parseSinceLiteral("9223372036854775808123") shouldBe
                BigDecimal("9223372036854775808123")
        }

        test("falls back to raw string for non-typed values") {
            DataExportHelpers.parseSinceLiteral("release-42") shouldBe "release-42"
        }

        // 0.8.0 Phase E (docs/ImpPlan-0.8.0-E.md §4.4):
        // Keine Auto-Zonierung — ein lokaler ISO-DateTime bleibt
        // LocalDateTime, auch wenn die JVM-Default-Zone abweicht oder
        // `ResolvedI18nSettings.timezone` gesetzt waere.
        test("Phase E §4.4: lokaler DateTime bleibt LocalDateTime (keine Default-TZ-Injektion)") {
            val result = DataExportHelpers.parseSinceLiteral("2026-06-15T12:00:00")
            result.shouldBeInstanceOf<LocalDateTime>()
            result shouldBe LocalDateTime.of(2026, 6, 15, 12, 0, 0)
        }

        test("Phase E §4.2: Offset-Input bleibt OffsetDateTime") {
            val result = DataExportHelpers.parseSinceLiteral("2026-06-15T12:00:00+02:00")
            result.shouldBeInstanceOf<OffsetDateTime>()
        }
    }

    // ─── formatProgressSummary ────────────────────────────────────

    context("formatProgressSummary") {

        fun result(
            tableCount: Int = 1,
            totalRows: Long = 100,
            totalBytes: Long = 2 * 1024 * 1024,
            durationMs: Long = 1_500,
        ): ExportResult = ExportResult(
            tables = List(tableCount) { i ->
                TableExportSummary(
                    table = "t$i",
                    rows = totalRows / tableCount,
                    chunks = 1,
                    bytes = totalBytes / tableCount,
                    durationMs = durationMs / tableCount,
                )
            },
            totalRows = totalRows,
            totalChunks = tableCount.toLong(),
            totalBytes = totalBytes,
            durationMs = durationMs,
        )

        test("includes the 'Exported N table(s)' prefix") {
            val summary = DataExportHelpers.formatProgressSummary(result(tableCount = 3))
            summary shouldStartWith "Exported 3 table(s)"
        }

        test("includes the total row count") {
            val summary = DataExportHelpers.formatProgressSummary(result(totalRows = 42))
            summary shouldContain "42 rows"
        }

        test("converts bytes to MB with stable Locale.US formatting") {
            // 5 * 1024 * 1024 bytes = 5.00 MB
            val summary = DataExportHelpers.formatProgressSummary(
                result(totalBytes = 5L * 1024 * 1024)
            )
            summary shouldContain "5.00 MB"
        }

        test("zero-byte result still formats without crash") {
            val summary = DataExportHelpers.formatProgressSummary(
                result(totalBytes = 0, durationMs = 0)
            )
            summary shouldContain "Exported"
            summary shouldContain "MB"
            summary shouldContain "s"
        }

        test("single-table result uses singular 'table(s)' with count 1") {
            val summary = DataExportHelpers.formatProgressSummary(result(tableCount = 1))
            summary shouldContain "Exported 1 table(s)"
        }
    }

    // ─── TABLE_IDENTIFIER_PATTERN constant ────────────────────────

    test("TABLE_IDENTIFIER_PATTERN is the documented regex from Plan §6.7") {
        DataExportHelpers.TABLE_IDENTIFIER_PATTERN shouldBe
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$"
    }
})
