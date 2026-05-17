package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Plan-2 §8 D.3b Sub-Slice A: cover the planner-side routing for
 * materialized-view diffs without depending on a dialect renderer. The
 * dialect-renderer-specific behaviour is asserted in the per-driver
 * test suites; this spec pins the [DiffOperation]-shape and planner
 * diagnostics emitted by [OperationMapper.mapViews].
 */
class OperationMapperMaterializedViewTest : FunSpec({

    val planner = DiffPlanner()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    test("viewsAdded with materialized=true emits a CreateMaterializedView op") {
        val view = ViewDefinition(query = "SELECT 1", materialized = true)
        val diff = SchemaDiff(viewsAdded = listOf(NamedView("mv_x", view)))
        val plan = planner.plan(emptySchema(), emptySchema(), diff)

        val createMv = plan.operations.filterIsInstance<DiffOperation.CreateMaterializedView>().single()
        createMv.objectRef.type shouldBe DiffObjectType.MATERIALIZED_VIEW
        createMv.objectRef.rootName shouldBe "mv_x"
        createMv.view.materialized shouldBe true
        plan.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }.shouldBeEmpty()
    }

    test("viewsAdded with materialized=false keeps the regular CreateView op") {
        val view = ViewDefinition(query = "SELECT 1", materialized = false)
        val diff = SchemaDiff(viewsAdded = listOf(NamedView("v_x", view)))
        val plan = planner.plan(emptySchema(), emptySchema(), diff)

        plan.operations.filterIsInstance<DiffOperation.CreateView>().single().objectRef.rootName shouldBe "v_x"
        plan.operations.filterIsInstance<DiffOperation.CreateMaterializedView>().shouldBeEmpty()
    }

    test("viewsAdded with materialized=true but missing query emits a planner blocker") {
        val view = ViewDefinition(query = null, materialized = true)
        val diff = SchemaDiff(viewsAdded = listOf(NamedView("mv_x", view)))
        val plan = planner.plan(emptySchema(), emptySchema(), diff)

        val op = plan.operations.filterIsInstance<DiffOperation.CreateMaterializedView>().single()
        val blocker = plan.diagnostics.single {
            it.code == "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED"
        }
        blocker.severity shouldBe DiffDiagnostic.Severity.BLOCKER
        blocker.operationId shouldBe op.id
        blocker.message shouldContain "mv_x"
    }

    test("viewsRemoved with materialized=true emits a DropMaterializedView op") {
        val view = ViewDefinition(query = "SELECT 1", materialized = true)
        val diff = SchemaDiff(viewsRemoved = listOf(NamedView("mv_x", view)))
        val plan = planner.plan(emptySchema(), emptySchema(), diff)

        val dropMv = plan.operations.filterIsInstance<DiffOperation.DropMaterializedView>().single()
        dropMv.objectRef.type shouldBe DiffObjectType.MATERIALIZED_VIEW
        dropMv.objectRef.rootName shouldBe "mv_x"
        plan.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }.shouldBeEmpty()
    }

    test("viewsRemoved with materialized=true and missing query emits a WARNING-severity BLOCKED_DOWN_QUERY_UNKNOWN") {
        // Severity must stay WARNING: every dialect's RenderContext.toResult
        // promotes plan-level BLOCKER diagnostics into a
        // DIALECT_UNSUPPORTED_OPERATION MigrationBlocker — that would flip
        // `isBlocked=true` and stop the forward DDL from executing, even
        // though `DROP MATERIALIZED VIEW <name>` is perfectly renderable
        // without the original query body. The rollback impact is captured
        // by the report contract via the WARNING-coded diagnostic.
        val view = ViewDefinition(query = null, materialized = true)
        val diff = SchemaDiff(viewsRemoved = listOf(NamedView("mv_x", view)))
        val plan = planner.plan(emptySchema(), emptySchema(), diff)

        val op = plan.operations.filterIsInstance<DiffOperation.DropMaterializedView>().single()
        val diagnostic = plan.diagnostics.single { it.code == "BLOCKED_DOWN_QUERY_UNKNOWN" }
        diagnostic.severity shouldBe DiffDiagnostic.Severity.WARNING
        diagnostic.operationId shouldBe op.id
    }

    test("viewsAdded with ViewDefinition.refresh set emits BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED WARNING") {
        // Plan §2 / §6.4.1: refresh has no D.3b semantics. The mapper
        // emits a WARNING (not BLOCKER) so the Up DDL still runs while
        // the report contract surfaces the OOS gap.
        val view = ViewDefinition(query = "SELECT 1", materialized = true, refresh = "MANUAL")
        val diff = SchemaDiff(viewsAdded = listOf(NamedView("mv_x", view)))
        val plan = planner.plan(emptySchema(), emptySchema(), diff)

        val op = plan.operations.filterIsInstance<DiffOperation.CreateMaterializedView>().single()
        val diagnostic = plan.diagnostics.single { it.code == "BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED" }
        diagnostic.severity shouldBe DiffDiagnostic.Severity.WARNING
        diagnostic.operationId shouldBe op.id
        diagnostic.message shouldContain "mv_x"
        diagnostic.message shouldContain "MANUAL"
    }

    test("viewsRemoved with ViewDefinition.refresh set emits BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED WARNING") {
        val view = ViewDefinition(query = "SELECT 1", materialized = true, refresh = "MANUAL")
        val diff = SchemaDiff(viewsRemoved = listOf(NamedView("mv_x", view)))
        val plan = planner.plan(emptySchema(), emptySchema(), diff)

        val op = plan.operations.filterIsInstance<DiffOperation.DropMaterializedView>().single()
        val diagnostic = plan.diagnostics.single { it.code == "BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED" }
        diagnostic.severity shouldBe DiffDiagnostic.Severity.WARNING
        diagnostic.operationId shouldBe op.id
    }

    test("viewsChanged on both-materialized routes to ReplaceMaterializedView (Sub-Slice B)") {
        val before = ViewDefinition(query = "SELECT 1", materialized = true)
        val after = ViewDefinition(query = "SELECT 2", materialized = true)
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(ViewDiff(name = "mv_x", query = ValueChange("SELECT 1", "SELECT 2")))),
        )

        val replace = plan.operations.filterIsInstance<DiffOperation.ReplaceMaterializedView>().single()
        replace.objectRef.type shouldBe DiffObjectType.MATERIALIZED_VIEW
        replace.objectRef.rootName shouldBe "mv_x"
        replace.before.query shouldBe "SELECT 1"
        replace.after.query shouldBe "SELECT 2"
        plan.operations.filterIsInstance<DiffOperation.ReplaceView>()
            .none { it.objectRef.rootName == "mv_x" } shouldBe true
        plan.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }.shouldBeEmpty()
    }

    test("viewsChanged on both-materialized with missing after.query emits BLOCKER DIFF_METADATA") {
        val before = ViewDefinition(query = "SELECT 1", materialized = true)
        val after = ViewDefinition(query = null, materialized = true)
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(ViewDiff(name = "mv_x", query = ValueChange("SELECT 1", null)))),
        )

        val op = plan.operations.filterIsInstance<DiffOperation.ReplaceMaterializedView>().single()
        val blocker = plan.diagnostics.single {
            it.code == "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED"
        }
        blocker.severity shouldBe DiffDiagnostic.Severity.BLOCKER
        blocker.operationId shouldBe op.id
    }

    test("viewsChanged on both-materialized with missing before.query emits WARNING REPLACE_DOWN_BODY") {
        val before = ViewDefinition(query = null, materialized = true)
        val after = ViewDefinition(query = "SELECT 2", materialized = true)
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(ViewDiff(name = "mv_x", query = ValueChange(null, "SELECT 2")))),
        )

        val op = plan.operations.filterIsInstance<DiffOperation.ReplaceMaterializedView>().single()
        val diagnostic = plan.diagnostics.single { it.code == "BLOCKED_REPLACE_DOWN_BODY_UNKNOWN" }
        diagnostic.severity shouldBe DiffDiagnostic.Severity.WARNING
        diagnostic.operationId shouldBe op.id
    }

    test("Replace where only before.refresh is set still surfaces BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED") {
        // Regression pin: an operator removing the `refresh` field during a
        // Replace (before.refresh = "MANUAL", after.refresh = null) must still
        // surface the OOS gap. Without this asymmetric check the WARNING would
        // be silently swallowed because the previous implementation only
        // inspected `after.refresh`.
        val before = ViewDefinition(query = "SELECT 1", materialized = true, refresh = "MANUAL")
        val after = ViewDefinition(query = "SELECT 2", materialized = true, refresh = null)
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(ViewDiff(name = "mv_x", refresh = ValueChange("MANUAL", null)))),
        )

        val op = plan.operations.filterIsInstance<DiffOperation.ReplaceMaterializedView>().single()
        val diagnostic = plan.diagnostics.single { it.code == "BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED" }
        diagnostic.severity shouldBe DiffDiagnostic.Severity.WARNING
        diagnostic.operationId shouldBe op.id
        diagnostic.message shouldContain "MANUAL"
    }

    test("Replace where after.refresh is set takes precedence over before.refresh") {
        // When both sides carry a `refresh` value, the diagnostic message
        // should describe the operator's desired state (after), not the
        // legacy state (before).
        val before = ViewDefinition(query = "SELECT 1", materialized = true, refresh = "OLD")
        val after = ViewDefinition(query = "SELECT 2", materialized = true, refresh = "NEW")
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(ViewDiff(name = "mv_x", refresh = ValueChange("OLD", "NEW")))),
        )

        val diagnostics = plan.diagnostics.filter { it.code == "BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED" }
        diagnostics.size shouldBe 1
        diagnostics.single().message shouldContain "NEW"
    }

    test("viewsChanged with materialized flag flip emits BLOCKED_CONVERSION_UNSUPPORTED") {
        val before = ViewDefinition(query = "SELECT 1", materialized = false)
        val after = before.copy(materialized = true)
        val current = emptySchema().copy(views = mapOf("mv_x" to before))
        val desired = emptySchema().copy(views = mapOf("mv_x" to after))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsChanged = listOf(ViewDiff(name = "mv_x", materialized = ValueChange(false, true)))),
        )

        // Conversion path keeps a ReplaceView placeholder so the report builder
        // has an operation to attach the contract to. The dedicated MV ops
        // intentionally stay absent.
        plan.operations.filterIsInstance<DiffOperation.CreateMaterializedView>().shouldBeEmpty()
        plan.operations.filterIsInstance<DiffOperation.DropMaterializedView>().shouldBeEmpty()
        val replace = plan.operations.filterIsInstance<DiffOperation.ReplaceView>().single()
        val blocker = plan.diagnostics.single { it.code == "BLOCKED_CONVERSION_UNSUPPORTED" }
        blocker.severity shouldBe DiffDiagnostic.Severity.BLOCKER
        blocker.operationId shouldBe replace.id
        blocker.message shouldContain "mv_x"
        blocker.message shouldContain "false"
        blocker.message shouldContain "true"
    }
})
