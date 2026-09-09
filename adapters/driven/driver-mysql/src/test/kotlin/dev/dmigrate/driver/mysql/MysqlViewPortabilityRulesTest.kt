package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.ViewQueryTransformer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Was MySQL an einem fremden View-Rumpf umschreibt und was es ablehnt.
 *
 * Frueher Teil eines gemeinsamen Tests in `driver-common` — mit den Regeln
 * hierher gewandert, damit das Wissen und seine Zusicherungen im selben Modul
 * liegen.
 */
class MysqlViewPortabilityRulesTest : FunSpec({

    test("MySQL: DATE_TRUNC month transforms to DATE_FORMAT") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC('month', created_at) FROM t", "postgresql")
        result shouldBe "SELECT DATE_FORMAT(created_at, '%Y-%m-01') FROM t"
    }
    test("MySQL: DATE_TRUNC year transforms to DATE_FORMAT") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC('year', created_at) FROM t", "postgresql")
        result shouldBe "SELECT DATE_FORMAT(created_at, '%Y-01-01') FROM t"
    }
    test("MySQL: unsupported DATE_TRUNC unit falls back to original function call") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC('quarter', created_at) FROM t", "postgresql")
        result.replace(Regex("\\s+"), " ") shouldBe "SELECT DATE_TRUNC('quarter', created_at) FROM t"
    }
    test("MySQL: malformed DATE_TRUNC call stays as empty function call") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC() FROM t", "postgresql")
        result shouldBe "SELECT DATE_TRUNC() FROM t"
    }
    test("MySQL: EXTRACT YEAR FROM transforms to YEAR()") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT EXTRACT(YEAR FROM created_at) FROM t", "postgresql")
        result shouldBe "SELECT YEAR(created_at) FROM t"
    }
    test("MySQL: LENGTH transforms to CHAR_LENGTH") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT LENGTH(name) FROM t", "postgresql")
        result shouldBe "SELECT CHAR_LENGTH(name) FROM t"
    }
    test("MySQL: CHAR_LENGTH is not corrupted to CHAR_CHAR_LENGTH") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT CHAR_LENGTH(name) FROM t", "postgresql")
        result shouldBe "SELECT CHAR_LENGTH(name) FROM t"
    }
    test("MySQL: TRUE and FALSE transform to 1 and 0") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT * FROM t WHERE active = TRUE AND deleted = FALSE", "postgresql")
        result shouldBe "SELECT * FROM t WHERE active = 1 AND deleted = 0"
    }
    test("MySQL: CURRENT_DATE transforms to CURDATE()") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT CURRENT_DATE FROM t", "postgresql")
        result shouldBe "SELECT CURDATE() FROM t"
    }
    test("MySQL: CURRENT_TIME transforms to CURTIME()") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT CURRENT_TIME FROM t", "postgresql")
        result shouldBe "SELECT CURTIME() FROM t"
    }

    // ── SQLite transformations ───────────────────
    test("assessPortability: backticks are fine for a MySQL target") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        transformer.assessPortability("SELECT `x` FROM `t`", null).portable shouldBe true
    }
    test("assessPortability: PG :: cast is non-portable to MySQL (N4)") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        val verdict = transformer.assessPortability("SELECT (x)::text FROM t", "postgresql")
        verdict.portable shouldBe false
        verdict.reason!! shouldContain "::"
    }
    test("assessPortability: PG || concat is non-portable to MySQL (N4)") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        transformer.assessPortability("SELECT a || b FROM t", "postgresql").portable shouldBe false
    }
    test("assessPortability: same-dialect MySQL || (logical OR) stays portable (N4)") {
        val transformer = ViewQueryTransformer(MysqlViewPortabilityRules)
        transformer.assessPortability("SELECT a FROM t WHERE x || y", "mysql").portable shouldBe true
    }
})
