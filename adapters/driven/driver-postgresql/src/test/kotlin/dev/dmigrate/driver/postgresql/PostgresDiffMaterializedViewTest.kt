package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.TransactionBehavior
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Plan-2 §8 D.3b Sub-Slice A: PostgreSQL renderer pins for the new
 * [dev.dmigrate.core.diff.migration.DiffOperation.CreateMaterializedView]
 * / [dev.dmigrate.core.diff.migration.DiffOperation.DropMaterializedView]
 * operations. Kept in its own file so
 * [PostgresDiffDdlGeneratorTest] stays under Detekt's `LargeClass`
 * threshold.
 */
class PostgresDiffMaterializedViewTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun plan(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        overlays: (DiffResult) -> List<MigrationOverlayDocument> = { emptyList() },
    ): DiffResult {
        val planned = planner.plan(current, desired, diff)
        return planned.copy(migrationOverlays = overlays(planned))
    }

    fun planAndUp(diff: SchemaDiff) =
        gen.generateUp(plan(diff), DdlGenerationOptions())

    fun planAndDown(diff: SchemaDiff) =
        gen.generateDown(plan(diff), DdlGenerationOptions())

    test("CreateMaterializedView renders CREATE MATERIALIZED VIEW on PostgreSQL") {
        val view = ViewDefinition(query = "SELECT 1 AS one", materialized = true)
        val up = planAndUp(SchemaDiff(viewsAdded = listOf(NamedView("mv_x", view))))

        up.isBlocked shouldBe false
        val stmt = up.statements.single()
        stmt.sql shouldContainStr "CREATE MATERIALIZED VIEW"
        stmt.sql shouldContainStr "\"mv_x\""
        stmt.sql shouldContainStr "SELECT 1 AS one"
        stmt.sql.endsWith(";") shouldBe true
    }

    test("CreateMaterializedView Down emits DROP MATERIALIZED VIEW on PostgreSQL") {
        val view = ViewDefinition(query = "SELECT 1", materialized = true)
        val down = planAndDown(SchemaDiff(viewsAdded = listOf(NamedView("mv_x", view))))

        down.isBlocked shouldBe false
        down.statements.single().sql shouldContainStr "DROP MATERIALIZED VIEW \"mv_x\""
    }

    test("CreateMaterializedView Up without query is blocked by the planner diagnostic") {
        val view = ViewDefinition(query = null, materialized = true)
        val planned = planner.plan(
            emptySchema(),
            emptySchema(),
            SchemaDiff(viewsAdded = listOf(NamedView("mv_x", view))),
        )
        val planBlocker = planned.diagnostics.single {
            it.code == "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED"
        }
        planBlocker.message shouldContainStr "mv_x"

        val up = gen.generateUp(planned, DdlGenerationOptions())
        up.statements.shouldBeEmpty()
        up.isBlocked shouldBe true
        up.diagnostics.any { it.code == "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED" } shouldBe true
    }

    test("DropMaterializedView Up emits DROP MATERIALIZED VIEW on PostgreSQL") {
        val view = ViewDefinition(query = "SELECT 1", materialized = true)
        val up = planAndUp(SchemaDiff(viewsRemoved = listOf(NamedView("mv_x", view))))

        up.statements.single().sql shouldContainStr "DROP MATERIALIZED VIEW \"mv_x\""
    }

    test("DropMaterializedView Down reconstructs via CREATE MATERIALIZED VIEW when query is known") {
        val view = ViewDefinition(query = "SELECT id FROM users", materialized = true)
        val down = planAndDown(SchemaDiff(viewsRemoved = listOf(NamedView("mv_x", view))))

        down.isBlocked shouldBe false
        val stmt = down.statements.single()
        stmt.sql shouldContainStr "CREATE MATERIALIZED VIEW \"mv_x\""
        stmt.sql shouldContainStr "SELECT id FROM users"
    }

    test("DropMaterializedView without query still renders the Up DROP statement (not blocked)") {
        // Regression pin for the Sub-Slice A review: a missing `query`
        // body must NOT block the forward DROP MATERIALIZED VIEW DDL — the
        // operator can still apply Up; only the rollback contract reflects
        // the missing body. The planner-emitted diagnostic is WARNING-only
        // so toResult does not promote it into a render blocker.
        val view = ViewDefinition(query = null, materialized = true)
        val planned = planner.plan(
            emptySchema(),
            emptySchema(),
            SchemaDiff(viewsRemoved = listOf(NamedView("mv_x", view))),
        )
        planned.diagnostics.any { it.code == "BLOCKED_DOWN_QUERY_UNKNOWN" } shouldBe true

        val up = gen.generateUp(planned, DdlGenerationOptions())
        up.isBlocked shouldBe false
        up.statements.single().sql shouldContainStr "DROP MATERIALIZED VIEW \"mv_x\""
    }

    test("DropMaterializedView without query blocks rollback with MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN") {
        val view = ViewDefinition(query = null, materialized = true)
        val planned = planner.plan(
            emptySchema(),
            emptySchema(),
            SchemaDiff(viewsRemoved = listOf(NamedView("mv_x", view))),
        )
        planned.diagnostics.any { it.code == "BLOCKED_DOWN_QUERY_UNKNOWN" } shouldBe true

        val down = gen.generateDown(planned, DdlGenerationOptions())
        down.statements.shouldBeEmpty()
        down.diagnostics.any { it.code == "MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN" } shouldBe true
    }

    test("ReplaceMaterializedView Up emits DROP + CREATE under one operation id") {
        val before = ViewDefinition(query = "SELECT 1", materialized = true)
        val after = before.copy(query = "SELECT 2")
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(
                ViewDiff(name = "mv_x", query = ValueChange("SELECT 1", "SELECT 2")),
            )),
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())

        up.isBlocked shouldBe false
        up.statements.size shouldBe 2
        up.statements[0].sql shouldContainStr "DROP MATERIALIZED VIEW \"mv_x\""
        up.statements[1].sql shouldContainStr "CREATE MATERIALIZED VIEW \"mv_x\""
        up.statements[1].sql shouldContainStr "SELECT 2"
        // Plan §8: both statements MUST share the same operationId so
        // executionStatementGroups treats them as one atomic unit.
        up.statements.flatMap { it.operationIds }.distinct().size shouldBe 1
        // PG-DDL is transactional under the runner-owned transaction —
        // pinned via the FULLY_TRANSACTIONAL hint so readers do not see
        // the intermediate dropped state between DROP and CREATE. Plan §5
        // also pins `locking=ACCESS_EXCLUSIVE` at the statement-hint
        // level (TABLE_EXCLUSIVE + requiresExclusiveAccess) — MV
        // statements are NOT catalog-only metadata writes, the initial
        // refresh takes an exclusive lock on the target relation.
        up.statements.all {
            it.hints.transactionBehavior == TransactionBehavior.FULLY_TRANSACTIONAL
        } shouldBe true
        up.statements.all { it.hints.lockBehavior == LockBehavior.TABLE_EXCLUSIVE } shouldBe true
        up.statements.all { it.hints.requiresExclusiveAccess } shouldBe true
    }

    test("Materialized-view statements use TABLE_EXCLUSIVE locking hints for Create/Drop too") {
        // The Create and Drop paths share the same lock-hint reasoning as
        // Replace (Plan §5 / §6.4). Pinning here so a future refactor
        // that swaps the hint pool back to POSTGRES_METADATA_HINTS fails
        // loudly.
        val createUp = planAndUp(
            SchemaDiff(
                viewsAdded = listOf(
                    NamedView(
                        "mv_x",
                        ViewDefinition(query = "SELECT 1", materialized = true),
                    ),
                ),
            ),
        )
        createUp.statements.single().hints.lockBehavior shouldBe LockBehavior.TABLE_EXCLUSIVE
        createUp.statements.single().hints.requiresExclusiveAccess shouldBe true

        val dropUp = planAndUp(
            SchemaDiff(
                viewsRemoved = listOf(
                    NamedView(
                        "mv_x",
                        ViewDefinition(query = "SELECT 1", materialized = true),
                    ),
                ),
            ),
        )
        dropUp.statements.single().hints.lockBehavior shouldBe LockBehavior.TABLE_EXCLUSIVE
        dropUp.statements.single().hints.requiresExclusiveAccess shouldBe true
    }

    test("ReplaceMaterializedView Down emits DROP + CREATE inverse using before.query") {
        val before = ViewDefinition(query = "SELECT 1", materialized = true)
        val after = before.copy(query = "SELECT 2")
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(
                ViewDiff(name = "mv_x", query = ValueChange("SELECT 1", "SELECT 2")),
            )),
        )
        val down = gen.generateDown(plan, DdlGenerationOptions())

        down.isBlocked shouldBe false
        down.statements.size shouldBe 2
        down.statements[0].sql shouldContainStr "DROP MATERIALIZED VIEW \"mv_x\""
        down.statements[1].sql shouldContainStr "CREATE MATERIALIZED VIEW \"mv_x\""
        down.statements[1].sql shouldContainStr "SELECT 1"
    }

    test("ReplaceMaterializedView Up without after.query is blocked by planner DIFF_METADATA") {
        val before = ViewDefinition(query = "SELECT 1", materialized = true)
        val after = ViewDefinition(query = null, materialized = true)
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(
                ViewDiff(name = "mv_x", query = ValueChange("SELECT 1", null)),
            )),
        )
        plan.diagnostics.any {
            it.code == "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED" &&
                it.severity == dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.BLOCKER
        } shouldBe true

        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.shouldBeEmpty()
        up.isBlocked shouldBe true
        up.diagnostics.any { it.code == "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED" } shouldBe true
    }

    test("ReplaceMaterializedView Down without before.query blocks with MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN") {
        val before = ViewDefinition(query = null, materialized = true)
        val after = ViewDefinition(query = "SELECT 2", materialized = true)
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(
                ViewDiff(name = "mv_x", query = ValueChange(null, "SELECT 2")),
            )),
        )
        // Up renders fine: after.query is present, only the Down direction
        // needs before.query.
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.isBlocked shouldBe false
        up.statements.size shouldBe 2

        val down = gen.generateDown(plan, DdlGenerationOptions())
        down.statements.shouldBeEmpty()
        down.diagnostics.any { it.code == "MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN" } shouldBe true
    }

    test("View↔MaterializedView conversion is blocked deterministically with BLOCKED_CONVERSION_UNSUPPORTED") {
        val before = ViewDefinition(query = "SELECT 1", materialized = false)
        val after = ViewDefinition(query = "SELECT 1", materialized = true)
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val planned = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(
                ViewDiff(name = "mv_x", materialized = ValueChange(false, true)),
            )),
        )

        val conversion = planned.diagnostics.single { it.code == "BLOCKED_CONVERSION_UNSUPPORTED" }
        conversion.message shouldContainStr "mv_x"
        conversion.message shouldContainStr "false"
        conversion.message shouldContainStr "true"
    }
})
