package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedProcedure
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * E.1 Routine-Migration Slice B pins for the PostgreSQL procedure
 * renderer. Mirrors `PostgresDiffFunctionOpsTest` (Slice A) for
 * Create / Replace / Drop in both Up and Down direction, plus the
 * Down-blocking path when the prior body is unknown.
 */
class PostgresDiffProcedureOpsTest : FunSpec({

    val planner = DiffPlanner()
    val comparator = SchemaComparator()
    val gen = PostgresDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
    ) = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun planAndDown(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
    ) = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    val sampleProcedure = ProcedureDefinition(
        parameters = listOf(ParameterDefinition(name = "id_in", type = "integer")),
        language = "plpgsql",
        body = "BEGIN\n  CALL audit_log(id_in);\nEND",
    )

    test("CreateProcedure (Up) emits CREATE PROCEDURE with dollar-quoted body and parameter list") {
        val r = planAndUp(SchemaDiff(proceduresAdded = listOf(NamedProcedure("audit_call", sampleProcedure))))
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE PROCEDURE \"audit_call\"")
        statement.shouldContain("(id_in integer)")
        statement.shouldContain("LANGUAGE \"plpgsql\"")
        statement.shouldContain("\$body\$")
        statement.shouldContain("CALL audit_log(id_in)")
        // Procedures have no RETURNS clause — Slice A's renderer
        // produces `RETURNS …`; the procedure renderer must NOT.
        statement.shouldNotContain("RETURNS")
    }

    test("CreateProcedure (Up) renders SECURITY DEFINER and search_path when set") {
        val secured = sampleProcedure.copy(
            security = RoutineSecurity.DEFINER,
            searchPath = listOf("public", "audit"),
        )
        val r = planAndUp(SchemaDiff(proceduresAdded = listOf(NamedProcedure("audit_call", secured))))
        val statement = r.statements.single().sql
        statement.shouldContain("SECURITY DEFINER")
        statement.shouldContain("SET search_path = \"public\", \"audit\"")
    }

    test("ReplaceProcedure (Up) emits CREATE OR REPLACE with the new body") {
        val before = sampleProcedure
        val after = sampleProcedure.copy(body = "BEGIN\n  CALL audit_log_v2(id_in);\nEND")
        val current = emptySchema().copy(procedures = mapOf("audit_call" to before))
        val desired = emptySchema().copy(procedures = mapOf("audit_call" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE OR REPLACE PROCEDURE \"audit_call\"")
        statement.shouldContain("CALL audit_log_v2(id_in)")
    }

    test("ReplaceProcedure Down with known prior body emits CREATE OR REPLACE reverting to before") {
        val before = sampleProcedure
        val after = sampleProcedure.copy(body = "BEGIN\n  CALL audit_log_v2(id_in);\nEND")
        val current = emptySchema().copy(procedures = mapOf("audit_call" to before))
        val desired = emptySchema().copy(procedures = mapOf("audit_call" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE OR REPLACE PROCEDURE \"audit_call\"")
        statement.shouldContain("CALL audit_log(id_in)") // before body
    }

    test("ReplaceProcedure Down without known prior body blocks with ROUTINE_REPLACE_DOWN_BODY_UNKNOWN") {
        val before = sampleProcedure.copy(body = null)
        val after = sampleProcedure.copy(body = "BEGIN CALL audit_log_v2(id_in); END")
        val current = emptySchema().copy(procedures = mapOf("audit_call" to before))
        val desired = emptySchema().copy(procedures = mapOf("audit_call" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        r.diagnostics.any { it.code == "ROUTINE_REPLACE_DOWN_BODY_UNKNOWN" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("DropProcedure (Up) emits DROP PROCEDURE with parameter types") {
        val r = planAndUp(
            SchemaDiff(proceduresRemoved = listOf(NamedProcedure("audit_call", sampleProcedure))),
            current = emptySchema().copy(procedures = mapOf("audit_call" to sampleProcedure)),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("DROP PROCEDURE \"audit_call\"(integer)")
    }

    test("DropProcedure Down re-creates the procedure with the stored definition") {
        val r = planAndDown(
            SchemaDiff(proceduresRemoved = listOf(NamedProcedure("audit_call", sampleProcedure))),
            current = emptySchema().copy(procedures = mapOf("audit_call" to sampleProcedure)),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE PROCEDURE \"audit_call\"")
        statement.shouldNotContain("OR REPLACE")
    }

    test("CreateProcedure without body blocks rather than emitting empty dollar-quotes") {
        val noBody = sampleProcedure.copy(body = null)
        val r = planAndUp(SchemaDiff(proceduresAdded = listOf(NamedProcedure("audit_call", noBody))))
        r.isBlocked shouldBe true
        r.diagnostics.shouldNotBeEmpty()
        r.diagnostics.any { it.code == "ROUTINE_BODY_UNKNOWN" } shouldBe true
    }

    test("procedure body containing the renderer's dollar-tag blocks with ROUTINE_BODY_DOLLAR_TAG_COLLISION") {
        val colliding = sampleProcedure.copy(body = "BEGIN\n  CALL log(\$body\$ || 'x');\nEND")
        val r = planAndUp(SchemaDiff(proceduresAdded = listOf(NamedProcedure("collider", colliding))))
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_BODY_DOLLAR_TAG_COLLISION" } shouldBe true
    }

    test("DROP PROCEDURE drops OUT params and prefixes INOUT (PG signature contract)") {
        val overloaded = sampleProcedure.copy(
            parameters = listOf(
                ParameterDefinition(name = "input_val", type = "integer", direction = ParameterDirection.IN),
                ParameterDefinition(name = "io_val", type = "text", direction = ParameterDirection.INOUT),
                ParameterDefinition(name = "out_val", type = "boolean", direction = ParameterDirection.OUT),
            ),
        )
        val r = planAndUp(
            SchemaDiff(proceduresRemoved = listOf(NamedProcedure("overloaded_p", overloaded))),
            current = emptySchema().copy(procedures = mapOf("overloaded_p" to overloaded)),
        )
        val statement = r.statements.single().sql
        statement.shouldContain("DROP PROCEDURE \"overloaded_p\"(integer, INOUT text)")
        statement.shouldNotContain("boolean") // OUT param dropped from signature
    }
})
