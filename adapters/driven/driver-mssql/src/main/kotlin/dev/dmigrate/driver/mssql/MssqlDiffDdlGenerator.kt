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
 * T-SQL-Renderer fuer die Migrations-Pipeline (Sub-Slice 5a des
 * MSSQL-Ausbaus, [ADR 0047]).
 *
 * **Im Umfang**: Tabellen, Spalten und Primaerschluessel (5a), der
 * IDENTITY-Tabellen-Neubau (5a-2, [MssqlRebuildPlanner]), Constraints und
 * Indizes (5b), Sichten (5c). Alles andere meldet der Dispatcher als
 * `DIALECT_UNSUPPORTED_OPERATION` — teils, weil ein spaeterer Sub-Slice es
 * liefert (Custom Types 5c, Sequenzen 5d), teils, weil ein Slice
 * die ganze Flaeche besitzt (Routinen und Trigger Slice 9, Partitionierung
 * Slice 7) oder SQL Server sie gar nicht kennt (Materialized Views).
 *
 * Solange dieser Renderer unvollstaendig ist, weist `DialectCommandGate`
 * `schema migrate` fuer mssql weiterhin an der Kommando-Grenze ab; das Gate
 * faellt erst mit Sub-Slice 5e. Der Renderer ist bis dahin ueber seine Tests
 * erreichbar, nicht ueber die CLI.
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
            is DiffOperation.AddIndex -> MssqlDiffObjectOps.renderAddIndex(op, ctx)
            is DiffOperation.DropIndex -> MssqlDiffObjectOps.renderDropIndex(op, ctx)
            is DiffOperation.AddConstraint -> MssqlDiffObjectOps.renderAddConstraint(op, ctx)
            is DiffOperation.DropConstraint -> MssqlDiffObjectOps.renderDropConstraint(op, ctx)
            is DiffOperation.CreateView -> MssqlDiffViewOps.renderCreateView(op, ctx)
            is DiffOperation.ReplaceView -> MssqlDiffViewOps.renderReplaceView(op, ctx)
            is DiffOperation.DropView -> MssqlDiffViewOps.renderDropView(op, ctx)
            is DiffOperation.RenameView -> MssqlDiffViewOps.renderRenameView(op, ctx)
            else -> blockUnsupported(op, ctx)
        }
    }

    /**
     * Der Port verlangt fuer eine nicht gerenderte Operation einen
     * `DIALECT_UNSUPPORTED_OPERATION`-Blocker statt einer Exception. Die
     * Meldung nennt den Grund und, wo es einen gibt, den Sub-Slice oder Slice,
     * der die Flaeche besitzt — damit ein Operator nicht raten muss, ob er auf
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
        is DiffOperation.CreateCustomType, is DiffOperation.AlterCustomType,
        is DiffOperation.DropCustomType,
        -> "custom types arrive with sub-slice 5c"

        is DiffOperation.CreateSequence, is DiffOperation.AlterSequence,
        is DiffOperation.DropSequence, is DiffOperation.RenameSequence,
        is DiffOperation.AlterSequenceCurrentValue,
        -> "sequences arrive with sub-slice 5d"

        is DiffOperation.CreateFunction, is DiffOperation.ReplaceFunction,
        is DiffOperation.DropFunction, is DiffOperation.RenameFunction,
        is DiffOperation.CreateProcedure, is DiffOperation.ReplaceProcedure,
        is DiffOperation.DropProcedure, is DiffOperation.RenameProcedure,
        is DiffOperation.CreateTrigger, is DiffOperation.ReplaceTrigger,
        is DiffOperation.DropTrigger, is DiffOperation.RenameTrigger,
        -> "routines and triggers wait for slice 9 — the MSSQL reverse does not read their bodies yet " +
            "(R342), so there is nothing to render; PostgreSQL renders them because its reverse does"

        is DiffOperation.CreateMaterializedView, is DiffOperation.ReplaceMaterializedView,
        is DiffOperation.DropMaterializedView,
        -> "SQL Server has no materialized views; the generate path degrades them to a plain view (W103)"

        else -> "no MSSQL rendering exists for this operation"
    }
}
