package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Sub-Slice 5c: Sichten.
 *
 * Der Schwerpunkt liegt auf dem, was T-SQL hier anders macht als die drei
 * bestehenden Dialekte: `CREATE OR ALTER VIEW` gibt es nativ (also kein
 * Drop-und-Neu-Fenster), `sp_rename` laesst den Rumpf stehen, und
 * Materialized Views existieren gar nicht.
 */
class MssqlDiffViewOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    fun schema(vararg views: Pair<String, ViewDefinition>) =
        SchemaDefinition(name = "App", version = "1", views = views.toMap())

    fun up(diff: SchemaDiff, current: SchemaDefinition = schema(), desired: SchemaDefinition = schema()) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun down(diff: SchemaDiff, current: SchemaDefinition = schema(), desired: SchemaDefinition = schema()) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    val active = ViewDefinition(query = "SELECT id FROM users WHERE active = 1")

    /** Ein DiffResult um eine einzelne, von Hand gebaute Operation. */
    fun diffOf(op: DiffOperation) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = listOf(op),
        currentSchema = schema(),
        desiredSchema = schema(),
    )

    test("CreateView renders CREATE OR ALTER and declares a metadata lock, not a table lock") {
        val r = up(SchemaDiff(viewsAdded = listOf(NamedView("v_active", active))))
        val stmt = r.statements.single()
        stmt.sql shouldBe "CREATE OR ALTER VIEW [v_active] AS\nSELECT id FROM users WHERE active = 1;"
        // Die Basistabellen bleiben les- und schreibbar.
        stmt.hints.lockBehavior shouldBe LockBehavior.METADATA
        stmt.hints.requiresExclusiveAccess shouldBe false
    }

    test("down of CreateView drops the view") {
        val sqls = down(SchemaDiff(viewsAdded = listOf(NamedView("v_active", active)))).statements.map { it.sql }
        sqls.single() shouldBe "DROP VIEW [v_active];"
    }

    test("ReplaceView is ONE statement — T-SQL has CREATE OR ALTER, so there is no gap") {
        val after = ViewDefinition(query = "SELECT id, nick FROM users WHERE active = 1")
        val diff = SchemaDiff(
            viewsChanged = listOf(ViewDiff(name = "v_active", query = ValueChange(active.query, after.query))),
        )
        val current = schema("v_active" to active)
        val desired = schema("v_active" to after)
        val r = up(diff, current, desired)
        // Kein DROP + CREATE: die Sicht ist zu keinem Zeitpunkt weg.
        r.statements.single().sql shouldBe
            "CREATE OR ALTER VIEW [v_active] AS\nSELECT id, nick FROM users WHERE active = 1;"
        r.statements.single().risk.hasGap shouldBe false
    }

    test("down of ReplaceView puts the old body back") {
        val after = ViewDefinition(query = "SELECT id, nick FROM users WHERE active = 1")
        val diff = SchemaDiff(
            viewsChanged = listOf(ViewDiff(name = "v_active", query = ValueChange(active.query, after.query))),
        )
        val sqls = down(diff, schema("v_active" to active), schema("v_active" to after)).statements.map { it.sql }
        sqls.single() shouldContainStr "SELECT id FROM users WHERE active = 1;"
    }

    test("renaming a view says that its stored body keeps the old name") {
        // `RenameView` entsteht nur mit Rename-Overlay; hier direkt gebaut,
        // weil die Aussage am Renderer haengt, nicht am Planner.
        val op = DiffOperation.RenameView(
            id = "RenameView:v_old",
            objectRef = DiffObjectRef(DiffObjectType.VIEW, listOf("v_old")),
            fromName = "v_old",
            toName = "v_new",
            overlaySource = "test",
            overlayEntryId = "e1",
            overlayHash = null,
        )
        val r = gen.generateUp(diffOf(op), DdlGenerationOptions())
        r.statements.single().sql shouldBe "EXEC sp_rename 'v_old', 'v_new';"
        // Der Rumpf in sys.sql_modules sagt weiterhin CREATE VIEW [v_old] —
        // SQL Server stoert das nicht, den Reverse schon.
        r.diagnostics.map { it.code } shouldContain "MSSQL_RENAME_KEEPS_VIEW_BODY"
        // Abwaerts genau andersherum.
        gen.generateDown(diffOf(op), DdlGenerationOptions()).statements.single().sql shouldBe
            "EXEC sp_rename 'v_new', 'v_old';"
    }

    test("DropView removes it upwards and restores it downwards") {
        val diff = SchemaDiff(viewsRemoved = listOf(NamedView("v_active", active)))
        up(diff).statements.single().sql shouldBe "DROP VIEW [v_active];"
        // Der Rumpf steht in der Operation — abwaerts ist die Sicht wiederherstellbar.
        down(diff).statements.single().sql shouldContainStr "CREATE OR ALTER VIEW [v_active]"
    }

    test("a materialized view is blocked, permanently — SQL Server has no equivalent") {
        // Sie kommt als eigene Operation (`CreateMaterializedView`) und wird
        // deshalb am Dispatcher geblockt, nicht im Sicht-Renderer. Kein Slice
        // holt das nach: T-SQL hat kein Aequivalent.
        val matview = ViewDefinition(query = "SELECT 1 AS one", materialized = true)
        val r = up(SchemaDiff(viewsAdded = listOf(NamedView("v_mat", matview))))
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.diagnostics.single { it.code == "DIALECT_UNSUPPORTED_OPERATION" }
            .message shouldContainStr "no materialized views"
    }

    test("a view body that T-SQL cannot parse is blocked with E053, not emitted as broken DDL") {
        // `::`-Cast und `||`-Verkettung sind PostgreSQL, nicht T-SQL.
        val pgView = ViewDefinition(
            query = "SELECT id::text || 'x' AS label FROM users",
            sourceDialect = "postgresql",
        )
        val r = up(SchemaDiff(viewsAdded = listOf(NamedView("v_pg", pgView))))
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "E053"
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    test("a view without a query is blocked rather than rendered as empty DDL") {
        val r = up(SchemaDiff(viewsAdded = listOf(NamedView("v_empty", ViewDefinition()))))
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "MSSQL_VIEW_WITHOUT_QUERY"
    }
})
