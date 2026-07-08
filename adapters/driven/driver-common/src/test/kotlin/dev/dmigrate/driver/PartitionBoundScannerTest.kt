package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Gemeinsamer Top-Level-Splitter für Partitionsgrenzen (AP6-Review P3 #9). Deckt die
 * Vereinigung beider Dialekt-Formen ab — `'`-/`` ` ``-Quotes, `()`/`[]`-Klammern,
 * verdoppelte (escapte) Quotes.
 */
class PartitionBoundScannerTest : FunSpec({

    test("splits a plain comma-separated tuple") {
        PartitionBoundScanner.splitTopLevel("10, 'x'") shouldBe listOf("10", "'x'")
    }

    test("commas inside single-quoted literals are protected") {
        PartitionBoundScanner.splitTopLevel("'a, b', 'c'") shouldBe listOf("'a, b'", "'c'")
    }

    test("commas inside backtick-quoted identifiers are protected (MySQL form)") {
        PartitionBoundScanner.splitTopLevel("`a,b`, `c`") shouldBe listOf("`a,b`", "`c`")
    }

    test("commas inside parentheses and brackets are protected") {
        PartitionBoundScanner.splitTopLevel("(1, 2), [3, 4]") shouldBe listOf("(1, 2)", "[3, 4]")
    }

    test("a doubled (escaped) single quote keeps the literal balanced") {
        PartitionBoundScanner.splitTopLevel("'O''Brien, jr', 'x'") shouldBe listOf("'O''Brien, jr'", "'x'")
    }

    test("blank tokens are dropped and tokens are trimmed") {
        PartitionBoundScanner.splitTopLevel("  a , , b ") shouldBe listOf("a", "b")
    }

    test("an empty input yields an empty list") {
        PartitionBoundScanner.splitTopLevel("") shouldBe emptyList()
    }
})
