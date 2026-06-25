package dev.dmigrate.driver.postgresql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDate

/**
 * COPY-TEXT-Encoder für den COPY-Bulk-Fast-Path (import-throughput-copy-path.md). Der Encoder
 * ist korrektheits-kritisch (Fehler = stille Datenkorruption) — daher dicht unit-getestet.
 */
class PostgresCopyTextTest : FunSpec({

    test("null becomes the COPY NULL marker \\N") {
        PostgresCopyText.field(null) shouldBe "\\N"
    }

    test("a literal '\\N' string is NOT the NULL marker (backslash escaped first)") {
        // "\N" -> '\' -> "\\", 'N' -> 'N' => "\\N" (literal), distinct from the NULL marker \N.
        PostgresCopyText.field("\\N") shouldBe "\\\\N"
    }

    test("plain scalars use their canonical text representation") {
        PostgresCopyText.field(42) shouldBe "42"
        PostgresCopyText.field(9_000_000_000L) shouldBe "9000000000"
        PostgresCopyText.field("hello") shouldBe "hello"
        PostgresCopyText.field(LocalDate.of(2024, 1, 31)) shouldBe "2024-01-31"
    }

    test("BigDecimal uses toPlainString (never scientific notation)") {
        PostgresCopyText.field(BigDecimal("12.50")) shouldBe "12.50"
        PostgresCopyText.field(BigDecimal("1E+2")) shouldBe "100"
    }

    test("Boolean maps to PG canonical t/f") {
        PostgresCopyText.field(true) shouldBe "t"
        PostgresCopyText.field(false) shouldBe "f"
    }

    test("separator/escape characters in a string are escaped") {
        PostgresCopyText.field("a\tb") shouldBe "a\\tb"
        PostgresCopyText.field("a\nb") shouldBe "a\\nb"
        PostgresCopyText.field("a\rb") shouldBe "a\\rb"
        PostgresCopyText.field("a\\b") shouldBe "a\\\\b"
        // combined: backslash + tab + newline + CR in one value
        PostgresCopyText.field("x\\\ty\nz\r") shouldBe "x\\\\\\ty\\nz\\r"
    }

    test("other characters pass through unchanged (incl. unicode + quotes/commas)") {
        PostgresCopyText.field("Müller, O'Brien \"x\"") shouldBe "Müller, O'Brien \"x\""
    }

    test("encode joins columns with TAB and terminates each row with newline") {
        val rows = listOf(
            arrayOf<Any?>(1, "a", null),
            arrayOf<Any?>(2, "b\tc", BigDecimal("3.14")),
        )
        PostgresCopyText.encode(rows) shouldBe "1\ta\t\\N\n2\tb\\tc\t3.14\n"
    }

    test("empty chunk encodes to the empty string") {
        PostgresCopyText.encode(emptyList()) shouldBe ""
    }
})
