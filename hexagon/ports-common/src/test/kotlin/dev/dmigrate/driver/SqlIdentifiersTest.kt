package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Security tests for [SqlIdentifiers].
 *
 * Every test case uses deliberately malicious or edge-case identifier
 * values to verify that the quoting utility neutralises injection
 * attempts across all three dialects.
 */
class SqlIdentifiersTest : FunSpec({

    val edgeCases = listOf(
        "embedded quote" to "a\"b",
        "dotted name" to "tenant.users",
        "semicolon" to "users; DROP TABLE users",
        "keyword" to "select",
        "unicode" to "tаble",
        "empty" to "",
    )

    // ── quoteIdentifier: PostgreSQL / SQLite (double-quote) ────────

    test("PostgreSQL: simple name is double-quoted") {
        SqlIdentifiers.quoteIdentifier("users", DatabaseDialect.POSTGRESQL) shouldBe "\"users\""
    }

    test("PostgreSQL: embedded double-quote is escaped") {
        SqlIdentifiers.quoteIdentifier("""a"b""", DatabaseDialect.POSTGRESQL) shouldBe "\"a\"\"b\""
    }

    test("PostgreSQL: SQL injection via semicolon is neutralised") {
        val malicious = "users; DROP TABLE users --"
        val quoted = SqlIdentifiers.quoteIdentifier(malicious, DatabaseDialect.POSTGRESQL)
        quoted shouldBe "\"users; DROP TABLE users --\""
    }

    test("PostgreSQL: reserved word is safely quoted") {
        SqlIdentifiers.quoteIdentifier("select", DatabaseDialect.POSTGRESQL) shouldBe "\"select\""
    }

    test("PostgreSQL: Unicode homoglyph is passed through unchanged") {
        // Cyrillic 'а' (U+0430) looks like Latin 'a'
        SqlIdentifiers.quoteIdentifier("tаble", DatabaseDialect.POSTGRESQL) shouldBe "\"tаble\""
    }

    test("PostgreSQL: empty name produces empty quoted identifier") {
        SqlIdentifiers.quoteIdentifier("", DatabaseDialect.POSTGRESQL) shouldBe "\"\""
    }

    test("PostgreSQL: identifier contract covers edge-case names") {
        edgeCases.forEach { (_, name) ->
            val quoted = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.POSTGRESQL)
            quoted.first() shouldBe '"'
            quoted.last() shouldBe '"'
            quoted.substring(1, quoted.length - 1).replace("\"\"", "").contains("\"") shouldBe false
        }
    }

    // ── quoteIdentifier: MySQL (backtick) ──────────────────────────

    test("MySQL: simple name is backtick-quoted") {
        SqlIdentifiers.quoteIdentifier("users", DatabaseDialect.MYSQL) shouldBe "`users`"
    }

    test("MySQL: embedded backtick is escaped") {
        SqlIdentifiers.quoteIdentifier("a`b", DatabaseDialect.MYSQL) shouldBe "`a``b`"
    }

    test("MySQL: SQL injection via comment is neutralised") {
        val malicious = "users` /*"
        val quoted = SqlIdentifiers.quoteIdentifier(malicious, DatabaseDialect.MYSQL)
        quoted shouldBe "`users`` /*`"
    }

    test("MySQL: reserved word is safely quoted") {
        SqlIdentifiers.quoteIdentifier("select", DatabaseDialect.MYSQL) shouldBe "`select`"
    }

    test("MySQL: identifier contract covers edge-case names") {
        val mysqlCases = edgeCases + ("embedded backtick" to "a`b")

        mysqlCases.forEach { (_, name) ->
            val quoted = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.MYSQL)
            quoted.first() shouldBe '`'
            quoted.last() shouldBe '`'
            quoted.substring(1, quoted.length - 1).replace("``", "").contains("`") shouldBe false
        }
    }

    // ── quoteIdentifier: SQLite (same as PostgreSQL) ───────────────

    test("SQLite: embedded double-quote is escaped") {
        SqlIdentifiers.quoteIdentifier("""x"y""", DatabaseDialect.SQLITE) shouldBe "\"x\"\"y\""
    }

    test("SQLite: identifier contract covers edge-case names") {
        edgeCases.forEach { (_, name) ->
            val quoted = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.SQLITE)
            quoted.first() shouldBe '"'
            quoted.last() shouldBe '"'
            quoted.substring(1, quoted.length - 1).replace("\"\"", "").contains("\"") shouldBe false
        }
    }

    // ── quoteQualifiedIdentifier ───────────────────────────────────

    test("PostgreSQL: qualified name quotes each segment") {
        SqlIdentifiers.quoteQualifiedIdentifier("public.users", DatabaseDialect.POSTGRESQL) shouldBe
            "\"public\".\"users\""
    }

    test("MySQL: qualified name quotes each segment") {
        SqlIdentifiers.quoteQualifiedIdentifier("mydb.orders", DatabaseDialect.MYSQL) shouldBe
            "`mydb`.`orders`"
    }

    test("PostgreSQL: injection attempt in qualified name is neutralised") {
        // An attacker might try to break out of the identifier by embedding
        // a dot and quotes: schema"."table; DROP --
        // After split+quote, each segment's quotes are doubled, preventing breakout.
        val result = SqlIdentifiers.quoteQualifiedIdentifier("evil\".x", DatabaseDialect.POSTGRESQL)
        result shouldBe "\"evil\"\"\".\"x\""
    }

    // ── quoteStringLiteral ─────────────────────────────────────────

    test("simple string literal is single-quoted") {
        SqlIdentifiers.quoteStringLiteral("users") shouldBe "'users'"
    }

    test("embedded single-quote is escaped") {
        SqlIdentifiers.quoteStringLiteral("O'Brien") shouldBe "'O''Brien'"
    }

    test("SQL injection via single-quote breakout is neutralised") {
        val malicious = "users'); DROP TABLE users --"
        val quoted = SqlIdentifiers.quoteStringLiteral(malicious)
        quoted shouldBe "'users''); DROP TABLE users --'"
    }
})
