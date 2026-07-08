package dev.dmigrate.driver.sqlite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class SqliteUniqueConstraintScannerTest : FunSpec({

    test("named table-level UNIQUE clause yields name + columns") {
        val scan = SqliteUniqueConstraintScanner.scan(
            """CREATE TABLE "t" ("a" TEXT, "b" TEXT, CONSTRAINT "uq_ab" UNIQUE ("a", "b"))""",
        )
        scan shouldBe listOf(SqliteUniqueConstraintScanner.UniqueClause("uq_ab", listOf("a", "b")))
    }

    test("unnamed table-level UNIQUE clause yields null name") {
        val scan = SqliteUniqueConstraintScanner.scan(
            "CREATE TABLE t (a TEXT, b TEXT, UNIQUE (a, b))",
        )
        scan shouldBe listOf(SqliteUniqueConstraintScanner.UniqueClause(null, listOf("a", "b")))
    }

    test("column-level UNIQUE (no paren group) is not matched") {
        SqliteUniqueConstraintScanner.scan(
            "CREATE TABLE t (a TEXT UNIQUE, b TEXT)",
        ).shouldBeEmpty()
    }

    test("backtick/bracket identifiers and COLLATE/DESC decorations are normalised") {
        val scan = SqliteUniqueConstraintScanner.scan(
            "CREATE TABLE t (a TEXT, b TEXT, CONSTRAINT `uq x` UNIQUE (`a` COLLATE NOCASE, [b] DESC))",
        )
        scan shouldBe listOf(SqliteUniqueConstraintScanner.UniqueClause("uq x", listOf("a", "b")))
    }

    test("UNIQUE inside string literals or CHECK expressions is ignored") {
        val scan = SqliteUniqueConstraintScanner.scan(
            """CREATE TABLE t (a TEXT DEFAULT 'UNIQUE (x)', CONSTRAINT c CHECK (a <> 'UNIQUE (y)'), UNIQUE (a))""",
        )
        scan shouldBe listOf(SqliteUniqueConstraintScanner.UniqueClause(null, listOf("a")))
    }

    test("multiple clauses scan in declaration order") {
        val scan = SqliteUniqueConstraintScanner.scan(
            "CREATE TABLE t (a TEXT, b TEXT, c TEXT, CONSTRAINT uq1 UNIQUE (a, b), UNIQUE (b, c))",
        )
        scan shouldBe listOf(
            SqliteUniqueConstraintScanner.UniqueClause("uq1", listOf("a", "b")),
            SqliteUniqueConstraintScanner.UniqueClause(null, listOf("b", "c")),
        )
    }
})
