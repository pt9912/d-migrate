package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Routinen und Trigger im Diff-Pfad.
 *
 * Wie bei den Sichten macht `CREATE OR ALTER` den Ersetzungsfall billig: ein
 * Statement, kein Fenster, in dem die Routine fehlt. Beide Richtungen rendern
 * dieselbe Form mit dem jeweils anderen Rumpf.
 *
 * Was nicht renderbar ist, entscheidet **derselbe** Code wie im Generate-Pfad
 * ([MssqlRoutineDdl]) — fremder Dialekt, fehlender Rueckgabetyp, ein
 * Trigger-Zeitpunkt oder eine Trigger-Form, die T-SQL nicht hat, ein nicht
 * abbildbarer neutraler Typ. Zwei getrennte Urteile ueber dieselbe Frage waren
 * schon einmal der Fehler: der Diff-Pfad rendert sonst etwas anderes als der
 * Generate-Pfad, ohne dass es jemandem auffaellt.
 */
internal object MssqlDiffRoutineOps {

    fun renderCreateFunction(op: DiffOperation.CreateFunction, ctx: MssqlDiffRenderContext) =
        createOrDrop(op, ctx, "FUNCTION") { emitFunction(op, ctx, op.function) }

    fun renderDropFunction(op: DiffOperation.DropFunction, ctx: MssqlDiffRenderContext) =
        dropOrCreate(op, ctx, "FUNCTION") { emitFunction(op, ctx, op.function) }

    fun renderReplaceFunction(op: DiffOperation.ReplaceFunction, ctx: MssqlDiffRenderContext) =
        emitFunction(op, ctx, if (ctx.direction == MssqlRenderDirection.UP) op.after else op.before)

    fun renderCreateProcedure(op: DiffOperation.CreateProcedure, ctx: MssqlDiffRenderContext) =
        createOrDrop(op, ctx, "PROCEDURE") { emitProcedure(op, ctx, op.procedure) }

    fun renderDropProcedure(op: DiffOperation.DropProcedure, ctx: MssqlDiffRenderContext) =
        dropOrCreate(op, ctx, "PROCEDURE") { emitProcedure(op, ctx, op.procedure) }

    fun renderReplaceProcedure(op: DiffOperation.ReplaceProcedure, ctx: MssqlDiffRenderContext) =
        emitProcedure(op, ctx, if (ctx.direction == MssqlRenderDirection.UP) op.after else op.before)

    fun renderCreateTrigger(op: DiffOperation.CreateTrigger, ctx: MssqlDiffRenderContext) =
        createOrDrop(op, ctx, "TRIGGER") { emitTrigger(op, ctx, op.trigger) }

    fun renderDropTrigger(op: DiffOperation.DropTrigger, ctx: MssqlDiffRenderContext) =
        dropOrCreate(op, ctx, "TRIGGER") { emitTrigger(op, ctx, op.trigger) }

    fun renderReplaceTrigger(op: DiffOperation.ReplaceTrigger, ctx: MssqlDiffRenderContext) =
        emitTrigger(op, ctx, if (ctx.direction == MssqlRenderDirection.UP) op.after else op.before)

    fun renderRenameFunction(op: DiffOperation.RenameFunction, ctx: MssqlDiffRenderContext) =
        emitRename(op, ctx, "function", op.fromName, op.toName)

    fun renderRenameProcedure(op: DiffOperation.RenameProcedure, ctx: MssqlDiffRenderContext) =
        emitRename(op, ctx, "procedure", op.fromName, op.toName)

    fun renderRenameTrigger(op: DiffOperation.RenameTrigger, ctx: MssqlDiffRenderContext) =
        emitRename(op, ctx, "trigger", op.fromName, op.toName)

    // ── Richtung ─────────────────────────────────

    private inline fun createOrDrop(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        kind: String,
        emitCreate: () -> Unit,
    ) {
        if (ctx.direction == MssqlRenderDirection.DOWN) emitDrop(op, ctx, kind) else emitCreate()
    }

    private inline fun dropOrCreate(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        kind: String,
        emitCreate: () -> Unit,
    ) {
        if (ctx.direction == MssqlRenderDirection.DOWN) emitCreate() else emitDrop(op, ctx, kind)
    }

    private fun emitDrop(op: DiffOperation, ctx: MssqlDiffRenderContext, kind: String) {
        val name = nameOf(op, kind)
        ctx.emit(
            op,
            "DROP $kind ${ctx.sql.quote(name)};",
            MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS,
        )
    }

    // ── Emission ─────────────────────────────────

    private fun emitFunction(op: DiffOperation, ctx: MssqlDiffRenderContext, fn: FunctionDefinition) {
        val name = nameOf(op, "FUNCTION")
        val problem = collision(ctx, name, "function")
            ?: MssqlRoutineDdl.bodyProblem("function", name, fn.body, fn.sourceDialect)
            ?: MssqlRoutineDdl.unsupportedFunctionShape(name, fn)
        if (blocked(op, ctx, problem, fn.body)) return
        ctx.emit(
            op,
            MssqlRoutineDdl.functionSql(name, fn, checkNotNull(fn.body)) { ctx.sql.quote(it) },
            MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS,
        )
    }

    private fun emitProcedure(op: DiffOperation, ctx: MssqlDiffRenderContext, proc: ProcedureDefinition) {
        val name = nameOf(op, "PROCEDURE")
        val problem = collision(ctx, name, "procedure")
            ?: MssqlRoutineDdl.bodyProblem("procedure", name, proc.body, proc.sourceDialect)
            ?: MssqlRoutineDdl.unsupportedProcedureShape(name, proc)
        if (blocked(op, ctx, problem, proc.body)) return
        ctx.emit(
            op,
            MssqlRoutineDdl.procedureSql(name, proc, checkNotNull(proc.body)) { ctx.sql.quote(it) },
            MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS,
        )
    }

    private fun emitTrigger(op: DiffOperation, ctx: MssqlDiffRenderContext, trigger: TriggerDefinition) {
        val name = nameOf(op, "TRIGGER")
        val problem = collision(ctx, name, "trigger")
            ?: MssqlRoutineDdl.bodyProblem("trigger", name, trigger.body, trigger.sourceDialect)
            ?: MssqlRoutineDdl.unsupportedTriggerShape(name, trigger)
        if (blocked(op, ctx, problem, trigger.body)) return
        ctx.emit(
            op,
            MssqlRoutineDdl.triggerSql(name, trigger, checkNotNull(trigger.body)) { ctx.sql.quote(it) },
            MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS,
        )
    }

    /**
     * `sp_rename` benennt das Objekt um, laesst seinen **Rumpf** aber
     * unberuehrt: in `sys.sql_modules` steht danach weiterhin der alte Name.
     * Dieselbe Lage wie bei den Sichten — fuer SQL Server folgenlos, fuer einen
     * Reverse-Read nicht, denn der liest genau diesen Text.
     */
    private fun emitRename(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        kind: String,
        fromName: String,
        toName: String,
    ) {
        val (from, to) = if (ctx.direction == MssqlRenderDirection.UP) {
            fromName to toName
        } else {
            toName to fromName
        }
        ctx.emit(op, ctx.sql.renameSql(from, to, "OBJECT"), MssqlDiffRenderContext.MSSQL_RENAME_HINTS)
        ctx.addInfoDiagnostic(
            code = "MSSQL_RENAME_KEEPS_ROUTINE_BODY",
            operationId = op.id,
            message = "sp_rename renames $kind '$from' to '$to', but the stored definition in sys.sql_modules " +
                "keeps naming '$from'. SQL Server does not care; a reverse read of the body does.",
        )
    }

    // ── Urteile ──────────────────────────────────

    /**
     * Ein fehlender Rumpf blockt anders als eine Form, die T-SQL nicht kennt:
     * beim Rueckbau ist er `ROLLBACK_NOT_POSSIBLE` (die alte Fassung ist
     * schlicht unbekannt), sonst eine Handarbeit.
     */
    private fun blocked(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        problem: MssqlRoutineDdl.Unrenderable?,
        body: String?,
    ): Boolean {
        if (problem == null) return false
        val bodyUnknown = body.isNullOrBlank()
        val isDown = ctx.direction == MssqlRenderDirection.DOWN
        val code = when {
            !bodyUnknown -> "E053"
            isDown -> "ROUTINE_DOWN_BODY_UNKNOWN"
            else -> "ROUTINE_REPLACE_UP_BODY_UNKNOWN"
        }
        val reason = if (bodyUnknown && isDown) {
            MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        } else {
            MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        }
        ctx.skip(op, "${problem.reason} ${problem.hint}", code = code)
        ctx.addBlocker(reason, setOf(op.id))
        return true
    }

    /**
     * Zwei Objekte, die auf denselben T-SQL-Namen fallen — Ueberladungen oder
     * gleichnamige Trigger auf verschiedenen Tabellen. Der Generate-Pfad meldet
     * das; hier zaehlt dieselbe Frage gegen das Zielschema, denn ein zweites
     * `CREATE OR ALTER` ersetzte sonst still das erste.
     */
    private fun collision(
        ctx: MssqlDiffRenderContext,
        name: String,
        kind: String,
    ): MssqlRoutineDdl.Unrenderable? {
        val schema = ctx.schemaForDirection() ?: return null
        val keys = keysOf(schema, kind)
        return MssqlRoutineDdl.nameCollision(kind, name, MssqlRoutineDdl.collidingNames(keys) { key ->
            if (kind == "trigger") ObjectKeyCodec.triggerName(key) else ObjectKeyCodec.routineName(key)
        })
    }

    private fun keysOf(schema: SchemaDefinition, kind: String): Set<String> = when (kind) {
        "function" -> schema.functions.keys
        "procedure" -> schema.procedures.keys
        else -> schema.triggers.keys
    }

    /** Der emittierte Bezeichner: aus dem kanonischen Key, nicht der Key selbst. */
    private fun nameOf(op: DiffOperation, kind: String): String {
        val key = op.objectRef.rootName
        return if (kind == "TRIGGER") ObjectKeyCodec.triggerName(key) else ObjectKeyCodec.routineName(key)
    }
}
