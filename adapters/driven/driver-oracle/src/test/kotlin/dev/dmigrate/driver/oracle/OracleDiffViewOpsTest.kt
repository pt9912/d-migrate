package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
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
import io.kotest.matchers.shouldBe

/**
 * Sub-Slice 5c, View-Haelfte. Die Erwartungen beruhen auf live gemessenen
 * Oracle-Eigenheiten (siehe [OracleDiffViewOps]): `CREATE OR REPLACE VIEW`
 * darf die Spaltenliste aendern (kein Signatur-Waechter noetig),
 * `ALTER VIEW ... RENAME TO` existiert nicht, und `FORCE` legt auch ueber
 * fehlendem Unterbau an.
 */
class OracleDiffViewOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = OracleDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun up(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun down(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    val view = ViewDefinition(query = "SELECT id FROM users")

    test("CreateView renders CREATE OR REPLACE FORCE VIEW; down drops it") {
        val diff = SchemaDiff(viewsAdded = listOf(NamedView("v_users", view)))
        up(diff).statements.single().sql shouldBe
            "CREATE OR REPLACE FORCE VIEW \"v_users\" AS\nSELECT id FROM users;"
        down(diff).statements.single().sql shouldBe "DROP VIEW \"v_users\";"
    }

    test("DropView renders DROP VIEW; down recreates from the carried definition") {
        val diff = SchemaDiff(viewsRemoved = listOf(NamedView("v_users", view)))
        up(diff).statements.single().sql shouldBe "DROP VIEW \"v_users\";"
        down(diff).statements.single().sql shouldBe
            "CREATE OR REPLACE FORCE VIEW \"v_users\" AS\nSELECT id FROM users;"
    }

    test("ReplaceView swaps the body per direction — no signature guard, Oracle may change the column list") {
        val before = ViewDefinition(query = "SELECT id FROM users")
        val after = ViewDefinition(query = "SELECT id, name FROM users")
        val diff = SchemaDiff(viewsChanged = listOf(ViewDiff(name = "v_users", query = ValueChange(before.query, after.query))))
        val current = emptySchema().copy(views = mapOf("v_users" to before))
        val desired = emptySchema().copy(views = mapOf("v_users" to after))
        up(diff, current, desired).statements.single().sql shouldBe
            "CREATE OR REPLACE FORCE VIEW \"v_users\" AS\nSELECT id, name FROM users;"
        down(diff, current, desired).statements.single().sql shouldBe
            "CREATE OR REPLACE FORCE VIEW \"v_users\" AS\nSELECT id FROM users;"
    }

    test("a view without a query blocks — the Generate path would skip it silently") {
        val diff = SchemaDiff(viewsAdded = listOf(NamedView("v_empty", ViewDefinition(query = null))))
        val r = up(diff)
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.any { it.code == "ORACLE_VIEW_WITHOUT_QUERY" } shouldBe true
    }

    test("a non-portable body blocks with E053, as in the Generate path") {
        val foreign = ViewDefinition(query = "SELECT `id` FROM `users`", sourceDialect = "mysql")
        val r = up(SchemaDiff(viewsAdded = listOf(NamedView("v_foreign", foreign))))
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "E053" } shouldBe true
    }

    test("the planner routes a materialized view to its own operation, which is not supported either") {
        // Ueber den Planner erreicht eine materialisierte Sicht `renderCreateView`
        // gar nicht: OperationMapper macht daraus `CreateMaterializedView`.
        val mv = ViewDefinition(query = "SELECT id FROM users", materialized = true)
        val r = up(SchemaDiff(viewsAdded = listOf(NamedView("mv_users", mv))))
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("a materialized view reaching the view renderer directly blocks — temporarily, until Slice 10") {
        // Der Waechter ist ueber den Planner unerreichbar (siehe oben), aber der
        // Renderer muss auch fuer handgebaute DiffResults (Artefakt-
        // Deserialisierung) richtig antworten, statt die Refresh-Semantik still
        // zu verlieren. Deshalb hier direkt konstruiert.
        val mv = ViewDefinition(query = "SELECT id FROM users", materialized = true)
        val op = DiffOperation.CreateView(
            id = "create-mv",
            objectRef = DiffObjectRef(DiffObjectType.VIEW, listOf("mv_users")),
            view = mv,
        )
        val plan = DiffResult(
            current = DiffEndpoint(schemaName = "App"),
            desired = DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = listOf(op),
        )
        val r = gen.generateUp(plan, DdlGenerationOptions())
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_MATERIALIZED_VIEW_DIFF_UNSUPPORTED" } shouldBe true
    }

    test("view DDL is metadata-only — lighter lock than table DDL, no exclusive access") {
        val stmt = up(SchemaDiff(viewsAdded = listOf(NamedView("v_users", view)))).statements.single()
        stmt.hints.lockBehavior shouldBe LockBehavior.METADATA
        stmt.hints.requiresExclusiveAccess shouldBe false
    }

    test("RenameView uses the standalone RENAME — ALTER VIEW ... RENAME TO does not exist in Oracle") {
        val op = DiffOperation.RenameView(
            id = "rename-view",
            objectRef = DiffObjectRef(DiffObjectType.VIEW, listOf("v_new")),
            fromName = "v_old",
            toName = "v_new",
            overlaySource = "ovl/rename.json",
            overlayEntryId = "v_old->v_new",
            overlayHash = null,
        )
        val plan = DiffResult(
            current = DiffEndpoint(schemaName = "App"),
            desired = DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = listOf(op),
        )
        gen.generateUp(plan, DdlGenerationOptions()).statements.single().sql shouldBe
            "RENAME \"v_old\" TO \"v_new\";"
        gen.generateDown(plan, DdlGenerationOptions()).statements.single().sql shouldBe
            "RENAME \"v_new\" TO \"v_old\";"
    }

    test("a View-to-MV flip renders as ReplaceView and is caught by the guard on the other side") {
        // OperationMapper macht daraus ein gewoehnliches ReplaceView; ohne den
        // beidseitigen Waechter rendert die Up-Richtung ueber eine
        // materialisierte Ausgangssicht hinweg.
        val op = DiffOperation.ReplaceView(
            id = "replace-view",
            objectRef = DiffObjectRef(DiffObjectType.VIEW, listOf("v_x")),
            before = ViewDefinition(query = "SELECT id FROM users", materialized = true),
            after = ViewDefinition(query = "SELECT id FROM users"),
        )
        val plan = DiffResult(
            current = DiffEndpoint(schemaName = "App"),
            desired = DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = listOf(op),
        )
        val r = gen.generateUp(plan, DdlGenerationOptions())
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_MATERIALIZED_VIEW_DIFF_UNSUPPORTED" } shouldBe true
    }
})
