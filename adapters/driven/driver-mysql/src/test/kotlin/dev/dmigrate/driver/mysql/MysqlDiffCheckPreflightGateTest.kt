package dev.dmigrate.driver.mysql

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
import dev.dmigrate.driver.MysqlServerVersion
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * F.5 Sub-Slice E.3 (2026-05-19): MySQL `AddConstraint(CHECK)`
 * renderer consults `DdlGenerationOptions.checkPreflights`. The
 * preflight gate runs *after* the enforcement-capability gate so
 * operators never see preflight blocks they can't act on (the
 * server-version-floor block fires first).
 */
class MysqlDiffCheckPreflightGateTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    val enforcedServer = MysqlServerVersion(8, 0, 16)

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
        problem: String? = null,
    ) = CheckPreflightDeclaration(
        operationId = opId,
        dialect = "mysql",
        table = "users",
        constraintName = "chk_age_nonneg",
        expression = "age >= 0",
        status = status,
        sqlHash = "fixed-hash",
        failingRows = failingRows,
        problem = problem,
    )

    test("PASSED + enforced server → renders natively") {
        val r = gen.generateUp(
            planResult(),
            DdlGenerationOptions(
                mysqlServerVersion = enforcedServer,
                checkPreflights = listOf(declaration(addCheckOpId(), CheckPreflightStatus.PASSED)),
            ),
        )
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "ADD CONSTRAINT `chk_age_nonneg` CHECK (age >= 0)"
    }

    test("FAILED + enforced server → block with CHECK_PREFLIGHT_VIOLATIONS") {
        val r = gen.generateUp(
            planResult(),
            DdlGenerationOptions(
                mysqlServerVersion = enforcedServer,
                checkPreflights = listOf(declaration(addCheckOpId(), CheckPreflightStatus.FAILED, failingRows = 5)),
            ),
        )
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
        r.diagnostics.any { it.code == PlannerBlockerClassifier.CHECK_PREFLIGHT_VIOLATIONS_CODE } shouldBe true
    }

    test("PROBE_RUNTIME_ERROR + enforced server → block with CHECK_PREFLIGHT_RUNTIME_ERROR") {
        val r = gen.generateUp(
            planResult(),
            DdlGenerationOptions(
                mysqlServerVersion = enforcedServer,
                checkPreflights = listOf(
                    declaration(
                        addCheckOpId(),
                        CheckPreflightStatus.PROBE_RUNTIME_ERROR,
                        problem = "connection lost",
                    ),
                ),
            ),
        )
        r.isBlocked shouldBe true
        val diag = r.diagnostics.single { it.code == PlannerBlockerClassifier.CHECK_PREFLIGHT_RUNTIME_ERROR_CODE }
        diag.message shouldContain "connection lost"
    }

    test("FAILED but server pre-8.0.16 → capability block wins (operator can't act on preflight yet)") {
        val r = gen.generateUp(
            planResult(),
            DdlGenerationOptions(
                mysqlServerVersion = MysqlServerVersion(8, 0, 15),
                checkPreflights = listOf(declaration(addCheckOpId(), CheckPreflightStatus.FAILED, failingRows = 5)),
            ),
        )
        r.isBlocked shouldBe true
        // Capability gate fires first; only its diagnostic is emitted.
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16_CODE
        } shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.CHECK_PREFLIGHT_VIOLATIONS_CODE
        } shouldBe false
    }

    test("FAILED with unknown server version → capability block wins") {
        val r = gen.generateUp(
            planResult(),
            DdlGenerationOptions(
                mysqlServerVersion = null,
                checkPreflights = listOf(declaration(addCheckOpId(), CheckPreflightStatus.FAILED, failingRows = 5)),
            ),
        )
        r.isBlocked shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.MYSQL_CHECK_ENFORCEMENT_UNKNOWN_CODE
        } shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.CHECK_PREFLIGHT_VIOLATIONS_CODE
        } shouldBe false
    }

    test("Down of AddConstraint(CHECK) on enforced server ignores preflight (drop)") {
        val r = gen.generateDown(
            planResult(),
            DdlGenerationOptions(
                mysqlServerVersion = enforcedServer,
                checkPreflights = listOf(declaration(addCheckOpId(), CheckPreflightStatus.FAILED, failingRows = 5)),
            ),
        )
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "DROP CHECK `chk_age_nonneg`"
    }
})
