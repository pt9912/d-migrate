package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.NamedProcedure
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
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * F.4 Sub-Slice B (2026-05-19): per-dialect pins for the MySQL object-
 * rename pipeline. The native side is `RENAME TABLE` for views; every
 * other kind goes through `RenameSupport.DropCreateFallback` (triggers,
 * routines) or `RenameSupport.Blocked` (sequences, materialized views).
 *
 * Mapper-level tests check that the policy choice surfaces as the
 * right operation shape — `RenameView` for views, `Drop+Create` with a
 * `RenameProvenance` marker for triggers/routines, `OBJECT_RENAME_UNSUPPORTED`
 * diagnostic for blocked kinds.
 *
 * Renderer-level tests pin the actual SQL.
 */
class MysqlDiffObjectRenameTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()
    val mysqlCaps = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.MYSQL)

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun renameOverlay(objectType: String, from: String, to: String): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "mysql",
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

    test("view rename Up renders RENAME TABLE old_view TO new_view") {
        val view = ViewDefinition(query = "SELECT 1")
        val plan = planner.plan(
            current = emptySchema().copy(views = mapOf("v_old" to view)),
            desired = emptySchema().copy(views = mapOf("v_new" to view)),
            schemaDiff = SchemaDiff(
                viewsAdded = listOf(NamedView("v_new", view)),
                viewsRemoved = listOf(NamedView("v_old", view)),
            ),
            migrationOverlays = listOf(renameOverlay("view", "v_old", "v_new")),
            capabilities = mysqlCaps,
        )
        // Mapper-side: native RenameView, no Drop+Create residue.
        plan.operations.filterIsInstance<DiffOperation.CreateView>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.DropView>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.RenameView>().size shouldBe 1

        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.size shouldBe 1
        up.statements.single().sql shouldContainStr "RENAME TABLE `v_old` TO `v_new`;"
    }

    test("view rename Down renders the inverse RENAME TABLE") {
        val view = ViewDefinition(query = "SELECT 1")
        val plan = planner.plan(
            current = emptySchema().copy(views = mapOf("v_old" to view)),
            desired = emptySchema().copy(views = mapOf("v_new" to view)),
            schemaDiff = SchemaDiff(
                viewsAdded = listOf(NamedView("v_new", view)),
                viewsRemoved = listOf(NamedView("v_old", view)),
            ),
            migrationOverlays = listOf(renameOverlay("view", "v_old", "v_new")),
            capabilities = mysqlCaps,
        )
        val down = gen.generateDown(plan, DdlGenerationOptions())
        down.statements.size shouldBe 1
        down.statements.single().sql shouldContainStr "RENAME TABLE `v_new` TO `v_old`;"
    }

    test("materialized view rename is blocked by the MySQL policy") {
        val mv = ViewDefinition(query = "SELECT 1", materialized = true)
        val plan = planner.plan(
            current = emptySchema().copy(views = mapOf("mv_old" to mv)),
            desired = emptySchema().copy(views = mapOf("mv_new" to mv)),
            schemaDiff = SchemaDiff(
                viewsAdded = listOf(NamedView("mv_new", mv)),
                viewsRemoved = listOf(NamedView("mv_old", mv)),
            ),
            migrationOverlays = listOf(renameOverlay("view", "mv_old", "mv_new")),
            capabilities = mysqlCaps,
        )
        plan.operations.filterIsInstance<DiffOperation.RenameView>() shouldBe emptyList()
        plan.diagnostics.map { it.code } shouldContain "OBJECT_RENAME_UNSUPPORTED"
    }

    // ── Trigger ─────────────────────────────────────────────────────

    test("trigger rename folds to Drop+Create with RenameProvenance marker on MySQL") {
        val trig = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "INSERT INTO audit VALUES (NEW.id)",
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
            capabilities = mysqlCaps,
        )
        // No native RenameTrigger emitted.
        plan.operations.filterIsInstance<DiffOperation.RenameTrigger>() shouldBe emptyList()
        // Drop+Create still emitted, both carrying the rename provenance marker.
        val drop = plan.operations.filterIsInstance<DiffOperation.DropTrigger>().single()
        val create = plan.operations.filterIsInstance<DiffOperation.CreateTrigger>().single()
        drop.renameProvenance shouldNotBe null
        create.renameProvenance shouldNotBe null
        drop.renameProvenance?.fromPath shouldBe listOf("orders", "audit_old")
        create.renameProvenance?.toPath shouldBe listOf("orders", "audit_new")
        drop.renameProvenance?.fallbackReason shouldContainStr "ALTER TRIGGER"
    }

    test("trigger rename with body drift is blocked on MySQL") {
        val before = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "v1",
        )
        val after = before.copy(body = "v2")
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
            capabilities = mysqlCaps,
        )
        plan.operations.filterIsInstance<DiffOperation.RenameTrigger>() shouldBe emptyList()
        // No fallback marker either — the policy returned Blocked.
        plan.operations.filterIsInstance<DiffOperation.CreateTrigger>().single().renameProvenance shouldBe null
        plan.diagnostics.map { it.code } shouldContain "OBJECT_RENAME_UNSUPPORTED"
    }

    test("trigger rename with missing source body is blocked on MySQL") {
        val before = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = null,
        )
        val after = before.copy(body = "INSERT INTO audit VALUES (NEW.id)")
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
            capabilities = mysqlCaps,
        )
        plan.diagnostics.firstOrNull {
            it.code == "OBJECT_RENAME_UNSUPPORTED" && it.message.contains("sourceBodyHash")
        } shouldNotBe null
    }

    // ── Function ────────────────────────────────────────────────────

    test("function rename folds to Drop+Create with RenameProvenance marker on MySQL") {
        val params = listOf(ParameterDefinition(name = "x", type = "int", direction = ParameterDirection.IN))
        val fn = FunctionDefinition(
            parameters = params,
            returns = ReturnType(type = "int"),
            body = "RETURN x + 1",
            language = "sql",
            deterministic = true,
        )
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
            capabilities = mysqlCaps,
        )
        plan.operations.filterIsInstance<DiffOperation.RenameFunction>() shouldBe emptyList()
        val drop = plan.operations.filterIsInstance<DiffOperation.DropFunction>().single()
        val create = plan.operations.filterIsInstance<DiffOperation.CreateFunction>().single()
        drop.renameProvenance?.fromPath shouldBe listOf(fromKey)
        create.renameProvenance?.toPath shouldBe listOf(toKey)
    }

    test("procedure rename folds to Drop+Create with RenameProvenance marker on MySQL") {
        val params = listOf(ParameterDefinition(name = "id", type = "int", direction = ParameterDirection.IN))
        val proc = ProcedureDefinition(parameters = params, body = "BEGIN END", language = "sql")
        val fromKey = ObjectKeyCodec.routineKey("proc_old", params)
        val toKey = ObjectKeyCodec.routineKey("proc_new", params)
        val plan = planner.plan(
            current = emptySchema().copy(procedures = mapOf("proc_old" to proc)),
            desired = emptySchema().copy(procedures = mapOf("proc_new" to proc)),
            schemaDiff = SchemaDiff(
                proceduresAdded = listOf(NamedProcedure("proc_new", proc)),
                proceduresRemoved = listOf(NamedProcedure("proc_old", proc)),
            ),
            migrationOverlays = listOf(renameOverlay("procedure", fromKey, toKey)),
            capabilities = mysqlCaps,
        )
        plan.operations.filterIsInstance<DiffOperation.RenameProcedure>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.DropProcedure>().single().renameProvenance shouldNotBe null
        plan.operations.filterIsInstance<DiffOperation.CreateProcedure>().single().renameProvenance shouldNotBe null
    }

    // ── Sequence ────────────────────────────────────────────────────

    test("sequence rename is blocked on MySQL (E.3 scope)") {
        val seq = SequenceDefinition()
        val plan = planner.plan(
            current = emptySchema().copy(sequences = mapOf("s_old" to seq)),
            desired = emptySchema().copy(sequences = mapOf("s_new" to seq)),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("s_new", seq)),
                sequencesRemoved = listOf(NamedSequence("s_old", seq)),
            ),
            migrationOverlays = listOf(renameOverlay("sequence", "s_old", "s_new")),
            capabilities = mysqlCaps,
        )
        plan.operations.filterIsInstance<DiffOperation.RenameSequence>() shouldBe emptyList()
        plan.diagnostics.firstOrNull {
            it.code == "OBJECT_RENAME_UNSUPPORTED" && it.message.contains("MySQL sequence")
        } shouldNotBe null
    }

    // ── F.4 Renderer-Blocker-Bridge (2026-05-19) ────────────────────

    test("F.4 G: trigger body-drift rename surfaces OBJECT_RENAME_UNSUPPORTED as primaryBlockedReason on MySQL") {
        // Pure Mapper-/Planner-block: the F.4 trigger-body-drift case
        // returns RenameSupport.Blocked from MysqlObjectRenamePolicy
        // and emits a BLOCKER OBJECT_RENAME_UNSUPPORTED diagnostic. The
        // Drop+Create trigger ops still render cleanly on MySQL, so the
        // renderer does NOT emit a competing blocker — the bridge
        // surfaces the planner reason as primary.
        val before = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "INSERT INTO audit VALUES (NEW.id, 1)",
        )
        val after = before.copy(body = "INSERT INTO audit VALUES (NEW.id, 2)")
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
            capabilities = mysqlCaps,
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.isBlocked shouldBe true
        up.primaryBlockedReason shouldBe MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED
        up.blockers.any { it.reason == MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED } shouldBe true
    }
})
