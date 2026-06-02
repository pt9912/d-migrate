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
 * procedures (Slice B) are renderable. E.2 Sub-Slice A.2 hooked
 * triggers into the same render pipeline; the boundary moved from
 * `DIALECT_UNSUPPORTED_OPERATION` to body-form validation
 * (`TRIGGER_BODY_NOT_FUNCTION_REFERENCE` for inline PL/pgSQL).
 */
class PostgresDiffRoutineBoundaryTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    test("TriggerAdd with inline body blocks with TRIGGER_BODY_NOT_FUNCTION_REFERENCE (E.2)") {
        // Prior to E.2 the Postgres renderer treated CreateTrigger as
        // DIALECT_UNSUPPORTED_OPERATION outright. E.2 wires triggers
        // into the render pipeline but enforces PostgreSQL's strict
        // `EXECUTE FUNCTION <ref>` body form — an inline `BEGIN END`
        // body now blocks for the right reason.
        val trigger = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.BEFORE,
            body = "BEGIN END",
        )
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("trg", trigger)))
        val r = gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.TRIGGER_BODY_NOT_FUNCTION_REFERENCE
        r.operationsSkipped.size shouldBe 1
    }
})
