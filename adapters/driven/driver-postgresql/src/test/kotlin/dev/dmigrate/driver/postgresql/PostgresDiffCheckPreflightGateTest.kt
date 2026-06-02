package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * F.5 Sub-Slice E.3 (2026-05-19): PostgreSQL `AddConstraint(CHECK)`
 * renderer consults `DdlGenerationOptions.checkPreflights`.
 *
 * Pinned outcomes per [CheckPreflightStatus]:
 * - PASSED / NOT_RUN_FILE_TARGET / NOT_RUN_POLICY / no declaration →
 *   render natively.
 * - FAILED → `CHECK_PREFLIGHT_VIOLATIONS` →
 *   `MANUAL_ACTION_REQUIRED`.
 * - PROBE_RUNTIME_ERROR → `CHECK_PREFLIGHT_RUNTIME_ERROR` →
 *   `MANUAL_ACTION_REQUIRED`.
 */
class PostgresDiffCheckPreflightGateTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    val constraint = ConstraintDefinition(
        name = "chk_age_nonneg",
        type = ConstraintType.CHECK,
        expression = "age >= 0",
    )
    val diff = SchemaDiff(
        tablesChanged = listOf(TableDiff(name = "users", constraintsAdded = listOf(constraint))),
    )

    fun planResult() = planner.plan(emptySchema(), emptySchema(), diff)
    fun addCheckOpId() = planResult().operations
        .filterIsInstance<DiffOperation.AddConstraint>()
        .single().id

    fun declaration(
        opId: String,
        status: CheckPreflightStatus,
        failingRows: Long? = null,
        totalRows: Long? = null,
        sampleRowIds: List<String> = emptyList(),
        problem: String? = null,
    ) = CheckPreflightDeclaration(
        operationId = opId,
        dialect = "postgresql",
        table = "users",
        constraintName = "chk_age_nonneg",
        expression = "age >= 0",
        status = status,
        sqlHash = "fixed-hash",
        failingRows = failingRows,
        totalRows = totalRows,
        sampleRowIds = sampleRowIds,
        problem = problem,
    )

    fun renderUpWith(decls: List<CheckPreflightDeclaration>) =
        gen.generateUp(planResult(), DdlGenerationOptions(checkPreflights = decls))

    test("no declaration in options.checkPreflights → renders natively") {
        val r = gen.generateUp(planResult(), DdlGenerationOptions())
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "ADD CONSTRAINT \"chk_age_nonneg\" CHECK (age >= 0)"
    }

    test("PASSED declaration → renders natively") {
        val r = renderUpWith(listOf(declaration(addCheckOpId(), CheckPreflightStatus.PASSED, totalRows = 1_000)))
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "ADD CONSTRAINT \"chk_age_nonneg\" CHECK (age >= 0)"
    }

    test("NOT_RUN_FILE_TARGET → renders natively (file-to-file path)") {
        val r = renderUpWith(listOf(declaration(addCheckOpId(), CheckPreflightStatus.NOT_RUN_FILE_TARGET)))
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "ADD CONSTRAINT \"chk_age_nonneg\""
    }

    test("NOT_RUN_POLICY → renders natively (operator opted out of preflight)") {
        val r = renderUpWith(listOf(declaration(addCheckOpId(), CheckPreflightStatus.NOT_RUN_POLICY)))
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "ADD CONSTRAINT \"chk_age_nonneg\""
    }

    test("FAILED → block with CHECK_PREFLIGHT_VIOLATIONS + MANUAL_ACTION_REQUIRED") {
        val r = renderUpWith(listOf(
            declaration(
                addCheckOpId(),
                CheckPreflightStatus.FAILED,
                failingRows = 12,
                totalRows = 1_000,
                sampleRowIds = listOf("42", "99"),
            ),
        ))
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
        val violationDiag = r.diagnostics.single { it.code == PlannerBlockerClassifier.CHECK_PREFLIGHT_VIOLATIONS_CODE }
        violationDiag.message shouldContain "chk_age_nonneg"
        violationDiag.message shouldContain "age >= 0"
        violationDiag.message shouldContain "Failing rows: 12"
        violationDiag.message shouldContain "Total rows: 1000"
        violationDiag.message shouldContain "Sample row ids: 42, 99"
        // No DDL emitted for the blocked op.
        r.statements.none { it.sql.contains("ADD CONSTRAINT \"chk_age_nonneg\"") } shouldBe true
    }

    test("PROBE_RUNTIME_ERROR → block with CHECK_PREFLIGHT_RUNTIME_ERROR + MANUAL_ACTION_REQUIRED") {
        val r = renderUpWith(listOf(
            declaration(
                addCheckOpId(),
                CheckPreflightStatus.PROBE_RUNTIME_ERROR,
                problem = "JDBC connection reset",
            ),
        ))
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
        val runtimeDiag = r.diagnostics.single { it.code == PlannerBlockerClassifier.CHECK_PREFLIGHT_RUNTIME_ERROR_CODE }
        runtimeDiag.message shouldContain "chk_age_nonneg"
        runtimeDiag.message shouldContain "JDBC connection reset"
    }

    test("declaration whose operationId doesn't match the op is ignored (renders natively)") {
        val r = renderUpWith(listOf(declaration("some-other-op", CheckPreflightStatus.FAILED, failingRows = 1)))
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "ADD CONSTRAINT \"chk_age_nonneg\""
    }

    test("Down direction of AddConstraint(CHECK) ignores preflight (drop is always safe)") {
        val r = gen.generateDown(
            planResult(),
            DdlGenerationOptions(
                checkPreflights = listOf(declaration(addCheckOpId(), CheckPreflightStatus.FAILED, failingRows = 1)),
            ),
        )
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "DROP CONSTRAINT \"chk_age_nonneg\""
    }
})
