package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.neutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.property.Arb
import io.kotest.property.checkAll

/**
 * Property-Based Testing für den SQLite-Typpfad (LN-046, ADR 0029, Phase B).
 */
class SqliteNeutralTypePropertySpec : FunSpec({

    val mapper = SqliteTypeMapper()

    test("toSql ist total — jeder NeutralType rendert eine nicht-leere DDL-Typzeichenkette") {
        checkAll(Arb.neutralType()) { type ->
            mapper.toSql(type).shouldNotBeBlank()
        }
    }

    test("canonicalize ist idempotent — canon(canon(t)) == canon(t)") {
        checkAll(Arb.neutralType()) { type ->
            val once = SqliteNeutralTypeCanonicalizer.canonicalize(type)
            SqliteNeutralTypeCanonicalizer.canonicalize(once) shouldBe once
        }
    }
})
