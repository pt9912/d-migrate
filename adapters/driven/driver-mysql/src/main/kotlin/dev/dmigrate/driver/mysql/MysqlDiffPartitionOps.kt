package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.PartitionDelta
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
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
 * `HASH` bleibt aussen vor: dort aendert eine zusaetzliche Partition den
 * Modulus und verteilt jede Zeile neu — das ist kein Grenz-Delta.
 */
internal object MysqlDiffPartitionOps {

    fun renderAlterTablePartitions(op: DiffOperation.AlterTablePartitions, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.rootName
        val down = ctx.direction == MysqlRenderDirection.DOWN
        val target = if (down) op.before else op.after
        if (blockHash(op, ctx, table, target)) return

        val dropped = if (down) op.delta.addedOutright else op.delta.droppedOutright
        val appended = if (down) op.delta.droppedOutright else op.delta.addedOutright

        val notes = mutableListOf<TransformationNote>()
        val helper = MysqlIndexPartitionDdlHelper(ctx.sql::quote)
        val columns = ctx.columnsOf(table)
        val quoted = ctx.sql.quote(table)

        for (step in reorganisations(op.delta, down)) {
            val from = step.from.joinToString(", ") { ctx.sql.quote(it.name) }
            val into = step.into.joinToString(", ") { helper.renderSinglePartition(it, target, columns, notes) }
            ctx.emit(op, "ALTER TABLE $quoted REORGANIZE PARTITION $from INTO ($into);")
        }
        for (child in dropped) {
            ctx.emit(op, "ALTER TABLE $quoted DROP PARTITION ${ctx.sql.quote(child.name)};")
        }
        if (appended.isNotEmpty()) {
            val clauses = appended.joinToString(", ") { helper.renderSinglePartition(it, target, columns, notes) }
            ctx.emit(op, "ALTER TABLE $quoted ADD PARTITION ($clauses);")
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

    private fun blockHash(
        op: DiffOperation.AlterTablePartitions,
        ctx: MysqlDiffRenderContext,
        table: String,
        target: PartitionConfig,
    ): Boolean {
        if (target.type != PartitionType.HASH) return false
        ctx.skip(
            op,
            "Table `$table` is HASH-partitioned. Adding or removing a HASH partition changes the " +
                "modulus and redistributes every row; that is not a boundary change and is not " +
                "applied automatically.",
            code = "PARTITION_HASH_CHANGE_NOT_APPLIED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
        return true
    }
}
