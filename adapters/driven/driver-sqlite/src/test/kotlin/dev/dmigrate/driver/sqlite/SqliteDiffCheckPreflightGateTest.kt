package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * F.5 Sub-Slice E.3 (2026-05-19): SQLite rebuild dispatcher consults
 * `DdlGenerationOptions.checkPreflights` and blocks the entire
 * rebuild bucket when any `AddConstraint(CHECK)` op in it carries a
 * FAILED or PROBE_RUNTIME_ERROR declaration.
 */
class SqliteDiffCheckPreflightGateTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun schemaWith(tables: Map<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables)

    val baseTable = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.Integer),
        ),
        primaryKey = listOf("id"),
    )
    val checkConstraint = ConstraintDefinition(
        name = "chk_age_nonneg",
        type = ConstraintType.CHECK,
        expression = "age >= 0",
    )

    fun planResult() = planner.plan(
        schemaWith(mapOf("u" to baseTable)),
        schemaWith(mapOf("u" to baseTable.copy(constraints = listOf(checkConstraint)))),
        SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", constraintsAdded = listOf(checkConstraint)),
            ),
        ),
    )

    fun addCheckOpId() = planResult().operations
        .filterIsInstance<DiffOperation.AddConstraint>()
        .single().id

    fun declaration(
        opId: String,
        status: CheckPreflightStatus,
        failingRows: Long? = null,
        problem: String? = null,
    ) = CheckPreflightDeclaration(
        operationId = opId,
        dialect = "sqlite",
        table = "u",
        constraintName = "chk_age_nonneg",
        expression = "age >= 0",
        status = status,
        sqlHash = "fixed-hash",
        failingRows = failingRows,
        problem = problem,
    )

    fun renderUp(decls: List<CheckPreflightDeclaration>) =
        gen.generateUp(planResult(), DdlGenerationOptions(checkPreflights = decls))

    test("PASSED → rebuild proceeds, CREATE temp carries CHECK inline") {
        val r = renderUp(listOf(declaration(addCheckOpId(), CheckPreflightStatus.PASSED)))
        r.isBlocked shouldBe false
        r.statements.first { it.sql.startsWith("CREATE TABLE \"u__dmg_rebuild_") }.sql shouldContain
            "CONSTRAINT \"chk_age_nonneg\" CHECK (age >= 0)"
    }

    test("NOT_RUN_FILE_TARGET → rebuild proceeds") {
        val r = renderUp(listOf(declaration(addCheckOpId(), CheckPreflightStatus.NOT_RUN_FILE_TARGET)))
        r.isBlocked shouldBe false
    }

    test("FAILED → rebuild bucket blocked with CHECK_PREFLIGHT_VIOLATIONS + MANUAL_ACTION_REQUIRED, no DDL") {
        val r = renderUp(listOf(declaration(addCheckOpId(), CheckPreflightStatus.FAILED, failingRows = 7)))
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
        r.diagnostics.any { it.code == PlannerBlockerClassifier.CHECK_PREFLIGHT_VIOLATIONS_CODE } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("PROBE_RUNTIME_ERROR → block with CHECK_PREFLIGHT_RUNTIME_ERROR") {
        val r = renderUp(listOf(
            declaration(
                addCheckOpId(),
                CheckPreflightStatus.PROBE_RUNTIME_ERROR,
                problem = "database is locked",
            ),
        ))
        r.isBlocked shouldBe true
        val diag = r.diagnostics.single { it.code == PlannerBlockerClassifier.CHECK_PREFLIGHT_RUNTIME_ERROR_CODE }
        diag.message shouldContain "database is locked"
    }

    test("no declaration → rebuild proceeds (file-to-file / non-CHECK rebuild)") {
        val r = renderUp(emptyList())
        r.isBlocked shouldBe false
    }
})
