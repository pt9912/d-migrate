package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.ViewQueryTransformer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Was SQLite an einem fremden View-Rumpf umschreibt. */
class SqliteViewPortabilityRulesTest : FunSpec({

    test("SQLite: NOW() transforms to datetime('now')") {
        val transformer = ViewQueryTransformer(SqliteViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT NOW() FROM t", "postgresql")
        result shouldBe "SELECT datetime('now') FROM t"
    }
    test("SQLite: CURRENT_TIMESTAMP transforms to datetime('now')") {
        val transformer = ViewQueryTransformer(SqliteViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT CURRENT_TIMESTAMP FROM t", "postgresql")
        result shouldBe "SELECT datetime('now') FROM t"
    }
    test("SQLite: CONCAT transforms to ||") {
        val transformer = ViewQueryTransformer(SqliteViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT CONCAT(first, last) FROM t", "postgresql")
        result shouldBe "SELECT first || last FROM t"
    }
    test("SQLite: SUBSTRING FROM FOR transforms to SUBSTR") {
        val transformer = ViewQueryTransformer(SqliteViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT SUBSTRING(name FROM 1 FOR 3) FROM t", "postgresql")
        result shouldBe "SELECT SUBSTR(name, 1, 3) FROM t"
    }
    test("SQLite: EXTRACT MONTH FROM transforms to CAST(strftime('%m', ...) AS INTEGER)") {
        val transformer = ViewQueryTransformer(SqliteViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT EXTRACT(MONTH FROM created_at) FROM t", "postgresql")
        result shouldBe "SELECT CAST(strftime('%m', created_at) AS INTEGER) FROM t"
    }
    test("SQLite: TRUE transforms to 1") {
        val transformer = ViewQueryTransformer(SqliteViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT * FROM t WHERE active = TRUE", "postgresql")
        result shouldBe "SELECT * FROM t WHERE active = 1"
    }

    // ── PostgreSQL transformations ───────────────
})
