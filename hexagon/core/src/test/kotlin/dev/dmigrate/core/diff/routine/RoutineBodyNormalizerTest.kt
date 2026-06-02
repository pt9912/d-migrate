package dev.dmigrate.core.diff.routine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * E.1 Routine-Migration Slice A — pin the normaliser/hash contract:
 *
 * - CRLF and CR alike collapse to LF so cross-platform schema files
 *   share a hash.
 * - Trailing whitespace per line is stripped (editor noise).
 * - Leading/trailing blank lines disappear.
 * - A single trailing `;` is dropped (dialect-default formatting).
 * - Inner whitespace, comments and casing stay significant — the
 *   slice deliberately avoids semantic awareness.
 */
class RoutineBodyNormalizerTest : FunSpec({

    test("null and blank inputs produce null") {
        RoutineBodyNormalizer.normalise(null).shouldBeNull()
        RoutineBodyNormalizer.normalise("").shouldBeNull()
        RoutineBodyNormalizer.normalise("   \n\n ").shouldBeNull()
        RoutineBodyNormalizer.hash(null).shouldBeNull()
        RoutineBodyNormalizer.hash("   ").shouldBeNull()
    }

    test("CRLF and CR collapse to LF") {
        val crlf = "BEGIN\r\n  RETURN 1;\r\nEND"
        val cr = "BEGIN\r  RETURN 1;\rEND"
        val lf = "BEGIN\n  RETURN 1;\nEND"
        val expected = "BEGIN\n  RETURN 1;\nEND"
        RoutineBodyNormalizer.normalise(crlf) shouldBe expected
        RoutineBodyNormalizer.normalise(cr) shouldBe expected
        RoutineBodyNormalizer.normalise(lf) shouldBe expected
    }

    test("trailing whitespace per line is stripped") {
        val noisy = "BEGIN   \n  RETURN 1;\t\nEND  "
        RoutineBodyNormalizer.normalise(noisy) shouldBe "BEGIN\n  RETURN 1;\nEND"
    }

    test("trailing semicolon is dropped exactly once") {
        // Editor-style trailing `;` should not change the hash.
        RoutineBodyNormalizer.normalise("BEGIN RETURN 1; END;") shouldBe "BEGIN RETURN 1; END"
        // But internal semicolons stay.
        val withInternal = "BEGIN RETURN 1; RETURN 2; END;"
        RoutineBodyNormalizer.normalise(withInternal) shouldBe "BEGIN RETURN 1; RETURN 2; END"
    }

    test("hash is stable for cross-platform variants") {
        val lf = "BEGIN\n  RETURN 1;\nEND"
        val crlf = "BEGIN\r\n  RETURN 1;\r\nEND;"
        val trailing = "BEGIN\n  RETURN 1;\nEND\n\n"
        val expected = RoutineBodyNormalizer.hash(lf)
        RoutineBodyNormalizer.hash(crlf) shouldBe expected
        RoutineBodyNormalizer.hash(trailing) shouldBe expected
    }

    test("hash differs when inner whitespace or comments differ") {
        // E.1 carve-out: no semantic awareness. Operators changing
        // the body — even just a comment — see a ReplaceFunction.
        val a = "BEGIN RETURN 1; END"
        val b = "BEGIN /* tweak */ RETURN 1; END"
        RoutineBodyNormalizer.hash(a)?.shouldNotContain(RoutineBodyNormalizer.hash(b) ?: "?")
        (RoutineBodyNormalizer.hash(a) == RoutineBodyNormalizer.hash(b)) shouldBe false
    }
})
