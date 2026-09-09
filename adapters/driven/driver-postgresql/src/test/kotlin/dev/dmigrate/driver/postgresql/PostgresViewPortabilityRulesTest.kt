package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.ViewQueryTransformer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Was PostgreSQL an einem fremden View-Rumpf umschreibt. */
class PostgresViewPortabilityRulesTest : FunSpec({

    test("PostgreSQL: NOW() transforms to CURRENT_TIMESTAMP") {
        val transformer = ViewQueryTransformer(PostgresViewPortabilityRules)
        val (result, _) = transformer.transform("SELECT NOW() FROM t", null)
        result shouldBe "SELECT CURRENT_TIMESTAMP FROM t"
    }

    // ── Unknown function detection ──────────────

    test("assessPortability: MySQL+PG-portable FLOOR is not flagged cross-dialect (M3)") {
        val transformer = ViewQueryTransformer(PostgresViewPortabilityRules)
        transformer.assessPortability("SELECT FLOOR(x) FROM t", "mysql").portable shouldBe true
    }
})
