package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.ViewQueryTransformer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Was Oracle an einem fremden View-Rumpf nicht versteht. */
class OracleViewPortabilityRulesTest : FunSpec({

    test("assessPortability: `::` and LIMIT are non-portable to Oracle; `||` stays portable (native concat)") {
        val transformer = ViewQueryTransformer(OracleViewPortabilityRules)
        transformer.assessPortability("SELECT id::text FROM t", "postgresql").let {
            it.portable shouldBe false
            it.reason shouldContain "::"
        }
        transformer.assessPortability("SELECT id FROM t LIMIT 10", "sqlite").let {
            it.portable shouldBe false
            it.reason shouldContain "LIMIT"
        }
        // Oracle unterstuetzt || nativ als Stringverkettung, wie PostgreSQL/SQLite.
        transformer.assessPortability("SELECT a || b FROM t", "postgresql").portable shouldBe true
        // Inside string literals the markers are ignored.
        transformer.assessPortability("SELECT 'a||b::c limit' AS s FROM t", "postgresql").portable shouldBe true
        transformer.assessPortability("SELECT id, name FROM users WHERE id > 0", "postgresql").portable shouldBe true
    }
    test("assessPortability: `||` from a MySQL source is non-portable to Oracle (logical OR, not concat)") {
        val transformer = ViewQueryTransformer(OracleViewPortabilityRules)
        transformer.assessPortability("SELECT a || b FROM t", "mysql").let {
            it.portable shouldBe false
            it.reason shouldContain "||"
        }
        // Same source dialect (oracle) is unaffected -- native concatenation.
        transformer.assessPortability("SELECT a || b FROM t", "oracle").portable shouldBe true
    }
})
