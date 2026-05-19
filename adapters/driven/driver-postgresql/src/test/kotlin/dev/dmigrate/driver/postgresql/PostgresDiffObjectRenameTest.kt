package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.NamedProcedure
import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * F.4 Sub-Slice A.2 Teil 2 renderer pins. The PostgreSQL renderer
 * emits one statement per `Rename*` subtype, with the existing object
 * identity on the left of `ALTER … RENAME TO …` and the new visible
 * name on the right. `objectRef.path[0]` is the canonical target key
 * for plan/report ID stability and must NOT appear in the SQL.
 */
class PostgresDiffObjectRenameTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun renameOverlay(objectType: String, from: String, to: String): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
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

    test("view rename Up renders ALTER VIEW … RENAME TO …") {
        val view = ViewDefinition(query = "SELECT 1")
        val plan = planner.plan(
            current = emptySchema().copy(views = mapOf("v_old" to view)),
            desired = emptySchema().copy(views = mapOf("v_new" to view)),
            schemaDiff = SchemaDiff(
                viewsAdded = listOf(NamedView("v_new", view)),
                viewsRemoved = listOf(NamedView("v_old", view)),
            ),
            migrationOverlays = listOf(renameOverlay("view", "v_old", "v_new")),
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.size shouldBe 1
        up.statements.single().sql shouldContain "ALTER VIEW \"v_old\" RENAME TO \"v_new\";"
    }

    test("view rename Down renders the inverse") {
        val view = ViewDefinition(query = "SELECT 1")
        val plan = planner.plan(
            current = emptySchema().copy(views = mapOf("v_old" to view)),
            desired = emptySchema().copy(views = mapOf("v_new" to view)),
            schemaDiff = SchemaDiff(
                viewsAdded = listOf(NamedView("v_new", view)),
                viewsRemoved = listOf(NamedView("v_old", view)),
            ),
            migrationOverlays = listOf(renameOverlay("view", "v_old", "v_new")),
        )
        val down = gen.generateDown(plan, DdlGenerationOptions())
        down.statements.size shouldBe 1
        down.statements.single().sql shouldContain "ALTER VIEW \"v_new\" RENAME TO \"v_old\";"
    }

    // ── Sequence ────────────────────────────────────────────────────

    test("sequence rename Up renders ALTER SEQUENCE … RENAME TO …") {
        val seq = SequenceDefinition()
        val plan = planner.plan(
            current = emptySchema().copy(sequences = mapOf("s_old" to seq)),
            desired = emptySchema().copy(sequences = mapOf("s_new" to seq)),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("s_new", seq)),
                sequencesRemoved = listOf(NamedSequence("s_old", seq)),
            ),
            migrationOverlays = listOf(renameOverlay("sequence", "s_old", "s_new")),
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.size shouldBe 1
        up.statements.single().sql shouldContain "ALTER SEQUENCE \"s_old\" RENAME TO \"s_new\";"
    }

    test("sequence rename Down renders the inverse") {
        val seq = SequenceDefinition()
        val plan = planner.plan(
            current = emptySchema().copy(sequences = mapOf("s_old" to seq)),
            desired = emptySchema().copy(sequences = mapOf("s_new" to seq)),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("s_new", seq)),
                sequencesRemoved = listOf(NamedSequence("s_old", seq)),
            ),
            migrationOverlays = listOf(renameOverlay("sequence", "s_old", "s_new")),
        )
        val down = gen.generateDown(plan, DdlGenerationOptions())
        down.statements.size shouldBe 1
        down.statements.single().sql shouldContain "ALTER SEQUENCE \"s_new\" RENAME TO \"s_old\";"
    }

    // ── Function ────────────────────────────────────────────────────

    test("function rename Up renders ALTER FUNCTION fromName(types) RENAME TO toName") {
        val params = listOf(ParameterDefinition(name = "x", type = "int", direction = ParameterDirection.IN))
        val fn = FunctionDefinition(
            parameters = params,
            returns = ReturnType(type = "int"),
            body = "select x",
            language = "sql",
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
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.size shouldBe 1
        up.statements.single().sql shouldContain "ALTER FUNCTION \"fn_old\"(int) RENAME TO \"fn_new\";"
        // canonical target key (used in objectRef.path[0]) must not
        // leak into the SQL — the renderer must build identity from
        // op.fromName + op.signature, not from objectRef.
        up.statements.single().sql.contains("fn_new(in:int)") shouldBe false
    }

    test("function rename Down renders the inverse signature-aware SQL") {
        val params = listOf(ParameterDefinition(name = "x", type = "int", direction = ParameterDirection.IN))
        val fn = FunctionDefinition(
            parameters = params,
            returns = ReturnType(type = "int"),
            body = "select x",
            language = "sql",
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
        )
        val down = gen.generateDown(plan, DdlGenerationOptions())
        down.statements.size shouldBe 1
        down.statements.single().sql shouldContain "ALTER FUNCTION \"fn_new\"(int) RENAME TO \"fn_old\";"
    }

    test("function rename signature uses INOUT keyword and skips OUT params") {
        val params = listOf(
            ParameterDefinition(name = "a", type = "int", direction = ParameterDirection.IN),
            ParameterDefinition(name = "b", type = "text", direction = ParameterDirection.INOUT),
            ParameterDefinition(name = "c", type = "bigint", direction = ParameterDirection.OUT),
        )
        val fn = FunctionDefinition(
            parameters = params,
            returns = ReturnType(type = "int"),
            body = "select a",
            language = "sql",
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
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.size shouldBe 1
        // OUT params are excluded; INOUT keeps its keyword.
        up.statements.single().sql shouldContain "ALTER FUNCTION \"fn_old\"(int, INOUT text) RENAME TO \"fn_new\";"
    }

    // ── Procedure ───────────────────────────────────────────────────

    test("procedure rename Up renders ALTER PROCEDURE fromName(types) RENAME TO toName") {
        val params = listOf(ParameterDefinition(name = "x", type = "int", direction = ParameterDirection.IN))
        val proc = ProcedureDefinition(parameters = params, body = "BEGIN END", language = "plpgsql")
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
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.size shouldBe 1
        up.statements.single().sql shouldContain "ALTER PROCEDURE \"proc_old\"(int) RENAME TO \"proc_new\";"
    }

    // ── Trigger ─────────────────────────────────────────────────────

    test("trigger rename Up renders ALTER TRIGGER fromName ON tableName RENAME TO toName") {
        val trig = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "audit_fn()",
        )
        val fromKey = ObjectKeyCodec.triggerKey("orders", "audit_old")
        val toKey = ObjectKeyCodec.triggerKey("orders", "audit_new")
        val plan = planner.plan(
            current = emptySchema().copy(triggers = mapOf("audit_old" to trig)),
            desired = emptySchema().copy(triggers = mapOf("audit_new" to trig)),
            schemaDiff = SchemaDiff(
                triggersAdded = listOf(NamedTrigger("audit_new", trig)),
                triggersRemoved = listOf(NamedTrigger("audit_old", trig)),
            ),
            migrationOverlays = listOf(renameOverlay("trigger", fromKey, toKey)),
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.size shouldBe 1
        up.statements.single().sql shouldContain
            "ALTER TRIGGER \"audit_old\" ON \"orders\" RENAME TO \"audit_new\";"
        // Canonical key (orders::audit_new) must not leak into SQL.
        up.statements.single().sql.contains("orders::audit") shouldBe false
    }

    test("trigger rename Down renders the inverse") {
        val trig = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "audit_fn()",
        )
        val fromKey = ObjectKeyCodec.triggerKey("orders", "audit_old")
        val toKey = ObjectKeyCodec.triggerKey("orders", "audit_new")
        val plan = planner.plan(
            current = emptySchema().copy(triggers = mapOf("audit_old" to trig)),
            desired = emptySchema().copy(triggers = mapOf("audit_new" to trig)),
            schemaDiff = SchemaDiff(
                triggersAdded = listOf(NamedTrigger("audit_new", trig)),
                triggersRemoved = listOf(NamedTrigger("audit_old", trig)),
            ),
            migrationOverlays = listOf(renameOverlay("trigger", fromKey, toKey)),
        )
        val down = gen.generateDown(plan, DdlGenerationOptions())
        down.statements.size shouldBe 1
        down.statements.single().sql shouldContain
            "ALTER TRIGGER \"audit_new\" ON \"orders\" RENAME TO \"audit_old\";"
    }
})
