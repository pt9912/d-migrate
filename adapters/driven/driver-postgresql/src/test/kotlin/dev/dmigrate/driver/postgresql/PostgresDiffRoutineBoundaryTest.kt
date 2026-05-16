package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * E.1 Routine-Migration boundary pin: functions (Slice A) and
 * procedures (Slice B) are renderable. Triggers stay blocked with
 * `DIALECT_UNSUPPORTED_OPERATION` until E.2 ships. The exhaustive
 * `categorize()` `when` in `PostgresDiffDdlGenerator` would catch
 * a missed case at compile-time, but pinning the runtime blocker
 * keeps the boundary explicit in tests.
 */
class PostgresDiffRoutineBoundaryTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    test("TriggerAdd stays DIALECT_UNSUPPORTED_OPERATION until E.2") {
        val trigger = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.BEFORE,
            body = "BEGIN END",
        )
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("trg", trigger)))
        val r = gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.operationsSkipped.size shouldBe 1
    }
})
