package dev.dmigrate.core.diff.migration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Plan-2 §F.4 dependency-projection T1: pins the version parser so
 * policies can safely write `parsed >= MIN_SQLITE_3_26` instead of
 * lexicographic string comparison (`3.9` vs `3.26` would otherwise
 * sort in the wrong direction).
 */
class RenameProjectionVersionParserTest : FunSpec({

    test("parses bare major") {
        RenameProjectionVersionParser.parse("3") shouldBe ParsedRenameVersion(3)
    }

    test("major + minor expands patch to 0") {
        RenameProjectionVersionParser.parse("3.9") shouldBe ParsedRenameVersion(3, 9, 0)
    }

    test("major + minor + patch") {
        RenameProjectionVersionParser.parse("3.26.0") shouldBe ParsedRenameVersion(3, 26, 0)
        RenameProjectionVersionParser.parse("8.0.30") shouldBe ParsedRenameVersion(8, 0, 30)
    }

    test("3.9 sorts BEFORE 3.26 (the lexicographic-trap case)") {
        val v39 = RenameProjectionVersionParser.parse("3.9")!!
        val v326 = RenameProjectionVersionParser.parse("3.26")!!
        v39 shouldBeLessThan v326
        v326 shouldBeGreaterThan v39
    }

    test("8.0.30 sorts after 8.0.3 (numeric, not lexicographic)") {
        val v8030 = RenameProjectionVersionParser.parse("8.0.30")!!
        val v803 = RenameProjectionVersionParser.parse("8.0.3")!!
        v803 shouldBeLessThan v8030
    }

    test("MariaDB suffix is preserved (lowercased) and orders after empty-suffix sibling") {
        val plain = RenameProjectionVersionParser.parse("10.11.5")!!
        val mariadb = RenameProjectionVersionParser.parse("10.11.5-MariaDB")!!
        mariadb.suffix shouldBe "mariadb"
        plain shouldBeLessThan mariadb
    }

    test("blank, null and unparseable values return null (treated as unknown capability)") {
        RenameProjectionVersionParser.parse(null).shouldBeNull()
        RenameProjectionVersionParser.parse("").shouldBeNull()
        RenameProjectionVersionParser.parse("   ").shouldBeNull()
        RenameProjectionVersionParser.parse("abc").shouldBeNull()
        RenameProjectionVersionParser.parse("v1").shouldBeNull()
        RenameProjectionVersionParser.parse("1.x").shouldBeNull()
    }

    test("trailing dot and four-segment versions are rejected (not real engine strings)") {
        // A dot after the patch component is not a recognised suffix
        // separator — the parser refuses rather than guessing.
        RenameProjectionVersionParser.parse("3.").shouldBeNull()
        RenameProjectionVersionParser.parse("3.9.0.0").shouldBeNull()
        RenameProjectionVersionParser.parse("1.0.0.RELEASE").shouldBeNull()
    }

    test("build-metadata suffix after + is preserved (semver-style)") {
        val parsed = RenameProjectionVersionParser.parse("8.4.0+build.123")!!
        parsed shouldBe ParsedRenameVersion(8, 4, 0, suffix = "build.123")
    }
})
