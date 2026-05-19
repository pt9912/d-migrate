package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

class CheckPreflightStageTest : FunSpec({

    val planner = DiffPlanner()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planWithCheckAdd() = planner.plan(
        emptySchema(),
        emptySchema(),
        SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    constraintsAdded = listOf(
                        ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0"),
                    ),
                ),
            ),
        ),
    )

    fun requestExecuteDb(dialect: String) =
        SchemaMigrateRequest(source = "desired.yaml", target = "db:$dialect", execute = true)

    fun requestFile() =
        SchemaMigrateRequest(source = "desired.yaml", target = "file:current.yaml")

    fun dbTarget(dialect: String) = CompareOperand.Database(dialect)
    fun fileTarget() = CompareOperand.File(Path.of("current.yaml"))

    // ── MigrationPreflightPlanner CHECK part ─────────────────────

    test("plan with CHECK Add + execute against DB → NOT_RUN_POLICY declaration per dialect") {
        for (dialect in listOf(DatabaseDialect.POSTGRESQL, DatabaseDialect.MYSQL, DatabaseDialect.SQLITE)) {
            val r = MigrationPreflightPlanner.plan(
                sqliteCastPlanner = null,
                request = requestExecuteDb(dialect.name.lowercase()),
                target = dbTarget(dialect.name.lowercase()),
                dialect = dialect,
                plan = planWithCheckAdd(),
            )
            r.checkPreflights shouldHaveSize 1
            r.checkPreflights.single().status shouldBe CheckPreflightStatus.NOT_RUN_POLICY
            r.checkPreflights.single().dialect shouldBe dialect.name.lowercase()
        }
    }

    test("plan with CHECK Add + file target → NOT_RUN_FILE_TARGET") {
        val r = MigrationPreflightPlanner.plan(
            sqliteCastPlanner = null,
            request = requestFile(),
            target = fileTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = planWithCheckAdd(),
        )
        r.checkPreflights.single().status shouldBe CheckPreflightStatus.NOT_RUN_FILE_TARGET
    }

    test("plan with no CHECK Add ops → empty checkPreflights regardless of dialect") {
        val empty = planner.plan(emptySchema(), emptySchema(), SchemaDiff())
        val r = MigrationPreflightPlanner.plan(
            sqliteCastPlanner = null,
            request = requestExecuteDb("postgresql"),
            target = dbTarget("postgresql"),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = empty,
        )
        r.checkPreflights.shouldBeEmpty()
    }

    // ── CheckPreflightStage.run ──────────────────────────────────

    test("file target → NotRun") {
        val outcome = CheckPreflightStage.run(
            probe = { _, _, _, _ -> emptyList() },
            request = requestFile(),
            target = fileTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = planWithCheckAdd(),
            preflightPlan = MigrationPreflightPlanner.plan(
                null, requestFile(), fileTarget(), DatabaseDialect.POSTGRESQL, planWithCheckAdd(),
            ),
        )
        outcome shouldBe CheckPreflightStage.Outcome.NotRun
    }

    test("execute without probe (CLI didn't wire one) → NotRun") {
        val outcome = CheckPreflightStage.run(
            probe = null,
            request = requestExecuteDb("postgresql"),
            target = dbTarget("postgresql"),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = planWithCheckAdd(),
            preflightPlan = MigrationPreflightPlanner.plan(
                null, requestExecuteDb("postgresql"), dbTarget("postgresql"),
                DatabaseDialect.POSTGRESQL, planWithCheckAdd(),
            ),
        )
        outcome shouldBe CheckPreflightStage.Outcome.NotRun
    }

    test("execute against DB with empty preflight plan → NotRun (no CHECK Adds to probe)") {
        val empty = planner.plan(emptySchema(), emptySchema(), SchemaDiff())
        val outcome = CheckPreflightStage.run(
            probe = { _, _, _, _ -> emptyList() },
            request = requestExecuteDb("postgresql"),
            target = dbTarget("postgresql"),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = empty,
            preflightPlan = MigrationPreflightPlan.EMPTY,
        )
        outcome shouldBe CheckPreflightStage.Outcome.NotRun
    }

    test("execute + probe + non-empty plan → Succeeded with probe's declarations") {
        val canned = CheckPreflightDeclaration(
            operationId = "op-1",
            dialect = "postgresql",
            table = "users",
            constraintName = "chk_age",
            expression = "age >= 0",
            status = CheckPreflightStatus.PASSED,
            sqlHash = "h",
        )
        val outcome = CheckPreflightStage.run(
            probe = { _, _, _, _ -> listOf(canned) },
            request = requestExecuteDb("postgresql"),
            target = dbTarget("postgresql"),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = planWithCheckAdd(),
            preflightPlan = MigrationPreflightPlanner.plan(
                null, requestExecuteDb("postgresql"), dbTarget("postgresql"),
                DatabaseDialect.POSTGRESQL, planWithCheckAdd(),
            ),
        )
        outcome shouldBe CheckPreflightStage.Outcome.Succeeded(listOf(canned))
    }

    test("probe throws → Failed; declarations from preflight plan get PROBE_RUNTIME_ERROR + problem text") {
        val preflightPlan = MigrationPreflightPlanner.plan(
            null, requestExecuteDb("postgresql"), dbTarget("postgresql"),
            DatabaseDialect.POSTGRESQL, planWithCheckAdd(),
        )
        val outcome = CheckPreflightStage.run(
            probe = { _, _, _, _ -> error("connection reset") },
            request = requestExecuteDb("postgresql"),
            target = dbTarget("postgresql"),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = planWithCheckAdd(),
            preflightPlan = preflightPlan,
        )
        outcome.shouldBeFailedWith("connection reset", expectedSize = 1)
    }

    test("buildFailureResult produces a MANUAL_ACTION_REQUIRED MigrationDdlResult with CHECK_PREFLIGHT_RUN_FAILED diagnostic") {
        val r = CheckPreflightStage.buildFailureResult(
            message = "boom",
            declarations = listOf(
                CheckPreflightDeclaration(
                    operationId = "op-1", dialect = "postgresql", table = "users",
                    constraintName = "chk_age", expression = "age >= 0",
                    status = CheckPreflightStatus.PROBE_RUNTIME_ERROR, sqlHash = "h", problem = "boom",
                ),
            ),
        )
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "CHECK_PREFLIGHT_RUN_FAILED" }.message shouldContain "boom"
        r.checkPreflights shouldHaveSize 1
    }
})

private fun CheckPreflightStage.Outcome.shouldBeFailedWith(messageFragment: String, expectedSize: Int) {
    val failed = this as? CheckPreflightStage.Outcome.Failed
        ?: throw AssertionError("expected Failed, got $this")
    failed.message shouldContain messageFragment
    failed.declarations shouldHaveSize expectedSize
    failed.declarations.all { it.status == CheckPreflightStatus.PROBE_RUNTIME_ERROR } shouldBe true
}
