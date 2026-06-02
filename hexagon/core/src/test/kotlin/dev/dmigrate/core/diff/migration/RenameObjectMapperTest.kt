package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.NamedProcedure
import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * F.4 Sub-Slice A.2 Teil 2 mapper tests: the dialect-neutral fold from
 * rename-overlay mappings to the five new `Rename*` `DiffOperation`
 * subtypes (or to a Drop+Create+RenameProvenance fallback, or to an
 * `OBJECT_RENAME_UNSUPPORTED` blocker), driven by
 * [ObjectRenamePolicyRegistry] per dialect. Renderer tests live in the
 * driver modules.
 */
class RenameObjectMapperTest : FunSpec({

    val planner = DiffPlanner()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun renameOverlay(
        entries: List<RenameMappingOverlayEntry>,
        source: String = "ovl/rename.json",
        dialect: String = "postgresql",
    ): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = dialect,
            entries = entries,
            createdAt = "2026-05-19T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = source, overlay = overlay)
    }

    fun renameEntry(objectType: String, from: String, to: String, id: String = "$from-to-$to") =
        RenameMappingOverlayEntry(id = id, objectType = objectType, fromName = from, toName = to)

    // ── Views ───────────────────────────────────────────────────────

    test("view rename overlay folds DropView+CreateView into RenameView") {
        val view = ViewDefinition(query = "SELECT 1")
        val current = emptySchema().copy(views = mapOf("v_old" to view))
        val desired = emptySchema().copy(views = mapOf("v_new" to view))
        val diff = SchemaDiff(
            viewsAdded = listOf(NamedView("v_new", view)),
            viewsRemoved = listOf(NamedView("v_old", view)),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("view", "v_old", "v_new")))),
        )

        plan.operations.filterIsInstance<DiffOperation.CreateView>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.DropView>() shouldBe emptyList()
        val renames = plan.operations.filterIsInstance<DiffOperation.RenameView>()
        renames.size shouldBe 1
        renames.single().fromName shouldBe "v_old"
        renames.single().toName shouldBe "v_new"
        renames.single().objectRef.path shouldBe listOf("v_new")
    }

    test("materialized view rename blocks with OBJECT_RENAME_UNSUPPORTED") {
        val mv = ViewDefinition(query = "SELECT 1", materialized = true)
        val current = emptySchema().copy(views = mapOf("mv_old" to mv))
        val desired = emptySchema().copy(views = mapOf("mv_new" to mv))
        val diff = SchemaDiff(
            viewsAdded = listOf(NamedView("mv_new", mv)),
            viewsRemoved = listOf(NamedView("mv_old", mv)),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("view", "mv_old", "mv_new")))),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameView>() shouldBe emptyList()
        plan.diagnostics.map { it.code } shouldContain RenameObjectMapper.OBJECT_RENAME_UNSUPPORTED
    }

    test("view rename with body drift blocks on PostgreSQL") {
        val before = ViewDefinition(query = "SELECT 1")
        val after = ViewDefinition(query = "SELECT 2")
        val current = emptySchema().copy(views = mapOf("v_old" to before))
        val desired = emptySchema().copy(views = mapOf("v_new" to after))
        val diff = SchemaDiff(
            viewsAdded = listOf(NamedView("v_new", after)),
            viewsRemoved = listOf(NamedView("v_old", before)),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("view", "v_old", "v_new")))),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameView>() shouldBe emptyList()
        plan.diagnostics.map { it.code } shouldContain RenameObjectMapper.OBJECT_RENAME_UNSUPPORTED
    }

    test("cross-document uniqueness pin: duplicate view rename collapses to one op") {
        val view = ViewDefinition(query = "SELECT 1")
        val current = emptySchema().copy(views = mapOf("v_old" to view))
        val desired = emptySchema().copy(views = mapOf("v_new" to view))
        val diff = SchemaDiff(
            viewsAdded = listOf(NamedView("v_new", view)),
            viewsRemoved = listOf(NamedView("v_old", view)),
        )

        // Two distinct overlay documents carrying the same logical
        // rename: index-level dedupe must collapse them to one op.
        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(
                renameOverlay(listOf(renameEntry("view", "v_old", "v_new", id = "a")), source = "doc-a"),
                renameOverlay(listOf(renameEntry("view", "v_old", "v_new", id = "b")), source = "doc-b"),
            ),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameView>().size shouldBe 1
    }

    // ── Sequences ───────────────────────────────────────────────────

    test("sequence rename overlay folds DropSequence+CreateSequence into RenameSequence") {
        val seq = SequenceDefinition(start = 1)
        val current = emptySchema().copy(sequences = mapOf("s_old" to seq))
        val desired = emptySchema().copy(sequences = mapOf("s_new" to seq))
        val diff = SchemaDiff(
            sequencesAdded = listOf(NamedSequence("s_new", seq)),
            sequencesRemoved = listOf(NamedSequence("s_old", seq)),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("sequence", "s_old", "s_new")))),
        )

        plan.operations.filterIsInstance<DiffOperation.CreateSequence>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.DropSequence>() shouldBe emptyList()
        val renames = plan.operations.filterIsInstance<DiffOperation.RenameSequence>()
        renames.size shouldBe 1
        renames.single().fromName shouldBe "s_old"
        renames.single().toName shouldBe "s_new"
    }

    test("MySQL: sequence rename overlay decomposes into DropSequence+CreateSequence with RenameProvenance") {
        // E.3 Sub-Slice C: MySQL has no native sequence-rename
        // grammar; the policy returns DropCreateFallback so the
        // Mapper emits both halves of the rename with shared
        // `renameProvenance` instead of a single RenameSequence op.
        val seq = SequenceDefinition(start = 1)
        val current = emptySchema().copy(sequences = mapOf("s_old" to seq))
        val desired = emptySchema().copy(sequences = mapOf("s_new" to seq))
        val diff = SchemaDiff(
            sequencesAdded = listOf(NamedSequence("s_new", seq)),
            sequencesRemoved = listOf(NamedSequence("s_old", seq)),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("sequence", "s_old", "s_new")))),
            capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.MYSQL),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameSequence>() shouldBe emptyList()
        val drop = plan.operations.filterIsInstance<DiffOperation.DropSequence>().single()
        val create = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single()
        drop.objectRef.rootName shouldBe "s_old"
        create.objectRef.rootName shouldBe "s_new"
        // Both ops carry the rename provenance pointing at the pair.
        drop.renameProvenance shouldNotBe null
        create.renameProvenance shouldNotBe null
        drop.renameProvenance!!.fromPath shouldBe listOf("s_old")
        drop.renameProvenance!!.toPath shouldBe listOf("s_new")
        create.renameProvenance!!.fromPath shouldBe listOf("s_old")
        create.renameProvenance!!.toPath shouldBe listOf("s_new")
        drop.renameProvenance!!.objectType shouldBe DiffObjectType.SEQUENCE
    }

    // ── Functions ───────────────────────────────────────────────────

    test("function rename overlay folds DropFunction+CreateFunction into RenameFunction with signature") {
        val params = listOf(ParameterDefinition(name = "x", type = "int", direction = ParameterDirection.IN))
        val fn = FunctionDefinition(
            parameters = params,
            returns = ReturnType(type = "int"),
            body = "select x + 1",
            language = "sql",
        )
        val current = emptySchema().copy(functions = mapOf("fn_old" to fn))
        val desired = emptySchema().copy(functions = mapOf("fn_new" to fn))
        val diff = SchemaDiff(
            functionsAdded = listOf(NamedFunction("fn_new", fn)),
            functionsRemoved = listOf(NamedFunction("fn_old", fn)),
        )

        val fromKey = ObjectKeyCodec.routineKey("fn_old", params)
        val toKey = ObjectKeyCodec.routineKey("fn_new", params)
        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("function", fromKey, toKey)))),
        )

        plan.operations.filterIsInstance<DiffOperation.CreateFunction>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.DropFunction>() shouldBe emptyList()
        val renames = plan.operations.filterIsInstance<DiffOperation.RenameFunction>()
        renames.size shouldBe 1
        renames.single().fromName shouldBe "fn_old"
        renames.single().toName shouldBe "fn_new"
        renames.single().signature shouldBe params
        renames.single().objectRef.path shouldBe listOf(toKey)
    }

    test("function rename with body drift blocks on PostgreSQL") {
        val params = listOf(ParameterDefinition(name = "x", type = "int", direction = ParameterDirection.IN))
        val before = FunctionDefinition(parameters = params, returns = ReturnType(type = "int"), body = "old body")
        val after = FunctionDefinition(parameters = params, returns = ReturnType(type = "int"), body = "new body")
        val current = emptySchema().copy(functions = mapOf("fn_old" to before))
        val desired = emptySchema().copy(functions = mapOf("fn_new" to after))
        val diff = SchemaDiff(
            functionsAdded = listOf(NamedFunction("fn_new", after)),
            functionsRemoved = listOf(NamedFunction("fn_old", before)),
        )

        val fromKey = ObjectKeyCodec.routineKey("fn_old", params)
        val toKey = ObjectKeyCodec.routineKey("fn_new", params)
        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("function", fromKey, toKey)))),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameFunction>() shouldBe emptyList()
        plan.diagnostics.map { it.code } shouldContain RenameObjectMapper.OBJECT_RENAME_UNSUPPORTED
    }

    test("function overlay signature must match schema signature — mismatch skips fold") {
        val schemaParams = listOf(ParameterDefinition(name = "x", type = "int"))
        val overlayParams = listOf(ParameterDefinition(name = "x", type = "text"))
        val fn = FunctionDefinition(parameters = schemaParams, returns = ReturnType(type = "int"), body = "select 1")
        val current = emptySchema().copy(functions = mapOf("fn_old" to fn))
        val desired = emptySchema().copy(functions = mapOf("fn_new" to fn.copy(parameters = schemaParams)))
        val diff = SchemaDiff(
            functionsAdded = listOf(NamedFunction("fn_new", fn.copy(parameters = schemaParams))),
            functionsRemoved = listOf(NamedFunction("fn_old", fn)),
        )

        // Overlay-side keys carry a different signature than what the
        // schema actually has — neither index-level signature mismatch
        // (both overlay sides match each other) nor schema fold can pin
        // this pair. The fold quietly skips; Drop+Create stays.
        val fromKey = ObjectKeyCodec.routineKey("fn_old", overlayParams)
        val toKey = ObjectKeyCodec.routineKey("fn_new", overlayParams)
        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("function", fromKey, toKey)))),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameFunction>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.CreateFunction>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropFunction>().size shouldBe 1
    }

    test("routine overlay with differing signatures on from/to blocks pre-index") {
        val plan = planner.plan(
            current = emptySchema(),
            desired = emptySchema(),
            schemaDiff = SchemaDiff(),
            migrationOverlays = listOf(
                renameOverlay(listOf(renameEntry("function", "fn(in:int)", "gn(in:text)"))),
            ),
        )
        plan.diagnostics.map { it.code } shouldContain "RENAME_OVERLAY_ROUTINE_SIGNATURE_MISMATCH"
    }

    test("routine overlay without canonical parens blocks pre-index") {
        val plan = planner.plan(
            current = emptySchema(),
            desired = emptySchema(),
            schemaDiff = SchemaDiff(),
            migrationOverlays = listOf(
                renameOverlay(listOf(renameEntry("function", "fn_old", "fn_new"))),
            ),
        )
        plan.diagnostics.map { it.code } shouldContain "RENAME_OVERLAY_ROUTINE_KEY_INVALID"
    }

    // ── Procedures ──────────────────────────────────────────────────

    test("procedure rename overlay folds DropProcedure+CreateProcedure into RenameProcedure") {
        val params = listOf(ParameterDefinition(name = "id", type = "int", direction = ParameterDirection.IN))
        val proc = ProcedureDefinition(parameters = params, body = "BEGIN END", language = "plpgsql")
        val current = emptySchema().copy(procedures = mapOf("proc_old" to proc))
        val desired = emptySchema().copy(procedures = mapOf("proc_new" to proc))
        val diff = SchemaDiff(
            proceduresAdded = listOf(NamedProcedure("proc_new", proc)),
            proceduresRemoved = listOf(NamedProcedure("proc_old", proc)),
        )

        val fromKey = ObjectKeyCodec.routineKey("proc_old", params)
        val toKey = ObjectKeyCodec.routineKey("proc_new", params)
        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("procedure", fromKey, toKey)))),
        )

        plan.operations.filterIsInstance<DiffOperation.CreateProcedure>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.DropProcedure>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.RenameProcedure>().size shouldBe 1
    }

    // ── Triggers ────────────────────────────────────────────────────

    test("trigger rename overlay folds DropTrigger+CreateTrigger into RenameTrigger") {
        val trig = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "audit_fn()",
        )
        val current = emptySchema().copy(triggers = mapOf("audit_old" to trig))
        val desired = emptySchema().copy(triggers = mapOf("audit_new" to trig))
        val diff = SchemaDiff(
            triggersAdded = listOf(NamedTrigger("audit_new", trig)),
            triggersRemoved = listOf(NamedTrigger("audit_old", trig)),
        )

        val fromKey = ObjectKeyCodec.triggerKey("orders", "audit_old")
        val toKey = ObjectKeyCodec.triggerKey("orders", "audit_new")
        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay(listOf(renameEntry("trigger", fromKey, toKey)))),
        )

        plan.operations.filterIsInstance<DiffOperation.CreateTrigger>() shouldBe emptyList()
        plan.operations.filterIsInstance<DiffOperation.DropTrigger>() shouldBe emptyList()
        val renames = plan.operations.filterIsInstance<DiffOperation.RenameTrigger>()
        renames.size shouldBe 1
        renames.single().fromName shouldBe "audit_old"
        renames.single().toName shouldBe "audit_new"
        renames.single().tableName shouldBe "orders"
        renames.single().objectRef.path shouldBe listOf(toKey)
    }

    test("cross-table trigger rename blocks pre-index") {
        val plan = planner.plan(
            current = emptySchema(),
            desired = emptySchema(),
            schemaDiff = SchemaDiff(),
            migrationOverlays = listOf(
                renameOverlay(
                    listOf(renameEntry("trigger", "orders::a", "users::b")),
                ),
            ),
        )
        plan.diagnostics.map { it.code } shouldContain "RENAME_OVERLAY_TRIGGER_CROSS_TABLE_REJECTED"
    }

    test("trigger overlay without canonical key (no ::) blocks pre-index") {
        val plan = planner.plan(
            current = emptySchema(),
            desired = emptySchema(),
            schemaDiff = SchemaDiff(),
            migrationOverlays = listOf(
                renameOverlay(listOf(renameEntry("trigger", "audit_old", "audit_new"))),
            ),
        )
        plan.diagnostics.map { it.code } shouldContain "RENAME_OVERLAY_TRIGGER_KEY_INVALID"
    }

    test("trigger rename with body drift blocks on PostgreSQL") {
        val before = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "audit_fn_v1()",
        )
        val after = before.copy(body = "audit_fn_v2()")
        val current = emptySchema().copy(triggers = mapOf("audit_old" to before))
        val desired = emptySchema().copy(triggers = mapOf("audit_new" to after))
        val diff = SchemaDiff(
            triggersAdded = listOf(NamedTrigger("audit_new", after)),
            triggersRemoved = listOf(NamedTrigger("audit_old", before)),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(
                renameOverlay(
                    listOf(
                        renameEntry(
                            "trigger",
                            ObjectKeyCodec.triggerKey("orders", "audit_old"),
                            ObjectKeyCodec.triggerKey("orders", "audit_new"),
                        ),
                    ),
                ),
            ),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameTrigger>() shouldBe emptyList()
        plan.diagnostics.map { it.code } shouldContain RenameObjectMapper.OBJECT_RENAME_UNSUPPORTED
    }

    test("trigger overlay-vs-schema table mismatch surfaces a residual blocker") {
        // Index-level check is per-overlay (fromTable == toTable);
        // the residual check fires when the schema places the trigger
        // on a different table than the overlay claims. Hard to hit
        // when keys are well-formed, but pinning the safety net
        // protects against pre-plan validators that ever stop running.
        val trig = TriggerDefinition(
            table = "users",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "audit_fn()",
        )
        val current = emptySchema().copy(triggers = mapOf("audit_old" to trig))
        val desired = emptySchema().copy(triggers = mapOf("audit_new" to trig))
        val diff = SchemaDiff(
            triggersAdded = listOf(NamedTrigger("audit_new", trig)),
            triggersRemoved = listOf(NamedTrigger("audit_old", trig)),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(
                renameOverlay(
                    listOf(
                        renameEntry(
                            "trigger",
                            ObjectKeyCodec.triggerKey("orders", "audit_old"),
                            ObjectKeyCodec.triggerKey("orders", "audit_new"),
                        ),
                    ),
                ),
            ),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameTrigger>() shouldBe emptyList()
        plan.diagnostics.firstOrNull { it.code == RenameObjectMapper.OBJECT_RENAME_UNSUPPORTED } shouldNotBe null
    }
})
