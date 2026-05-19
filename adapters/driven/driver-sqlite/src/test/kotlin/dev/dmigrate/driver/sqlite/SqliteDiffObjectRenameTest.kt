package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.RenameProjectionCapabilities
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * F.4 Sub-Slice C (2026-05-19): per-dialect pins for the SQLite
 * object-rename pipeline. SQLite has no native rename grammar for any
 * of the five new objectTypes:
 *
 * - Views fall back to Drop+Create when both view bodies are known and
 *   identical (`SqliteObjectRenamePolicy.DropCreateFallback`).
 * - Triggers fall back to Drop+Create under the same body-availability
 *   contract.
 * - Functions and procedures are blocked outright — SQLite has no
 *   stored-routine model.
 * - Sequences are blocked until an E.3 SQLite-sequence rendering
 *   contract exists.
 * - Materialized views remain blocked across all dialects.
 *
 * The Mapper-level outcome surfaces as a `Drop*`/`Create*` pair tagged
 * with a `RenameProvenance` marker (for the two body-bearing fallback
 * cases) or as a `OBJECT_RENAME_UNSUPPORTED` BLOCKER (for the
 * remaining four kinds). The SQLite renderer never receives any
 * `Rename*` subtype under this contract — the defensive UNSUPPORTED
 * routing in `categorize()` guards against future regressions.
 */
class SqliteDiffObjectRenameTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()
    val sqliteCaps = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.SQLITE)

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun renameOverlay(objectType: String, from: String, to: String): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "sqlite",
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "$from->$to",
                    objectType = objectType,
                    fromName = from,
                    toName = to,
                ),
            ),
            createdAt = "2026-05-19T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = "ovl/rename.json", overlay = overlay)
    }

    // ── View ────────────────────────────────────────────────────────

    test("view rename folds to Drop+Create with RenameProvenance marker on SQLite") {
        val view = ViewDefinition(query = "SELECT 1")
        val plan = planner.plan(
            current = emptySchema().copy(views = mapOf("v_old" to view)),
            desired = emptySchema().copy(views = mapOf("v_new" to view)),
            schemaDiff = SchemaDiff(
                viewsAdded = listOf(NamedView("v_new", view)),
                viewsRemoved = listOf(NamedView("v_old", view)),
            ),
            migrationOverlays = listOf(renameOverlay("view", "v_old", "v_new")),
            capabilities = sqliteCaps,
        )
        plan.operations.filterIsInstance<DiffOperation.RenameView>() shouldBe emptyList()
        val drop = plan.operations.filterIsInstance<DiffOperation.DropView>().single()
        val create = plan.operations.filterIsInstance<DiffOperation.CreateView>().single()
        drop.renameProvenance shouldNotBe null
        create.renameProvenance shouldNotBe null
        drop.renameProvenance?.fromPath shouldBe listOf("v_old")
        create.renameProvenance?.toPath shouldBe listOf("v_new")
        drop.renameProvenance?.fallbackReason shouldContainStr "SQLite has no native view-rename"

        // Renderer side: Up emits DROP VIEW + CREATE VIEW.
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.map { it.sql } shouldContain "DROP VIEW \"v_old\";"
        up.statements.any { it.sql.startsWith("CREATE VIEW \"v_new\"") } shouldBe true
    }

    test("view rename with missing body is blocked on SQLite") {
        val before = ViewDefinition(query = null)
        val after = ViewDefinition(query = "SELECT 1")
        val plan = planner.plan(
            current = emptySchema().copy(views = mapOf("v_old" to before)),
            desired = emptySchema().copy(views = mapOf("v_new" to after)),
            schemaDiff = SchemaDiff(
                viewsAdded = listOf(NamedView("v_new", after)),
                viewsRemoved = listOf(NamedView("v_old", before)),
            ),
            migrationOverlays = listOf(renameOverlay("view", "v_old", "v_new")),
            capabilities = sqliteCaps,
        )
        plan.diagnostics.firstOrNull {
            it.code == "OBJECT_RENAME_UNSUPPORTED" && it.message.contains("sourceBodyHash")
        } shouldNotBe null
        // Drop+Create still emitted by the regular loop, but without a fallback marker.
        plan.operations.filterIsInstance<DiffOperation.CreateView>().single().renameProvenance shouldBe null
    }

    test("view rename with body drift is blocked on SQLite") {
        val before = ViewDefinition(query = "SELECT 1")
        val after = ViewDefinition(query = "SELECT 2")
        val plan = planner.plan(
            current = emptySchema().copy(views = mapOf("v_old" to before)),
            desired = emptySchema().copy(views = mapOf("v_new" to after)),
            schemaDiff = SchemaDiff(
                viewsAdded = listOf(NamedView("v_new", after)),
                viewsRemoved = listOf(NamedView("v_old", before)),
            ),
            migrationOverlays = listOf(renameOverlay("view", "v_old", "v_new")),
            capabilities = sqliteCaps,
        )
        plan.diagnostics.map { it.code } shouldContain "OBJECT_RENAME_UNSUPPORTED"
        plan.operations.filterIsInstance<DiffOperation.CreateView>().single().renameProvenance shouldBe null
    }

    test("materialized view rename is blocked by the SQLite policy") {
        val mv = ViewDefinition(query = "SELECT 1", materialized = true)
        val plan = planner.plan(
            current = emptySchema().copy(views = mapOf("mv_old" to mv)),
            desired = emptySchema().copy(views = mapOf("mv_new" to mv)),
            schemaDiff = SchemaDiff(
                viewsAdded = listOf(NamedView("mv_new", mv)),
                viewsRemoved = listOf(NamedView("mv_old", mv)),
            ),
            migrationOverlays = listOf(renameOverlay("view", "mv_old", "mv_new")),
            capabilities = sqliteCaps,
        )
        plan.diagnostics.firstOrNull {
            it.code == "OBJECT_RENAME_UNSUPPORTED" && it.message.contains("materialized")
        } shouldNotBe null
    }

    // ── Trigger ─────────────────────────────────────────────────────

    test("trigger rename folds to Drop+Create with RenameProvenance marker on SQLite") {
        val trig = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "INSERT INTO audit VALUES (NEW.id);",
        )
        val plan = planner.plan(
            current = emptySchema().copy(triggers = mapOf("audit_old" to trig)),
            desired = emptySchema().copy(triggers = mapOf("audit_new" to trig)),
            schemaDiff = SchemaDiff(
                triggersAdded = listOf(NamedTrigger("audit_new", trig)),
                triggersRemoved = listOf(NamedTrigger("audit_old", trig)),
            ),
            migrationOverlays = listOf(
                renameOverlay(
                    "trigger",
                    ObjectKeyCodec.triggerKey("orders", "audit_old"),
                    ObjectKeyCodec.triggerKey("orders", "audit_new"),
                ),
            ),
            capabilities = sqliteCaps,
        )
        plan.operations.filterIsInstance<DiffOperation.RenameTrigger>() shouldBe emptyList()
        val drop = plan.operations.filterIsInstance<DiffOperation.DropTrigger>().single()
        val create = plan.operations.filterIsInstance<DiffOperation.CreateTrigger>().single()
        drop.renameProvenance shouldNotBe null
        create.renameProvenance shouldNotBe null
        drop.renameProvenance?.fromPath shouldBe listOf("orders", "audit_old")
        create.renameProvenance?.toPath shouldBe listOf("orders", "audit_new")
        drop.renameProvenance?.fallbackReason shouldContainStr "SQLite has no native trigger-rename"
    }

    test("trigger rename with body drift is blocked on SQLite") {
        val before = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "INSERT INTO audit VALUES (NEW.id, 1);",
        )
        val after = before.copy(body = "INSERT INTO audit VALUES (NEW.id, 2);")
        val plan = planner.plan(
            current = emptySchema().copy(triggers = mapOf("audit_old" to before)),
            desired = emptySchema().copy(triggers = mapOf("audit_new" to after)),
            schemaDiff = SchemaDiff(
                triggersAdded = listOf(NamedTrigger("audit_new", after)),
                triggersRemoved = listOf(NamedTrigger("audit_old", before)),
            ),
            migrationOverlays = listOf(
                renameOverlay(
                    "trigger",
                    ObjectKeyCodec.triggerKey("orders", "audit_old"),
                    ObjectKeyCodec.triggerKey("orders", "audit_new"),
                ),
            ),
            capabilities = sqliteCaps,
        )
        plan.diagnostics.map { it.code } shouldContain "OBJECT_RENAME_UNSUPPORTED"
        plan.operations.filterIsInstance<DiffOperation.CreateTrigger>().single().renameProvenance shouldBe null
    }

    // ── Functions / Procedures (blocked: SQLite has no routine model) ──

    test("function rename is blocked on SQLite (no routine model)") {
        val params = listOf(ParameterDefinition(name = "x", type = "int"))
        val fn = FunctionDefinition(parameters = params, returns = ReturnType(type = "int"), body = "RETURN x")
        val fromKey = ObjectKeyCodec.routineKey("fn_old", params)
        val toKey = ObjectKeyCodec.routineKey("fn_new", params)
        val plan = planner.plan(
            current = emptySchema().copy(functions = mapOf("fn_old" to fn)),
            desired = emptySchema().copy(functions = mapOf("fn_new" to fn)),
            schemaDiff = SchemaDiff(
                functionsAdded = listOf(NamedFunction("fn_new", fn)),
                functionsRemoved = listOf(NamedFunction("fn_old", fn)),
            ),
            migrationOverlays = listOf(renameOverlay("function", fromKey, toKey)),
            capabilities = sqliteCaps,
        )
        plan.diagnostics.firstOrNull {
            it.code == "OBJECT_RENAME_UNSUPPORTED" &&
                it.message.contains("SQLite has no user-defined FUNCTION")
        } shouldNotBe null
    }

    // ── Sequence ────────────────────────────────────────────────────

    test("sequence rename is blocked on SQLite (no sequence rendering contract yet)") {
        val seq = SequenceDefinition()
        val plan = planner.plan(
            current = emptySchema().copy(sequences = mapOf("s_old" to seq)),
            desired = emptySchema().copy(sequences = mapOf("s_new" to seq)),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("s_new", seq)),
                sequencesRemoved = listOf(NamedSequence("s_old", seq)),
            ),
            migrationOverlays = listOf(renameOverlay("sequence", "s_old", "s_new")),
            capabilities = sqliteCaps,
        )
        plan.diagnostics.firstOrNull {
            it.code == "OBJECT_RENAME_UNSUPPORTED" && it.message.contains("SQLite sequence")
        } shouldNotBe null
    }
})
