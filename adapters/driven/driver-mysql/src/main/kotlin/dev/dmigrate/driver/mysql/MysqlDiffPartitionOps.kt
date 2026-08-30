package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.PartitionDelta
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Der Bestand an Kind-Partitionen aendert sich. MySQL kennt dafuer drei
 * Anweisungen, und welche gilt, haengt daran, was mit dem **Bereich**
 * geschieht:
 *
 * - Ein Bereich kommt hinter der letzten Grenze dazu: `ADD PARTITION`. MySQL
 *   verlangt aufsteigende `VALUES LESS THAN`-Grenzen, ein `ADD` in der Mitte
 *   waere kein gueltiger Satz.
 * - Ein bestehender Bereich wird neu geschnitten — aufgeteilt oder
 *   zusammengelegt: `REORGANIZE PARTITION … INTO (…)`. MySQL nimmt die Zeilen
 *   dabei mit; `DROP` und neu anlegen verloere sie.
 * - Ein Bereich faellt ganz weg: `DROP PARTITION`, mitsamt seinen Zeilen.
 *
 * `HASH` erreicht diesen Weg nicht: eine geaenderte Eimerzahl verteilt jede
 * Zeile neu und wird schon im Hexagon als nicht auflösbar eingestuft
 * (`PartitionChangeReason.HASH_BUCKETS_CHANGED`).
 */
internal object MysqlDiffPartitionOps {

    fun renderAlterTablePartitions(op: DiffOperation.AlterTablePartitions, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.rootName
        val down = ctx.direction == MysqlRenderDirection.DOWN
        val target = if (down) op.before else op.after
        val helper = MysqlIndexPartitionDdlHelper(ctx.sql::quote)
        val columns = ctx.columnsOf(table)

        if (blockUnrenderable(op, ctx, table, target, helper, columns)) return
        if (blockRename(op, ctx, table, down)) return

        val dropped = if (down) op.delta.addedOutright else op.delta.droppedOutright
        val appended = if (down) op.delta.droppedOutright else op.delta.addedOutright

        val notes = mutableListOf<TransformationNote>()
        // Ein Satz fuer den ganzen Aufruf: eine Meldung zur Wert-Normalisierung
        // gilt der Partitionierung, nicht jeder Partition einzeln.
        val emittedCodes = mutableSetOf<String>()
        val quoted = ctx.sql.quote(table)

        fun clause(partition: PartitionDefinition) =
            helper.renderSinglePartition(partition, target, columns, notes, emittedCodes)

        for (step in reorganisations(op.delta, down)) {
            val from = step.from.joinToString(", ") { ctx.sql.quote(it.name) }
            ctx.emit(
                op,
                "ALTER TABLE $quoted REORGANIZE PARTITION $from INTO " +
                    "(${step.into.joinToString(", ") { clause(it) }});",
            )
        }
        for (child in dropped) {
            ctx.emit(op, "ALTER TABLE $quoted DROP PARTITION ${ctx.sql.quote(child.name)};")
        }
        if (appended.isNotEmpty()) {
            ctx.emit(op, "ALTER TABLE $quoted ADD PARTITION (${appended.joinToString(", ") { clause(it) }});")
        }
        notes.forEach { note -> ctx.warning(op, note.message, code = note.code ?: "W112") }
    }

    /**
     * Die Neuschnitte in Ausfuehrungsform. Vorwaerts wird eine Aufteilung zu
     * „eine Partition wird zu mehreren" und eine Zusammenlegung zu „mehrere
     * werden zu einer"; im Rueckbau tauschen beide die Rollen.
     */
    private fun reorganisations(delta: PartitionDelta, down: Boolean): List<Reorganisation> =
        if (down) {
            delta.splits.map { Reorganisation(it.pieces, listOf(it.whole)) } +
                delta.merges.map { Reorganisation(listOf(it.whole), it.pieces) }
        } else {
            delta.splits.map { Reorganisation(listOf(it.whole), it.pieces) } +
                delta.merges.map { Reorganisation(it.pieces, listOf(it.whole)) }
        }

    private data class Reorganisation(
        val from: List<PartitionDefinition>,
        val into: List<PartitionDefinition>,
    )

    /**
     * Was der Generate-Pfad fuer MySQL nicht rendern wuerde, rendert der
     * Diff-Pfad auch nicht — sonst entstuende DDL, die der Server ablehnt: ein
     * `VALUES IN ()` aus einer LIST-DEFAULT-Partition oder eine Grenze auf
     * einem Schluesseltyp, den MySQL nicht partitioniert.
     */
    private fun blockUnrenderable(
        op: DiffOperation.AlterTablePartitions,
        ctx: MysqlDiffRenderContext,
        table: String,
        target: PartitionConfig,
        helper: MysqlIndexPartitionDdlHelper,
        columns: Map<String, ColumnDefinition>,
    ): Boolean {
        val note = helper.partitioningSkipNote(target, columns) ?: return false
        ctx.skip(
            op,
            "Table `$table`: the partitioning cannot be expressed in MySQL, so the change to its " +
                "partitions was not applied. ${note.message}",
            code = note.code ?: "E055",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
        return true
    }

    /**
     * Ein Kind behaelt seine Grenzen und wechselt den Namen. MySQL benennt
     * seine Partitionen, also ist das eine echte Aenderung — aber keine des
     * Bestands, und ohne Meldung verschwaende sie still in einem Lauf, der
     * nebenbei eine Partition hinzufuegt.
     */
    private fun blockRename(
        op: DiffOperation.AlterTablePartitions,
        ctx: MysqlDiffRenderContext,
        table: String,
        down: Boolean,
    ): Boolean {
        val renamed = op.delta.retained.filter { it.before.name != it.after.name }
        if (renamed.isEmpty()) return false
        val pairs = renamed.joinToString(", ") {
            if (down) "${it.after.name} → ${it.before.name}" else "${it.before.name} → ${it.after.name}"
        }
        ctx.skip(
            op,
            "Table `$table`: partition(s) $pairs keep their boundaries and change their name. MySQL " +
                "renames a partition only by reorganising it; rename it manually " +
                "(`ALTER TABLE … REORGANIZE PARTITION … INTO (…)`).",
            code = "PARTITION_RENAME_NOT_APPLIED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
        return true
    }
}
