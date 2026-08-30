package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * T-SQL-Renderer fuer die Migrations-Pipeline ([ADR 0047]).
 *
 * **Im Umfang**: Tabellen, Spalten und Primaerschluessel samt dem
 * IDENTITY-Tabellen-Neubau ([MssqlRebuildPlanner]), Constraints und Indizes,
 * Sichten, Custom Types und Sequenzen.
 *
 * Ebenso Routinen und Trigger: `CREATE OR ALTER` macht den Ersetzungsfall zu
 * einem Statement, wie schon bei den Sichten.
 *
 * Was er nicht rendert, meldet der Dispatcher als
 * `DIALECT_UNSUPPORTED_OPERATION` mit dem Grund: Partitionierung, weil das
 * neutrale Modell weder Partitionsfunktion noch -schema traegt; Materialized
 * Views, weil SQL Server sie nicht kennt.
 *
 * Zustandslos und thread-sicher. `generateUp` konsumiert die topo-sortierten
 * Operationen des Planners unveraendert, `generateDown` laeuft dieselbe Liste
 * rueckwaerts und kehrt die Semantik um.
 */
class MssqlDiffDdlGenerator : DiffDdlGenerator {

    override val dialect: DatabaseDialect = DatabaseDialect.MSSQL

    private val sql = MssqlDiffSqlBuilders(MssqlTypeMapper())

    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, MssqlRenderDirection.UP)

    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, MssqlRenderDirection.DOWN)

    private fun render(
        diff: DiffResult,
        options: DdlGenerationOptions,
        direction: MssqlRenderDirection,
    ): MigrationDdlResult {
        val ctx = MssqlDiffRenderContext(
            direction = direction,
            sql = sql,
            options = options,
            currentSchema = diff.currentSchema,
            desiredSchema = diff.desiredSchema,
        )
        val ops = if (direction == MssqlRenderDirection.UP) diff.operations else diff.operations.reversed()
        renderAll(ops, ctx, diff)
        return ctx.toResult(diff)
    }

    /**
     * Operationen der Reihe nach — bis auf die, die ein Tabellen-Neubau
     * uebernimmt ([MssqlRebuildPlanner]). Deren Eimer laeuft als eine Sequenz,
     * und zwar an der Stelle seiner **letzten** Operation: damit ist alles
     * erledigt, was der Planner vor irgendein Eimer-Mitglied sortiert hat —
     * etwa die Tabelle, auf die ein neuer Fremdschluessel der neu gebauten
     * Tabelle zeigt.
     *
     * Die Buchhaltung laeuft auch ohne Neubau: der Spaltentanz stellt
     * dieselbe Frage — steht dieser Fremdschluessel schon? — und bekaeme sonst
     * eine leere Antwort.
     */
    private fun renderAll(ops: List<DiffOperation>, ctx: MssqlDiffRenderContext, diff: DiffResult) {
        val classification = MssqlRebuildPlanner.classify(ops, diff.currentSchema, diff.desiredSchema)
        val absorbedBy = classification.rebuilds
            .flatMap { rebuild -> rebuild.ops.map { it.id to rebuild } }
            .toMap()
        val lastOfBucket = classification.rebuilds.associate { it.table to it.ops.last().id }
        // Der Kontext fuehrt Buch, was schon geschrieben wurde: ob ein
        // eingehender Fremdschluessel bei einem spaeteren Neubau dasteht,
        // entscheidet nicht die Phasenordnung, sondern ob seine Operation
        // schon lief. Ein GEBLOCKTER Schritt zaehlt dabei nicht — er hat
        // nichts geschrieben. Ein Eimer zaehlt mit allem, was er absorbiert
        // hat: sein Neubau legt die Objekte seiner Tabelle mit an.
        for (op in ops) {
            val rebuild = absorbedBy[op.id]
            if (rebuild == null) {
                renderOp(op, ctx)
                if (!ctx.isSkipped(op)) ctx.noteRendered(op)
            } else if (op.id == lastOfBucket[rebuild.table]) {
                renderRebuild(rebuild, ctx)
                val done = rebuild.ops.filterNot { ctx.isSkipped(it) }
                done.forEach { ctx.noteRendered(it) }
                if (done.isNotEmpty()) ctx.noteRebuilt(rebuild.table)
            }
        }
    }

    /**
     * Abwaerts gilt fuer den Eimer, was fuer jede einzelne Operation gilt: ist
     * eine davon nicht umkehrbar, gibt es keinen Rueckweg. Der Neubau darf dann
     * gar nicht erst laufen — er wuerde die Tabelle in einen Zustand bringen,
     * aus dem die Operation nicht zurueckfuehrt.
     */
    private fun renderRebuild(rebuild: MssqlRebuildPlanner.Rebuild, ctx: MssqlDiffRenderContext) {
        val (table, _, bucket) = rebuild
        val irreversible = bucket.filter { it.reversibility == Reversibility.NOT_REVERSIBLE }
        if (ctx.direction == MssqlRenderDirection.DOWN && irreversible.isNotEmpty()) {
            val ids = irreversible.joinToString(", ") { it.id }
            bucket.forEach {
                ctx.skip(
                    it,
                    "Rebuilding table '$table' would have to undo operation(s) $ids, which are NOT_REVERSIBLE; " +
                        "the renderer cannot reconstruct an inverse for the rebuild as a whole.",
                    code = "ROLLBACK_NOT_POSSIBLE",
                )
            }
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, bucket.map { it.id }.toSet())
            return
        }
        MssqlRebuildRenderer.render(rebuild, ctx)
    }

    private fun renderOp(op: DiffOperation, ctx: MssqlDiffRenderContext) {
        if (ctx.direction == MssqlRenderDirection.DOWN && op.reversibility == Reversibility.NOT_REVERSIBLE) {
            ctx.skip(
                op,
                "Operation ${op.id} is NOT_REVERSIBLE; the renderer cannot reconstruct an inverse.",
                code = "ROLLBACK_NOT_POSSIBLE",
            )
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, setOf(op.id))
            return
        }
        // Ohne Down-Risikoprofil hat der Planner fuer diese Richtung keine
        // Umkehr definiert. `emit` wuerde daran mit einer Exception scheitern
        // statt einen Blocker zu liefern — was der Port verlangt.
        if (ctx.direction == MssqlRenderDirection.DOWN && op.risks.down == null) {
            ctx.skip(
                op,
                "Operation ${op.id} carries no risk profile for the Down direction; the planner defines no " +
                    "inverse for it, so the renderer cannot construct one either.",
                code = "ROLLBACK_NOT_POSSIBLE",
            )
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, setOf(op.id))
            return
        }
        val families = listOf(
            ::renderTableOp, ::renderObjectOp, ::renderViewOp, ::renderCustomTypeOp, ::renderSequenceOp,
            ::renderRoutineOp,
        )
        if (families.none { it(op, ctx) }) blockUnsupported(op, ctx)
    }

    /**
     * Der Dispatch ist nach denselben Familien geteilt, nach denen der
     * Renderer selbst geschnitten ist — Tabellen und Spalten, Constraints und
     * Indizes, Sichten, Custom Types. Jede Familie meldet, ob sie die
     * Operation genommen hat; was keine nimmt, blockt der Aufrufer.
     */
    private fun renderTableOp(op: DiffOperation, ctx: MssqlDiffRenderContext): Boolean {
        when (op) {
            is DiffOperation.CreateTable -> MssqlDiffTableOps.renderCreateTable(op, ctx)
            is DiffOperation.DropTable -> MssqlDiffTableOps.renderDropTable(op, ctx)
            is DiffOperation.RenameTable -> MssqlDiffTableOps.renderRenameTable(op, ctx)
            is DiffOperation.AddColumn -> MssqlDiffTableOps.renderAddColumn(op, ctx)
            is DiffOperation.DropColumn -> MssqlDiffTableOps.renderDropColumn(op, ctx)
            is DiffOperation.RenameColumn -> MssqlDiffTableOps.renderRenameColumn(op, ctx)
            is DiffOperation.AlterColumnType -> MssqlDiffTableOps.renderAlterColumnType(op, ctx)
            is DiffOperation.AlterColumnNullability -> MssqlDiffTableOps.renderAlterColumnNullability(op, ctx)
            is DiffOperation.AlterColumnDefault -> MssqlDiffTableOps.renderAlterColumnDefault(op, ctx)
            is DiffOperation.AddPrimaryKey -> MssqlDiffTableOps.renderAddPrimaryKey(op, ctx)
            is DiffOperation.DropPrimaryKey -> MssqlDiffTableOps.renderDropPrimaryKey(op, ctx)
            is DiffOperation.AlterTablePartitions -> MssqlDiffPartitionOps.renderAlterTablePartitions(op, ctx)
            else -> return false
        }
        return true
    }

    private fun renderObjectOp(op: DiffOperation, ctx: MssqlDiffRenderContext): Boolean {
        when (op) {
            is DiffOperation.AddIndex -> MssqlDiffObjectOps.renderAddIndex(op, ctx)
            is DiffOperation.DropIndex -> MssqlDiffObjectOps.renderDropIndex(op, ctx)
            is DiffOperation.AddConstraint -> MssqlDiffObjectOps.renderAddConstraint(op, ctx)
            is DiffOperation.DropConstraint -> MssqlDiffObjectOps.renderDropConstraint(op, ctx)
            else -> return false
        }
        return true
    }

    private fun renderViewOp(op: DiffOperation, ctx: MssqlDiffRenderContext): Boolean {
        when (op) {
            is DiffOperation.CreateView -> MssqlDiffViewOps.renderCreateView(op, ctx)
            is DiffOperation.ReplaceView -> MssqlDiffViewOps.renderReplaceView(op, ctx)
            is DiffOperation.DropView -> MssqlDiffViewOps.renderDropView(op, ctx)
            is DiffOperation.RenameView -> MssqlDiffViewOps.renderRenameView(op, ctx)
            else -> return false
        }
        return true
    }

    private fun renderSequenceOp(op: DiffOperation, ctx: MssqlDiffRenderContext): Boolean {
        when (op) {
            is DiffOperation.CreateSequence -> MssqlDiffSequenceOps.renderCreateSequence(op, ctx)
            is DiffOperation.AlterSequence -> MssqlDiffSequenceOps.renderAlterSequence(op, ctx)
            is DiffOperation.DropSequence -> MssqlDiffSequenceOps.renderDropSequence(op, ctx)
            is DiffOperation.RenameSequence -> MssqlDiffSequenceOps.renderRenameSequence(op, ctx)
            is DiffOperation.AlterSequenceCurrentValue ->
                MssqlDiffSequenceOps.renderAlterSequenceCurrentValue(op, ctx)
            else -> return false
        }
        return true
    }

    private fun renderRoutineOp(op: DiffOperation, ctx: MssqlDiffRenderContext): Boolean {
        when (op) {
            is DiffOperation.CreateFunction -> MssqlDiffRoutineOps.renderCreateFunction(op, ctx)
            is DiffOperation.ReplaceFunction -> MssqlDiffRoutineOps.renderReplaceFunction(op, ctx)
            is DiffOperation.DropFunction -> MssqlDiffRoutineOps.renderDropFunction(op, ctx)
            is DiffOperation.RenameFunction -> MssqlDiffRoutineOps.renderRenameFunction(op, ctx)
            is DiffOperation.CreateProcedure -> MssqlDiffRoutineOps.renderCreateProcedure(op, ctx)
            is DiffOperation.ReplaceProcedure -> MssqlDiffRoutineOps.renderReplaceProcedure(op, ctx)
            is DiffOperation.DropProcedure -> MssqlDiffRoutineOps.renderDropProcedure(op, ctx)
            is DiffOperation.RenameProcedure -> MssqlDiffRoutineOps.renderRenameProcedure(op, ctx)
            is DiffOperation.CreateTrigger -> MssqlDiffRoutineOps.renderCreateTrigger(op, ctx)
            is DiffOperation.ReplaceTrigger -> MssqlDiffRoutineOps.renderReplaceTrigger(op, ctx)
            is DiffOperation.DropTrigger -> MssqlDiffRoutineOps.renderDropTrigger(op, ctx)
            is DiffOperation.RenameTrigger -> MssqlDiffRoutineOps.renderRenameTrigger(op, ctx)
            else -> return false
        }
        return true
    }

    private fun renderCustomTypeOp(op: DiffOperation, ctx: MssqlDiffRenderContext): Boolean {
        when (op) {
            is DiffOperation.CreateCustomType -> MssqlDiffCustomTypeOps.renderCreateCustomType(op, ctx)
            is DiffOperation.AlterCustomType -> MssqlDiffCustomTypeOps.renderAlterCustomType(op, ctx)
            is DiffOperation.DropCustomType -> MssqlDiffCustomTypeOps.renderDropCustomType(op, ctx)
            else -> return false
        }
        return true
    }

    /**
     * Der Port verlangt fuer eine nicht gerenderte Operation einen
     * `DIALECT_UNSUPPORTED_OPERATION`-Blocker statt einer Exception. Die
     * Meldung nennt den Grund — damit ein Operator nicht raten muss, ob er auf
     * etwas wartet oder etwas falsch gemacht hat.
     */
    private fun blockUnsupported(op: DiffOperation, ctx: MssqlDiffRenderContext) {
        ctx.skip(
            op,
            "Operation ${op.id} (${op::class.simpleName}) is not rendered by the MSSQL migrate path: " +
                ownerOf(op) + ".",
            code = "DIALECT_UNSUPPORTED_OPERATION",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
    }

    private fun ownerOf(op: DiffOperation): String = when (op) {
        is DiffOperation.CreateMaterializedView, is DiffOperation.ReplaceMaterializedView,
        is DiffOperation.DropMaterializedView,
        -> "SQL Server has no materialized views; the generate path degrades them to a plain view (W103)"

        else -> "no MSSQL rendering exists for this operation"
    }
}
