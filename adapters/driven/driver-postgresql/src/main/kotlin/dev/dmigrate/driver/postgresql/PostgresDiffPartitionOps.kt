package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.RetainedPartition
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Der Bestand an Kind-Partitionen aendert sich. In PostgreSQL ist eine
 * Partition eine eigene Tabelle: sie entsteht mit
 * `CREATE TABLE … PARTITION OF …` und faellt mit `DROP TABLE` weg.
 *
 * Zwei Faelle rendert dieser Weg **nicht**, und beide erkennt er am Namen —
 * PostgreSQL benennt seine Partitionen, anders als SQL Server, das sie
 * nummeriert:
 *
 * - Ein Kind steht auf beiden Seiten mit demselben Namen, aber anderen
 *   Grenzen. Das ist keine neue Partition, sondern ein Zuschnitt: die Zeilen
 *   muessten mitwandern, und `DROP` + `CREATE` verloere sie.
 * - Ein Kind behaelt seine Grenzen und wechselt den Namen. Das ist ein
 *   Rename, keine Bestandsaenderung.
 */
internal object PostgresDiffPartitionOps {

    fun renderAlterTablePartitions(op: DiffOperation.AlterTablePartitions, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.rootName
        val down = ctx.direction == PostgresRenderDirection.DOWN
        val created = if (down) op.delta.droppedOutright else op.delta.addedOutright
        val dropped = if (down) op.delta.addedOutright else op.delta.droppedOutright
        val type = if (down) op.before.type else op.after.type

        if (blockReshape(op, ctx, table, created, dropped)) return
        if (blockSplitOrMerge(op, ctx, table)) return
        if (blockRename(op, ctx, table, op.delta.retained, down)) return

        // Erst wegnehmen, dann anlegen: zwei Kinder duerfen sich nicht
        // ueberlappen, und der Zielzuschnitt darf den alten beruehren.
        for (child in dropped) {
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(child.name)};")
        }
        for (child in created) {
            ctx.emit(op, PostgresPartitionClauses.childStatement(table, child, type, ctx.sql::quote))
        }
    }

    /**
     * Ein entfallenes Kind, dessen Bereich die hinzugekommenen wieder
     * abdecken (oder umgekehrt), ist eine Aufteilung oder Zusammenlegung.
     * SQL Server kann das mit `SPLIT`/`MERGE RANGE`, PostgreSQL nicht: dort
     * müsste die alte Kindtabelle weichen, und ihre Zeilen gingen mit.
     */
    private fun blockSplitOrMerge(
        op: DiffOperation.AlterTablePartitions,
        ctx: PostgresDiffRenderContext,
        table: String,
    ): Boolean {
        val affected = (op.delta.splits + op.delta.merges).map { it.whole.name }.sorted()
        if (affected.isEmpty()) return false
        ctx.skip(
            op,
            "Table '$table': partition(s) ${affected.joinToString(", ")} are split or merged — the new " +
                "boundaries re-cover the old range. PostgreSQL has no in-place split or merge; the rows " +
                "would have to move. Detach the partition, redistribute the rows, and attach the new ones " +
                "manually.",
            code = "PARTITION_SPLIT_OR_MERGE_NOT_APPLIED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
        return true
    }

    private fun blockReshape(
        op: DiffOperation.AlterTablePartitions,
        ctx: PostgresDiffRenderContext,
        table: String,
        created: List<PartitionDefinition>,
        dropped: List<PartitionDefinition>,
    ): Boolean {
        val createdNames = created.mapTo(mutableSetOf()) { it.name }
        val reshaped = dropped.filter { it.name in createdNames }.map { it.name }.sorted()
        if (reshaped.isEmpty()) return false
        ctx.skip(
            op,
            "Table '$table': partition(s) ${reshaped.joinToString(", ")} keep their name but change " +
                "their boundaries. PostgreSQL cannot re-cut a partition in place — the rows would have " +
                "to move — and dropping the partition to recreate it would lose them. Detach the " +
                "partition, move the rows, and attach the new one manually.",
            code = "PARTITION_BOUNDS_CHANGE_NOT_APPLIED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
        return true
    }

    private fun blockRename(
        op: DiffOperation.AlterTablePartitions,
        ctx: PostgresDiffRenderContext,
        table: String,
        retained: List<RetainedPartition>,
        down: Boolean,
    ): Boolean {
        val renamed = retained.filter { it.before.name != it.after.name }
        if (renamed.isEmpty()) return false
        // Im Rueckbau laeuft die Umbenennung andersherum; die Meldung nennt
        // sonst genau den Handgriff, der den Zustand weiter wegtruege.
        val pairs = renamed.joinToString(", ") {
            if (down) "${it.after.name} → ${it.before.name}" else "${it.before.name} → ${it.after.name}"
        }
        ctx.skip(
            op,
            "Table '$table': partition(s) $pairs keep their boundaries and change their name. " +
                "Renaming a partition is not part of a partition-set change; rename the child table " +
                "manually (`ALTER TABLE … RENAME TO …`).",
            code = "PARTITION_RENAME_NOT_APPLIED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
        return true
    }
}
