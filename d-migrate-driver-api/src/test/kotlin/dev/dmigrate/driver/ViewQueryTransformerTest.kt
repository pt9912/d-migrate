package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ViewQueryTransformerTest : FunSpec({

    // ── MySQL transformations ────────────────────

    test("MySQL: DATE_TRUNC month transforms to DATE_FORMAT") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC('month', created_at) FROM t", "postgresql")
        result shouldBe "SELECT DATE_FORMAT(created_at, '%Y-%m-01') FROM t"
    }

    test("MySQL: DATE_TRUNC year transforms to DATE_FORMAT") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC('year', created_at) FROM t", "postgresql")
        result shouldBe "SELECT DATE_FORMAT(created_at, '%Y-01-01') FROM t"
    }

    test("MySQL: EXTRACT YEAR FROM transforms to YEAR()") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT EXTRACT(YEAR FROM created_at) FROM t", "postgresql")
        result shouldBe "SELECT YEAR(created_at) FROM t"
    }

    test("MySQL: LENGTH transforms to CHAR_LENGTH") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT LENGTH(name) FROM t", "postgresql")
        result shouldBe "SELECT CHAR_LENGTH(name) FROM t"
    }

    test("MySQL: CHAR_LENGTH is not corrupted to CHAR_CHAR_LENGTH") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT CHAR_LENGTH(name) FROM t", "postgresql")
        result shouldBe "SELECT CHAR_LENGTH(name) FROM t"
    }

    test("MySQL: TRUE and FALSE transform to 1 and 0") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT * FROM t WHERE active = TRUE AND deleted = FALSE", "postgresql")
        result shouldBe "SELECT * FROM t WHERE active = 1 AND deleted = 0"
    }

    test("MySQL: CURRENT_DATE transforms to CURDATE()") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT CURRENT_DATE FROM t", "postgresql")
        result shouldBe "SELECT CURDATE() FROM t"
    }

    test("MySQL: CURRENT_TIME transforms to CURTIME()") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT CURRENT_TIME FROM t", "postgresql")
        result shouldBe "SELECT CURTIME() FROM t"
    }

    // ── SQLite transformations ───────────────────

    test("SQLite: NOW() transforms to datetime('now')") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT NOW() FROM t", "postgresql")
        result shouldBe "SELECT datetime('now') FROM t"
    }

    test("SQLite: CURRENT_TIMESTAMP transforms to datetime('now')") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT CURRENT_TIMESTAMP FROM t", "postgresql")
        result shouldBe "SELECT datetime('now') FROM t"
    }

    test("SQLite: CONCAT transforms to ||") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT CONCAT(first, last) FROM t", "postgresql")
        result shouldBe "SELECT first || last FROM t"
    }

    test("SQLite: SUBSTRING FROM FOR transforms to SUBSTR") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT SUBSTRING(name FROM 1 FOR 3) FROM t", "postgresql")
        result shouldBe "SELECT SUBSTR(name, 1, 3) FROM t"
    }

    test("SQLite: TRUE transforms to 1") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT * FROM t WHERE active = TRUE", "postgresql")
        result shouldBe "SELECT * FROM t WHERE active = 1"
    }

    // ── PostgreSQL transformations ───────────────

    test("PostgreSQL: NOW() transforms to CURRENT_TIMESTAMP") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        val (result, _) = transformer.transform("SELECT NOW() FROM t", null)
        result shouldBe "SELECT CURRENT_TIMESTAMP FROM t"
    }

    // ── Unknown function detection ──────────────

    test("Unknown function IFNULL produces W111 warning") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (_, notes) = transformer.transform("SELECT IFNULL(a, b) FROM t", "postgresql")
        notes shouldHaveSize 1
        notes[0].code shouldBe "W111"
        notes[0].message shouldContain "IFNULL"
    }

    test("Known functions like COUNT and SUM produce no W111 warning") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (_, notes) = transformer.transform("SELECT COUNT(*), SUM(amount) FROM t", "postgresql")
        notes.shouldBeEmpty()
    }

    test("Same dialect produces no W111 warning") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (_, notes) = transformer.transform("SELECT IFNULL(a, b) FROM t", "mysql")
        notes.shouldBeEmpty()
    }
})
