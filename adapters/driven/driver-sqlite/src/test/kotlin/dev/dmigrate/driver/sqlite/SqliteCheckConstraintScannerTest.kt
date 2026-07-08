package dev.dmigrate.driver.sqlite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SqliteCheckConstraintScannerTest : FunSpec({

    fun named(sql: String) = SqliteCheckConstraintScanner.scan(sql).named

    test("finds named constraints") {
        val sql = """CREATE TABLE t (
            id INTEGER PRIMARY KEY,
            age INTEGER,
            CONSTRAINT chk_age CHECK (age > 0),
            CONSTRAINT chk_range CHECK (age < 200)
        )"""
        named(sql) shouldBe listOf("chk_age" to "age > 0", "chk_range" to "age < 200")
    }

    test("returns empty for no constraints") {
        named("CREATE TABLE t (id INTEGER PRIMARY KEY)").size shouldBe 0
    }

    test("keeps the full expression of an IN list (balanced parens)") {
        // Regression: the old non-greedy regex truncated at the first `)`,
        // losing the tail of every expression with an inner paren.
        val sql = """CREATE TABLE t (p TEXT, CONSTRAINT chk CHECK (p IN ('a','b')))"""
        named(sql).single() shouldBe ("chk" to "p IN ('a','b')")
    }

    test("balances nested parentheses") {
        val sql = "CREATE TABLE t (a INT, b INT, c INT, CONSTRAINT chk CHECK ((a+b) > (c*2)))"
        named(sql).single() shouldBe ("chk" to "(a+b) > (c*2)")
    }

    test("ignores parens and escaped quotes inside string literals") {
        val sql = "CREATE TABLE t (n TEXT, CONSTRAINT chk CHECK (n != 'a(b' AND n != 'it''s (ok)'))"
        named(sql).single() shouldBe ("chk" to "n != 'a(b' AND n != 'it''s (ok)'")
    }

    test("handles multiple constraints incl. IN lists") {
        val sql = """CREATE TABLE t (
            p TEXT, q INTEGER,
            CONSTRAINT chk_p CHECK (p IN ('x','y','z')),
            CONSTRAINT chk_q CHECK (q > 0)
        )"""
        named(sql) shouldBe listOf("chk_p" to "p IN ('x','y','z')", "chk_q" to "q > 0")
    }

    test("unquotes names incl. spaces, doubled quotes, backticks, brackets") {
        val sql = """CREATE TABLE t (x INT,
            CONSTRAINT "my chk" CHECK (x > 0),
            CONSTRAINT "dq""name" CHECK (x < 100),
            CONSTRAINT `tick` CHECK (x < 9),
            CONSTRAINT [br] CHECK (x != 5))"""
        named(sql).map { it.first } shouldBe listOf("my chk", "dq\"name", "tick", "br")
    }

    test("skips a never-balancing CHECK instead of hanging or truncating") {
        val sql = "CREATE TABLE t (x INT, CONSTRAINT chk CHECK ((x > 0)" // truncated DDL
        named(sql).size shouldBe 0
    }

    test("separates unnamed column/table checks from named ones") {
        val sql = """CREATE TABLE t (
            age INTEGER CHECK (age >= 0),
            p TEXT,
            CONSTRAINT chk CHECK (p IN ('a','b')),
            CHECK (age < 200)
        )"""
        val scan = SqliteCheckConstraintScanner.scan(sql)
        scan.named shouldBe listOf("chk" to "p IN ('a','b')")
        scan.unnamedExpressions shouldBe listOf("age >= 0", "age < 200")
    }

    test("does not fire on CHECK inside a quoted identifier") {
        val sql = """CREATE TABLE t ("check list" TEXT, CONSTRAINT chk CHECK ("check list" != ''))"""
        val scan = SqliteCheckConstraintScanner.scan(sql)
        scan.named shouldBe listOf("chk" to "\"check list\" != ''")
        scan.unnamedExpressions shouldBe emptyList<String>()
    }

    test("does not fire on CHECK inside a string literal or as identifier substring") {
        val sql = "CREATE TABLE t (note TEXT DEFAULT 'run CHECK (later)', pre_check INTEGER)"
        val scan = SqliteCheckConstraintScanner.scan(sql)
        scan.named shouldBe emptyList<Pair<String, String>>()
        scan.unnamedExpressions shouldBe emptyList<String>()
    }
})
