package dev.dmigrate.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * F4 (docs/planning/in-progress/sample-db-roundtrip-findings.md): pins the
 * multi-event trigger model — the single-event convenience constructor, the
 * canonical-order helper, and the SQL event clause. PostgreSQL is the only
 * dialect with multi-event triggers (`BEFORE INSERT OR UPDATE`); these helpers
 * are the single source of truth for deterministic, order-independent
 * rendering and fingerprinting of the event set.
 */
class TriggerDefinitionTest : FunSpec({

    test("single-event convenience constructor wraps the event in a one-element set") {
        val trg = TriggerDefinition(table = "t", event = TriggerEvent.UPDATE, timing = TriggerTiming.BEFORE)
        trg.events shouldBe setOf(TriggerEvent.UPDATE)
    }

    test("canonicalOrder sorts by enum declaration order regardless of set iteration order") {
        setOf(TriggerEvent.DELETE, TriggerEvent.INSERT, TriggerEvent.UPDATE).canonicalOrder() shouldBe
            listOf(TriggerEvent.INSERT, TriggerEvent.UPDATE, TriggerEvent.DELETE)
        setOf(TriggerEvent.UPDATE, TriggerEvent.INSERT).canonicalOrder() shouldBe
            listOf(TriggerEvent.INSERT, TriggerEvent.UPDATE)
    }

    test("toSqlEventClause joins canonical events with OR") {
        setOf(TriggerEvent.INSERT).toSqlEventClause() shouldBe "INSERT"
        setOf(TriggerEvent.UPDATE, TriggerEvent.INSERT).toSqlEventClause() shouldBe "INSERT OR UPDATE"
        setOf(TriggerEvent.DELETE, TriggerEvent.INSERT, TriggerEvent.UPDATE).toSqlEventClause() shouldBe
            "INSERT OR UPDATE OR DELETE"
    }
})
