package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.NamedProcedure
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlServerVersion
import dev.dmigrate.driver.RoutineCapability
import dev.dmigrate.driver.RoutineKindCapability
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * E.1 Routine-Migration Slice C.2 pins for the MySQL function /
 * procedure renderer. Covers Up + Down Create / Replace / Drop,
 * the canonical body-unknown blockers, and the three Capability-
 * Resolution branches (Active / Disabled / InvalidConfig).
 */
class MysqlDiffRoutineOpsTest : FunSpec({

    val planner = DiffPlanner()
    val comparator = SchemaComparator()
    val gen = MysqlDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        options: DdlGenerationOptions = DdlGenerationOptions(),
    ) = gen.generateUp(planner.plan(current, desired, diff), options)

    fun planAndDown(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        options: DdlGenerationOptions = DdlGenerationOptions(),
    ) = gen.generateDown(planner.plan(current, desired, diff), options)

    val sampleFunction = FunctionDefinition(
        parameters = listOf(ParameterDefinition(name = "amount", type = "DECIMAL(10,2)")),
        returns = ReturnType(type = "DECIMAL(10,2)"),
        language = "sql",
        deterministic = true,
        body = "BEGIN\n  RETURN amount * 1.19;\nEND",
    )

    val sampleProcedure = ProcedureDefinition(
        parameters = listOf(ParameterDefinition(name = "id_in", type = "INT")),
        language = "sql",
        body = "BEGIN\n  CALL audit_log(id_in);\nEND",
    )

    // ── Function: Create / Replace / Drop ─────────────────────────

    test("CreateFunction (Up) emits CREATE FUNCTION with parameters, return type, and body (no DELIMITER)") {
        val r = planAndUp(SchemaDiff(functionsAdded = listOf(NamedFunction("compute_total", sampleFunction))))
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE FUNCTION `compute_total`")
        statement.shouldContain("(amount DECIMAL(10,2))")
        statement.shouldContain("RETURNS DECIMAL(10,2)")
        statement.shouldContain("LANGUAGE SQL")
        statement.shouldContain("DETERMINISTIC")
        statement.shouldContain("RETURN amount * 1.19")
        statement.shouldNotContain("DELIMITER")
        statement.shouldNotContain("\$body\$") // no dollar-quoting
    }

    test("CreateFunction (Up) renders SQL SECURITY when set") {
        val secured = sampleFunction.copy(security = RoutineSecurity.DEFINER)
        val r = planAndUp(SchemaDiff(functionsAdded = listOf(NamedFunction("compute_total", secured))))
        r.statements.single().sql.shouldContain("SQL SECURITY DEFINER")
    }

    test("ReplaceFunction (Up) under Active capability emits CREATE OR REPLACE with the new body") {
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN\n  RETURN amount * 1.2;\nEND")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE OR REPLACE FUNCTION `compute_total`")
        statement.shouldContain("RETURN amount * 1.2")
    }

    test("ReplaceFunction Down with known prior body emits CREATE OR REPLACE reverting to before") {
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN\n  RETURN amount * 1.2;\nEND")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE OR REPLACE FUNCTION `compute_total`")
        statement.shouldContain("RETURN amount * 1.19") // before body
    }

    test("ReplaceFunction Down without known prior body blocks with canonical ROUTINE_DOWN_BODY_UNKNOWN") {
        val before = sampleFunction.copy(body = null)
        val after = sampleFunction.copy(body = "BEGIN RETURN amount * 1.2; END")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        r.diagnostics.any { it.code == "ROUTINE_DOWN_BODY_UNKNOWN" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("DropFunction (Up) emits DROP FUNCTION without parameter signature (MySQL has no overloading)") {
        val r = planAndUp(
            SchemaDiff(functionsRemoved = listOf(NamedFunction("compute_total", sampleFunction))),
            current = emptySchema().copy(functions = mapOf("compute_total" to sampleFunction)),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("DROP FUNCTION `compute_total`")
        statement.shouldNotContain("(DECIMAL")
    }

    test("DropFunction Down re-creates the function with the stored definition") {
        val r = planAndDown(
            SchemaDiff(functionsRemoved = listOf(NamedFunction("compute_total", sampleFunction))),
            current = emptySchema().copy(functions = mapOf("compute_total" to sampleFunction)),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE FUNCTION `compute_total`")
        statement.shouldNotContain("OR REPLACE")
    }

    test("CreateFunction without body blocks with ROUTINE_BODY_UNKNOWN") {
        val noBody = sampleFunction.copy(body = null)
        val r = planAndUp(SchemaDiff(functionsAdded = listOf(NamedFunction("compute_total", noBody))))
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_BODY_UNKNOWN" } shouldBe true
    }

    // ── Procedure: Create / Replace / Drop ────────────────────────

    test("CreateProcedure (Up) emits CREATE PROCEDURE without RETURNS clause") {
        val r = planAndUp(SchemaDiff(proceduresAdded = listOf(NamedProcedure("audit_call", sampleProcedure))))
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE PROCEDURE `audit_call`")
        statement.shouldContain("(id_in INT)")
        statement.shouldContain("LANGUAGE SQL")
        statement.shouldContain("CALL audit_log(id_in)")
        statement.shouldNotContain("RETURNS")
        statement.shouldNotContain("DELIMITER")
    }

    test("ReplaceProcedure (Up) under Active capability emits CREATE OR REPLACE PROCEDURE") {
        val before = sampleProcedure
        val after = sampleProcedure.copy(body = "BEGIN\n  CALL audit_log_v2(id_in);\nEND")
        val current = emptySchema().copy(procedures = mapOf("audit_call" to before))
        val desired = emptySchema().copy(procedures = mapOf("audit_call" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE OR REPLACE PROCEDURE `audit_call`")
        statement.shouldContain("CALL audit_log_v2(id_in)")
    }

    test("ReplaceProcedure Down without known prior body blocks with ROUTINE_DOWN_BODY_UNKNOWN") {
        val before = sampleProcedure.copy(body = null)
        val after = sampleProcedure.copy(body = "BEGIN CALL log(); END")
        val current = emptySchema().copy(procedures = mapOf("audit_call" to before))
        val desired = emptySchema().copy(procedures = mapOf("audit_call" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_DOWN_BODY_UNKNOWN" } shouldBe true
    }

    test("MySQL procedure parameters render OUT and INOUT directions inline") {
        val proc = ProcedureDefinition(
            parameters = listOf(
                ParameterDefinition("p_in", "INT", ParameterDirection.IN),
                ParameterDefinition("p_out", "VARCHAR(64)", ParameterDirection.OUT),
                ParameterDefinition("p_io", "BIGINT", ParameterDirection.INOUT),
            ),
            language = "sql",
            body = "BEGIN END",
        )
        val r = planAndUp(SchemaDiff(proceduresAdded = listOf(NamedProcedure("p", proc))))
        val statement = r.statements.single().sql
        statement.shouldContain("(p_in INT, OUT p_out VARCHAR(64), INOUT p_io BIGINT)")
    }

    // ── Capability gates (Replace path only — Create/Drop ignore capability) ──

    fun disabledCapability() = DdlGenerationOptions(
        routineCapability = RoutineCapability(
            function = RoutineKindCapability(enabled = false),
            procedure = RoutineKindCapability(enabled = false),
        ),
    )

    test("ReplaceFunction with Disabled capability blocks with ROUTINE_CAPABILITY_DISABLED") {
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN RETURN amount * 1.2; END")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), disabledCapability())
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_CAPABILITY_DISABLED" } shouldBe true
    }

    test("ReplaceProcedure with Disabled capability blocks with ROUTINE_CAPABILITY_DISABLED") {
        val before = sampleProcedure
        val after = sampleProcedure.copy(body = "BEGIN END")
        val current = emptySchema().copy(procedures = mapOf("p" to before))
        val desired = emptySchema().copy(procedures = mapOf("p" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), disabledCapability())
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_CAPABILITY_DISABLED" } shouldBe true
    }

    test("Replace with minServerVersion declared and live version missing -> Disabled blocker") {
        val cap = RoutineCapability(
            function = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(8, 0, 0)),
            procedure = RoutineKindCapability(enabled = true),
        )
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN RETURN amount * 1.2; END")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(
            planner.plan(current, desired, diff),
            DdlGenerationOptions(routineCapability = cap, mysqlServerVersion = null),
        )
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_CAPABILITY_DISABLED" } shouldBe true
    }

    test("Replace with minServerVersion satisfied by live target -> CREATE OR REPLACE renders") {
        val cap = RoutineCapability(
            function = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(5, 7, 0)),
            procedure = RoutineKindCapability(enabled = true),
        )
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN RETURN amount * 1.2; END")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(
            planner.plan(current, desired, diff),
            DdlGenerationOptions(
                routineCapability = cap,
                mysqlServerVersion = MysqlServerVersion(8, 0, 36, vendor = "log"),
            ),
        )
        r.isBlocked shouldBe false
        r.statements.single().sql.shouldContain("CREATE OR REPLACE FUNCTION `compute_total`")
    }

    test("Replace with minServerVersion unmet by live target -> Disabled blocker") {
        val cap = RoutineCapability(
            function = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(8, 0, 0)),
            procedure = RoutineKindCapability(enabled = true),
        )
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN RETURN amount * 1.2; END")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(
            planner.plan(current, desired, diff),
            DdlGenerationOptions(
                routineCapability = cap,
                mysqlServerVersion = MysqlServerVersion(5, 7, 44, vendor = "log"),
            ),
        )
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_CAPABILITY_DISABLED" } shouldBe true
    }

    // ── Edge cases caught by the C.2 post-commit review ───────────

    test("CreateProcedure without body blocks with ROUTINE_BODY_UNKNOWN") {
        val noBody = sampleProcedure.copy(body = null)
        val r = planAndUp(SchemaDiff(proceduresAdded = listOf(NamedProcedure("audit_call", noBody))))
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_BODY_UNKNOWN" } shouldBe true
    }

    test("CreateFunction without RETURNS blocks with ROUTINE_RETURN_TYPE_UNKNOWN (no renderer crash)") {
        // Reverse-read paths or hand-edited schema files may produce a
        // Function with a body but no `returns:`. MySQL cannot render
        // such a routine — block explicitly instead of crashing.
        val noReturn = sampleFunction.copy(returns = null)
        val r = planAndUp(SchemaDiff(functionsAdded = listOf(NamedFunction("compute_total", noReturn))))
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_RETURN_TYPE_UNKNOWN" } shouldBe true
    }

    test("ReplaceFunction Up without after-body blocks with ROUTINE_REPLACE_UP_BODY_UNKNOWN") {
        // Operator declares a Function in the schema file without a
        // body on the after side (e.g. forgot to copy the body in).
        // The Up path's body-null guard reports a distinct code from
        // the Down path so reports stay traceable.
        val before = sampleFunction
        val after = sampleFunction.copy(body = null)
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_REPLACE_UP_BODY_UNKNOWN" } shouldBe true
    }

    test("DropProcedure (Up) emits DROP PROCEDURE without parameter signature") {
        val r = planAndUp(
            SchemaDiff(proceduresRemoved = listOf(NamedProcedure("audit_call", sampleProcedure))),
            current = emptySchema().copy(procedures = mapOf("audit_call" to sampleProcedure)),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("DROP PROCEDURE `audit_call`")
        statement.shouldNotContain("(INT") // MySQL DROP carries no signature
    }

    test("DropProcedure Down re-creates the procedure with the stored definition") {
        val r = planAndDown(
            SchemaDiff(proceduresRemoved = listOf(NamedProcedure("audit_call", sampleProcedure))),
            current = emptySchema().copy(procedures = mapOf("audit_call" to sampleProcedure)),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE PROCEDURE `audit_call`")
        statement.shouldNotContain("OR REPLACE")
    }
})
