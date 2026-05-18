package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TriggerNameCollisionDetectorTest : FunSpec({

    fun trigger(name: String, table: String): NamedTrigger =
        NamedTrigger(
            name = name,
            definition = TriggerDefinition(
                table = table,
                event = TriggerEvent.INSERT,
                timing = TriggerTiming.BEFORE,
                body = "audit_$name()",
            ),
        )

    test("empty input returns Ok") {
        TriggerNameCollisionDetector.detect(emptyList()) shouldBe TriggerNameCollisionOutcome.Ok
    }

    test("single trigger returns Ok") {
        val outcome = TriggerNameCollisionDetector.detect(
            listOf(trigger("audit_log", "orders")),
        )
        outcome shouldBe TriggerNameCollisionOutcome.Ok
    }

    test("distinct names on distinct tables returns Ok") {
        val outcome = TriggerNameCollisionDetector.detect(
            listOf(
                trigger("audit_orders", "orders"),
                trigger("audit_customers", "customers"),
                trigger("audit_products", "products"),
            ),
        )
        outcome shouldBe TriggerNameCollisionOutcome.Ok
    }

    test("identical (name, table) pair is not a collision (duplicate input row)") {
        // Readers may yield two rows for the same (name, table) when a
        // JDBC query joins. The detector must not flag this.
        val outcome = TriggerNameCollisionDetector.detect(
            listOf(
                trigger("audit_log", "orders"),
                trigger("audit_log", "orders"),
            ),
        )
        outcome shouldBe TriggerNameCollisionOutcome.Ok
    }

    test("same name on two distinct tables is a collision") {
        val outcome = TriggerNameCollisionDetector.detect(
            listOf(
                trigger("audit_log", "orders"),
                trigger("audit_log", "customers"),
            ),
        )
        val collisions = outcome.shouldBeInstanceOf<TriggerNameCollisionOutcome.Collisions>()
        collisions.collisions shouldHaveSize 1
        collisions.collisions[0].name shouldBe "audit_log"
        collisions.collisions[0].tables shouldContainExactlyInAnyOrder listOf("orders", "customers")
    }

    test("multiple distinct collisions are all reported") {
        val outcome = TriggerNameCollisionDetector.detect(
            listOf(
                trigger("audit_log", "orders"),
                trigger("audit_log", "customers"),
                trigger("change_log", "products"),
                trigger("change_log", "inventory"),
                trigger("unique_one", "alpha"),
            ),
        )
        val collisions = outcome.shouldBeInstanceOf<TriggerNameCollisionOutcome.Collisions>()
        collisions.collisions shouldHaveSize 2
        val byName = collisions.collisions.associateBy { it.name }
        byName.keys shouldBe setOf("audit_log", "change_log")
        byName.getValue("audit_log").tables shouldContainExactlyInAnyOrder listOf("orders", "customers")
        byName.getValue("change_log").tables shouldContainExactlyInAnyOrder listOf("products", "inventory")
    }

    test("collision across three distinct tables lists all three") {
        val outcome = TriggerNameCollisionDetector.detect(
            listOf(
                trigger("audit_log", "orders"),
                trigger("audit_log", "customers"),
                trigger("audit_log", "products"),
            ),
        )
        val collisions = outcome.shouldBeInstanceOf<TriggerNameCollisionOutcome.Collisions>()
        collisions.collisions shouldHaveSize 1
        collisions.collisions[0].tables shouldContainExactlyInAnyOrder listOf("orders", "customers", "products")
    }

    test("TriggerNameCollision rejects fewer than two tables") {
        val ex = runCatching {
            TriggerNameCollision(name = "audit_log", tables = listOf("orders"))
        }.exceptionOrNull()
        ex.shouldBeInstanceOf<IllegalArgumentException>()
    }
})
