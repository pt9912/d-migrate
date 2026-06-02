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
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.driver.MysqlServerVersion
import dev.dmigrate.driver.RoutineCapabilityDefaults
import dev.dmigrate.driver.RoutineKindCapability
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
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

    // Default options pre-Phase-B.0 carried the MySQL routine-capability defaults at the top
    // level; the new shape requires an explicit DdlDialectContext.MySql() to surface them so
    // the routine renderer doesn't fall through to InvalidConfig.
    val defaultMysqlOptions = DdlGenerationOptions(dialectContext = DdlDialectContext.MySql())

    fun planAndUp(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        options: DdlGenerationOptions = defaultMysqlOptions,
    ) = gen.generateUp(planner.plan(current, desired, diff), options)

    fun planAndDown(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        options: DdlGenerationOptions = defaultMysqlOptions,
    ) = gen.generateDown(planner.plan(current, desired, diff), options)

    fun mariaDbOptions() = DdlGenerationOptions(
        dialectContext = DdlDialectContext.MySql(
            routineCapability = RoutineCapabilityDefaults.forMysqlServerVersion(MysqlServerVersion(10, 11, 6, "MariaDB")),
            serverVersion = MysqlServerVersion(10, 11, 6, "MariaDB"),
        ),
    )

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
        val r = gen.generateUp(planner.plan(current, desired, diff), mariaDbOptions())
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE OR REPLACE FUNCTION `compute_total`")
        statement.shouldContain("RETURN amount * 1.2")
    }

    test("ReplaceFunction default Oracle MySQL capability uses guarded DROP + CREATE") {
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN\n  RETURN amount * 1.2;\nEND")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), defaultMysqlOptions)
        r.isBlocked shouldBe false
        val statements = r.statements.map { it.sql }
        statements.shouldHaveSize(2)
        statements.first().shouldContain("DROP FUNCTION `compute_total`")
        statements.last().shouldContain("CREATE FUNCTION `compute_total`")
        statements.last().shouldNotContain("OR REPLACE")
        r.diagnostics.any { it.code == "DEPENDENCY_GUARD_TOPOLOGY" } shouldBe true
        r.diagnostics.any { it.code == "MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC" } shouldBe true
    }

    test("ReplaceFunction Down with known prior body emits CREATE OR REPLACE reverting to before") {
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN\n  RETURN amount * 1.2;\nEND")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateDown(planner.plan(current, desired, diff), mariaDbOptions())
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
        val r = gen.generateDown(planner.plan(current, desired, diff), defaultMysqlOptions)
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
        val r = gen.generateUp(planner.plan(current, desired, diff), mariaDbOptions())
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
        val r = gen.generateDown(planner.plan(current, desired, diff), defaultMysqlOptions)
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
        dialectContext = DdlDialectContext.MySql(
            routineCapability = EffectiveRoutineCapability.Valid(
                function = RoutineKindCapability(enabled = false),
                procedure = RoutineKindCapability(enabled = false),
            ),
        ),
    )

    test("ReplaceFunction with Disabled capability + isolated plan (SAFE guard) emits DROP + CREATE") {
        // Slice C.3: when the routine is the only op in the plan,
        // the stub dependency guard returns SAFE and the renderer
        // falls back to DROP + CREATE instead of MANUAL_ACTION_REQUIRED.
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN RETURN amount * 1.2; END")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), disabledCapability())
        r.isBlocked shouldBe false
        r.statements.map { it.sql }.let { stmts ->
            stmts.shouldHaveSize(2)
            stmts.first().shouldContain("DROP FUNCTION `compute_total`")
            stmts.last().shouldContain("CREATE FUNCTION `compute_total`")
            stmts.last().shouldNotContain("OR REPLACE")
            stmts.last().shouldContain("RETURN amount * 1.2")
        }
        // The stub bewertung is annotated as an INFO diagnostic so
        // operators see that the SAFE call came from a heuristic,
        // not a topology proof. The SAFE-driven DROP + CREATE pair
        // is non-atomic across MySQL's implicit-commit boundary, so
        // the renderer additionally emits a WARNING to make that
        // operational risk visible.
        r.diagnostics.any { it.code == "DEPENDENCY_GUARD_TOPOLOGY" } shouldBe true
        r.diagnostics.any {
            it.code == "MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC" &&
                it.severity == dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.WARNING
        } shouldBe true
    }

    test("ReplaceProcedure with Disabled capability + isolated plan (SAFE guard) emits DROP + CREATE") {
        val before = sampleProcedure
        val after = sampleProcedure.copy(body = "BEGIN CALL log(); END")
        val current = emptySchema().copy(procedures = mapOf("p" to before))
        val desired = emptySchema().copy(procedures = mapOf("p" to after))
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), disabledCapability())
        r.isBlocked shouldBe false
        val stmts = r.statements.map { it.sql }
        stmts.shouldHaveSize(2)
        stmts.first().shouldContain("DROP PROCEDURE `p`")
        stmts.last().shouldContain("CREATE PROCEDURE `p`")
        stmts.last().shouldNotContain("OR REPLACE")
        r.diagnostics.any { it.code == "DEPENDENCY_GUARD_TOPOLOGY" } shouldBe true
        r.diagnostics.any { it.code == "MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC" } shouldBe true
    }

    test("ReplaceFunction with Disabled capability + true table-dependency (UNSAFE guard) blocks") {
        // Slice D.4 swapped the C.3 stub heuristic for real topology.
        // The C.3-era test relied on the stub flipping to UNSAFE
        // whenever any co-resident op existed; D.4 instead checks
        // declared edges. Here the routine truly depends on the
        // co-resident table (via `dependencies.tables`), so the
        // topology evaluator finds the edge and the renderer blocks.
        val newTable = dev.dmigrate.core.model.TableDefinition(
            columns = linkedMapOf(
                "id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.Integer,
                    required = true,
                ),
            ),
            primaryKey = listOf("id"),
        )
        val before = sampleFunction
        val after = sampleFunction.copy(
            body = "BEGIN RETURN amount * 1.2; END",
            dependencies = dev.dmigrate.core.model.DependencyInfo(tables = listOf("widgets")),
        )
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(
            functions = mapOf("compute_total" to after),
            tables = mapOf("widgets" to newTable),
        )
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), disabledCapability())
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_CAPABILITY_DISABLED" } shouldBe true
        r.diagnostics.any { it.code == "DEPENDENCY_GUARD_TOPOLOGY" } shouldBe true
        // The non-atomicity warning is tied to the SAFE-guard path
        // only; UNSAFE/UNKNOWN blocks do not emit it.
        r.diagnostics.none { it.code == "MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC" } shouldBe true
        // No CREATE FUNCTION emitted on the blocker path.
        r.statements.none { it.sql.contains("CREATE FUNCTION") } shouldBe true
    }

    test("D.4: Disabled capability + independent co-resident op stays SAFE (no stub heuristic)") {
        // Same plan-shape as the UNSAFE test above, but the routine
        // declares no dependency on the co-resident table. The
        // C.3-era stub would have flipped this to UNSAFE; D.4's
        // topology evaluator correctly recognises independence and
        // falls back to DROP + CREATE.
        val newTable = dev.dmigrate.core.model.TableDefinition(
            columns = linkedMapOf(
                "id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.Integer,
                    required = true,
                ),
            ),
            primaryKey = listOf("id"),
        )
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN RETURN amount * 1.2; END")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(
            functions = mapOf("compute_total" to after),
            tables = mapOf("widgets" to newTable),
        )
        val diff = comparator.compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), disabledCapability())
        r.isBlocked shouldBe false
        val createFn = r.statements.firstOrNull { it.sql.contains("CREATE FUNCTION") }
        createFn.shouldNotBeNull()
        r.diagnostics.any { it.code == "DEPENDENCY_GUARD_TOPOLOGY" } shouldBe true
        r.diagnostics.any { it.code == "MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC" } shouldBe true
    }

    test("Replace with minServerVersion declared and live version missing + SAFE guard -> DROP + CREATE") {
        // Disabled-by-missing-version follows the same Disabled
        // path as Disabled-by-flag, so SAFE guard still permits
        // DROP + CREATE.
        val cap = EffectiveRoutineCapability.Valid(
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
                dialectContext = DdlDialectContext.MySql(routineCapability = cap, serverVersion = null),
            ),
        )
        r.isBlocked shouldBe false
        r.statements.shouldHaveSize(2)
        r.diagnostics.any { it.code == "DEPENDENCY_GUARD_TOPOLOGY" } shouldBe true
        r.diagnostics.any { it.code == "MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC" } shouldBe true
    }

    test("Replace with minServerVersion satisfied by live target -> CREATE OR REPLACE renders") {
        val cap = EffectiveRoutineCapability.Valid(
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
                dialectContext = DdlDialectContext.MySql(
                    routineCapability = cap,
                    serverVersion = MysqlServerVersion(8, 0, 36, vendor = "log"),
                ),
            ),
        )
        r.isBlocked shouldBe false
        r.statements.single().sql.shouldContain("CREATE OR REPLACE FUNCTION `compute_total`")
    }

    test("Replace with minServerVersion unmet by live target + SAFE guard -> DROP + CREATE") {
        // Disabled-by-version-floor follows the same Disabled path as
        // Disabled-by-flag: with an isolated plan, the C.3 stub guard
        // reports SAFE and the renderer falls back to DROP + CREATE.
        val cap = EffectiveRoutineCapability.Valid(
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
                dialectContext = DdlDialectContext.MySql(
                    routineCapability = cap,
                    serverVersion = MysqlServerVersion(5, 7, 44, vendor = "log"),
                ),
            ),
        )
        r.isBlocked shouldBe false
        r.statements.shouldHaveSize(2)
        r.diagnostics.any { it.code == "DEPENDENCY_GUARD_TOPOLOGY" } shouldBe true
        r.diagnostics.any { it.code == "MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC" } shouldBe true
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
        val r = gen.generateUp(planner.plan(current, desired, diff), defaultMysqlOptions)
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

    // ── F.5 + 0.9.7 Sub-Slice C: InvalidConfig renderer pins ──────────
    //
    // F.5 wired `if (resolveCapability == InvalidConfig) blockCapabilityInvalid(...)`
    // into renderCreateFunction, renderDropFunction, renderCreateProcedure,
    // renderDropProcedure so ALL four entry points enforce
    // MANUAL_ACTION_REQUIRED per Plan §2/§3. Pre-F.5 only the Replace
    // entries routed through the resolver. The pins below cover the
    // Sub-Slice C requirement that
    // `routineCapability = EffectiveRoutineCapability.Invalid(reason)`
    // emits `ROUTINE_CAPABILITY_CONFIG_INVALID` with the operator's
    // reason string in the diagnostic body, with the operation marked
    // as blocked / MANUAL_ACTION_REQUIRED.

    fun invalidCapability(reason: String = "bad config: unparsable minServerVersion=not-a-version") =
        DdlGenerationOptions(
            dialectContext = DdlDialectContext.MySql(routineCapability = EffectiveRoutineCapability.Invalid(reason)),
        )

    test("Sub-Slice C: ReplaceFunction with Invalid capability blocks with reason in diagnostic body") {
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN RETURN amount * 1.2; END")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = comparator.compare(current, desired)
        val cap = invalidCapability("bad config: --routine-capability for kind=function has invalid 'enabled' value 'yes'")
        val r = gen.generateUp(planner.plan(current, desired, diff), cap)
        r.isBlocked shouldBe true
        val diag = r.diagnostics.firstOrNull { it.code == "ROUTINE_CAPABILITY_CONFIG_INVALID" }
        diag.shouldNotBeNull()
        diag.message.shouldContain("Function 'compute_total'")
        diag.message.shouldContain("invalid 'enabled' value 'yes'")
        r.statements.none { it.sql.contains("CREATE") || it.sql.contains("DROP FUNCTION") } shouldBe true
    }

    test("Sub-Slice C: CreateProcedure with Invalid capability blocks with reason in diagnostic body") {
        val r = planAndUp(
            SchemaDiff(proceduresAdded = listOf(NamedProcedure("audit_call", sampleProcedure))),
            options = invalidCapability("bad config: YAML 'routineCapability.procedure.enabled' must be a boolean"),
        )
        r.isBlocked shouldBe true
        val diag = r.diagnostics.firstOrNull { it.code == "ROUTINE_CAPABILITY_CONFIG_INVALID" }
        diag.shouldNotBeNull()
        diag.message.shouldContain("Procedure 'audit_call'")
        diag.message.shouldContain("must be a boolean")
    }

    test("Sub-Slice C: DropFunction with Invalid capability blocks with reason in diagnostic body") {
        val current = emptySchema().copy(functions = mapOf("compute_total" to sampleFunction))
        val desired = emptySchema()
        val diff = comparator.compare(current, desired)
        val cap = invalidCapability("bad config: duplicate --routine-capability for kind=function")
        val r = gen.generateUp(planner.plan(current, desired, diff), cap)
        r.isBlocked shouldBe true
        val diag = r.diagnostics.firstOrNull { it.code == "ROUTINE_CAPABILITY_CONFIG_INVALID" }
        diag.shouldNotBeNull()
        diag.message.shouldContain("duplicate --routine-capability")
        r.statements.none { it.sql.contains("DROP FUNCTION") } shouldBe true
    }

    // ── F.6: DEFINER clause is rendered when set ───────────────────────

    test("F.6: CreateFunction with definer emits DEFINER = user@host before FUNCTION") {
        val withDefiner = sampleFunction.copy(definer = "'alice'@'%'")
        val r = planAndUp(
            SchemaDiff(functionsAdded = listOf(NamedFunction("with_definer", withDefiner))),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("DEFINER = 'alice'@'%' FUNCTION `with_definer`")
    }

    test("F.6: CreateProcedure with definer emits DEFINER = user@host before PROCEDURE") {
        val withDefiner = sampleProcedure.copy(definer = "'bob'@'localhost'")
        val r = planAndUp(
            SchemaDiff(proceduresAdded = listOf(NamedProcedure("with_definer", withDefiner))),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("DEFINER = 'bob'@'localhost' PROCEDURE `with_definer`")
    }

    test("F.6: ReplaceFunction with definer emits DEFINER between OR REPLACE and FUNCTION") {
        val before = sampleFunction.copy(definer = "'alice'@'%'")
        val after = sampleFunction.copy(
            definer = "'bob'@'%'",
            body = "BEGIN\n  RETURN amount * 1.20;\nEND",
        )
        val diff = comparator.compare(
            emptySchema().copy(functions = mapOf("rotate_definer" to before)),
            emptySchema().copy(functions = mapOf("rotate_definer" to after)),
        )
        val r = planAndUp(
            diff,
            current = emptySchema().copy(functions = mapOf("rotate_definer" to before)),
            desired = emptySchema().copy(functions = mapOf("rotate_definer" to after)),
            options = mariaDbOptions(),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE OR REPLACE DEFINER = 'bob'@'%' FUNCTION `rotate_definer`")
    }

    test("F.6: CreateFunction without definer omits the DEFINER clause") {
        val r = planAndUp(
            SchemaDiff(functionsAdded = listOf(NamedFunction("no_definer", sampleFunction))),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldNotContain("DEFINER")
    }
})
