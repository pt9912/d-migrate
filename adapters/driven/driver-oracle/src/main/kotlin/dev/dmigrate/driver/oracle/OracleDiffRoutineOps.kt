package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.TriggerDefinition

/**
 * Routinen und Trigger im Diff-Pfad.
 *
 * Wie bei den Sichten macht `CREATE OR REPLACE` den Ersetzungsfall billig: ein
 * Statement, kein Fenster, in dem die Routine fehlt. Beide Richtungen rendern
 * dieselbe Form mit dem jeweils anderen Rumpf.
 *
 * Was nicht renderbar ist, entscheidet **derselbe** Code wie im Generate-Pfad
 * ([OracleRoutineShape]). Zwei getrennte Urteile ueber dieselbe Frage waeren
 * der Fehler: der Diff-Pfad rendert sonst etwas anderes als der Generate-Pfad,
 * ohne dass es auffaellt.
 *
 * Das emittierte SQL traegt **kein** abschliessendes `;` — anders als die
 * uebrigen Oracle-Anweisungen. Ein PL/SQL-Block endet auf `END;`; ein weiteres
 * Semikolon laesst `execute()` zwar gelingen, hinterlaesst die Routine aber
 * `INVALID`.
 */
internal object OracleDiffRoutineOps {

    fun renderCreateFunction(op: DiffOperation.CreateFunction, ctx: OracleDiffRenderContext) =
        createOrDrop(op, ctx, "FUNCTION") { emitFunction(op, ctx, op.function) }

    fun renderDropFunction(op: DiffOperation.DropFunction, ctx: OracleDiffRenderContext) =
        dropOrCreate(op, ctx, "FUNCTION") { emitFunction(op, ctx, op.function) }

    fun renderReplaceFunction(op: DiffOperation.ReplaceFunction, ctx: OracleDiffRenderContext) =
        emitFunction(op, ctx, if (ctx.direction == OracleRenderDirection.UP) op.after else op.before)

    fun renderCreateProcedure(op: DiffOperation.CreateProcedure, ctx: OracleDiffRenderContext) =
        createOrDrop(op, ctx, "PROCEDURE") { emitProcedure(op, ctx, op.procedure) }

    fun renderDropProcedure(op: DiffOperation.DropProcedure, ctx: OracleDiffRenderContext) =
        dropOrCreate(op, ctx, "PROCEDURE") { emitProcedure(op, ctx, op.procedure) }

    fun renderReplaceProcedure(op: DiffOperation.ReplaceProcedure, ctx: OracleDiffRenderContext) =
        emitProcedure(op, ctx, if (ctx.direction == OracleRenderDirection.UP) op.after else op.before)

    fun renderCreateTrigger(op: DiffOperation.CreateTrigger, ctx: OracleDiffRenderContext) =
        createOrDrop(op, ctx, "TRIGGER") { emitTrigger(op, ctx, op.trigger) }

    fun renderDropTrigger(op: DiffOperation.DropTrigger, ctx: OracleDiffRenderContext) =
        dropOrCreate(op, ctx, "TRIGGER") { emitTrigger(op, ctx, op.trigger) }

    fun renderReplaceTrigger(op: DiffOperation.ReplaceTrigger, ctx: OracleDiffRenderContext) =
        emitTrigger(op, ctx, if (ctx.direction == OracleRenderDirection.UP) op.after else op.before)

    /**
     * Oracle kennt kein Umbenennen freistehender Routinen — weder
     * `RENAME f TO g` (ORA-03001) noch `ALTER FUNCTION f RENAME TO g`
     * (ORA-00922). Ein Drop-und-Create waere kein Rename, sondern ein anderes
     * Objekt: abhaengige Objekte wuerden invalidiert und Rechte fielen weg.
     */
    fun renderRenameFunction(op: DiffOperation.RenameFunction, ctx: OracleDiffRenderContext) =
        OracleDiffRoutineGuards.blockRename(op, ctx, "function", op.fromName, op.toName)

    fun renderRenameProcedure(op: DiffOperation.RenameProcedure, ctx: OracleDiffRenderContext) =
        OracleDiffRoutineGuards.blockRename(op, ctx, "procedure", op.fromName, op.toName)

    /** Der einzige native Rename der drei: `ALTER TRIGGER … RENAME TO`. */
    fun renderRenameTrigger(op: DiffOperation.RenameTrigger, ctx: OracleDiffRenderContext) {
        val (from, to) = OracleDiffRoutineGuards.directedNames(ctx, op.fromName, op.toName)
        val fromName = ctx.sql.quote(from)
        val toName = ctx.sql.quote(to)
        ctx.emit(op, "ALTER TRIGGER $fromName RENAME TO $toName;", OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
    }

    // ── Richtung ─────────────────────────────────

    private inline fun createOrDrop(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        kind: String,
        emitCreate: () -> Unit,
    ) {
        if (ctx.direction == OracleRenderDirection.DOWN) emitDrop(op, ctx, kind) else emitCreate()
    }

    private inline fun dropOrCreate(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        kind: String,
        emitCreate: () -> Unit,
    ) {
        if (ctx.direction == OracleRenderDirection.DOWN) emitCreate() else emitDrop(op, ctx, kind)
    }

    private fun emitDrop(op: DiffOperation, ctx: OracleDiffRenderContext, kind: String) {
        ctx.emit(
            op,
            "DROP $kind ${ctx.sql.quote(OracleDiffRoutineGuards.nameOf(op, kind))};",
            OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS,
        )
    }

    // ── Emission ─────────────────────────────────

    private fun emitFunction(op: DiffOperation, ctx: OracleDiffRenderContext, fn: FunctionDefinition) {
        val name = OracleDiffRoutineGuards.nameOf(op, "FUNCTION")
        val problem = OracleDiffRoutineGuards.collision(ctx, name, "function")
            ?: OracleRoutineShape.bodyProblem("function", name, fn.body, fn.sourceDialect)
            ?: OracleRoutineShape.unsupportedFunctionShape(name, fn)
        if (OracleDiffRoutineGuards.blocked(op, ctx, problem, fn.body)) return
        ctx.emit(
            op,
            OracleRoutineDdl.functionSql(name, fn, checkNotNull(fn.body)) { ctx.sql.quote(it) },
            OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS,
        )
    }

    private fun emitProcedure(op: DiffOperation, ctx: OracleDiffRenderContext, proc: ProcedureDefinition) {
        val name = OracleDiffRoutineGuards.nameOf(op, "PROCEDURE")
        val problem = OracleDiffRoutineGuards.collision(ctx, name, "procedure")
            ?: OracleRoutineShape.bodyProblem("procedure", name, proc.body, proc.sourceDialect)
            ?: OracleRoutineShape.unsupportedProcedureShape(name, proc)
        if (OracleDiffRoutineGuards.blocked(op, ctx, problem, proc.body)) return
        ctx.emit(
            op,
            OracleRoutineDdl.procedureSql(name, proc, checkNotNull(proc.body)) { ctx.sql.quote(it) },
            OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS,
        )
    }

    private fun emitTrigger(op: DiffOperation, ctx: OracleDiffRenderContext, trigger: TriggerDefinition) {
        val name = OracleDiffRoutineGuards.nameOf(op, "TRIGGER")
        val problem = OracleDiffRoutineGuards.collision(ctx, name, "trigger")
            ?: OracleRoutineShape.bodyProblem("trigger", name, trigger.body, trigger.sourceDialect)
            ?: OracleRoutineShape.unsupportedTriggerShape(name, trigger, ctx.schemaForDirection()?.tables)
        if (OracleDiffRoutineGuards.blocked(op, ctx, problem, trigger.body)) return
        ctx.emit(
            op,
            OracleRoutineDdl.triggerSql(name, trigger, checkNotNull(trigger.body)) { ctx.sql.quote(it) },
            OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS,
        )
    }
}
