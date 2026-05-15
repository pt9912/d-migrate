package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedProcedure
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * E.1 Routine-Migration Slice A boundary pin: functions are
 * renderable now; procedures and triggers stay blocked with
 * `DIALECT_UNSUPPORTED_OPERATION` until Slice B / E.2 ships. The
 * exhaustive `categorize()` `when` in `PostgresDiffDdlGenerator`
 * would catch a missed case at compile-time, but pinning the
 * runtime blocker keeps the boundary explicit in tests.
 */
class PostgresDiffRoutineBoundaryTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    test("ProcedureAdd stays DIALECT_UNSUPPORTED_OPERATION until Slice B") {
        val procedure = ProcedureDefinition(body = "BEGIN END")
        val diff = SchemaDiff(proceduresAdded = listOf(NamedProcedure("p", procedure)))
        val r = gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.operationsSkipped.size shouldBe 1
    }
})
