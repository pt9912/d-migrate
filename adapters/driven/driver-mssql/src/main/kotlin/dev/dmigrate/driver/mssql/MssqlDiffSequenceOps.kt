package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.SequenceDefinition

/**
 * Sequenzen im Diff-Pfad.
 *
 * SQL Server hat native Sequenzen — anders als MySQL und SQLite, die sie ueber
 * eine Hilfstabelle emulieren. Der Diff-Pfad ist damit nahe an PostgreSQL, mit
 * zwei Unterschieden, die beide aus den Grenzen von T-SQLs `ALTER SEQUENCE`
 * folgen:
 *
 * - **Der Startwert ist unveraenderlich.** `ALTER SEQUENCE` kennt kein
 *   `START WITH`. Aendert das Schema ihn, laesst sich das nicht nachziehen —
 *   der Renderer sagt es (`MSSQL_SEQUENCE_START_IMMUTABLE`), statt die
 *   Abweichung stillschweigend stehen zu lassen.
 * - **Umbenannt wird ueber `sp_rename`**, nicht ueber `ALTER SEQUENCE … RENAME
 *   TO`.
 */
internal object MssqlDiffSequenceOps {

    fun renderCreateSequence(op: DiffOperation.CreateSequence, ctx: MssqlDiffRenderContext) {
        val name = op.objectRef.rootName
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, MssqlSequenceDdl.dropSql(name), MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS)
            return
        }
        ctx.emit(op, MssqlSequenceDdl.createSql(name, op.sequence), MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS)
    }

    fun renderDropSequence(op: DiffOperation.DropSequence, ctx: MssqlDiffRenderContext) {
        val name = op.objectRef.rootName
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, MssqlSequenceDdl.createSql(name, op.sequence), MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS)
            return
        }
        ctx.emit(op, MssqlSequenceDdl.dropSql(name), MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS)
    }

    fun renderAlterSequence(op: DiffOperation.AlterSequence, ctx: MssqlDiffRenderContext) {
        val name = op.objectRef.rootName
        val (from, target) = if (ctx.direction == MssqlRenderDirection.UP) {
            op.before to op.after
        } else {
            op.after to op.before
        }
        ctx.emit(op, MssqlSequenceDdl.alterSql(name, target), MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS)
        warnImmutableStart(op, ctx, name, from, target)
    }

    /**
     * `RESTART WITH` ist die einzige Form, in der T-SQL den Laufzeitzustand
     * einer Sequenz setzt.
     *
     * Die Umrechnung ist nicht offensichtlich und **live gemessen**: SQL Server
     * fuehrt in `sys.sequences.current_value` den zuletzt ausgegebenen Wert —
     * bei einer nie benutzten Sequenz aber den Startwert, und der erste
     * `NEXT VALUE FOR` gibt genau diesen zurueck, ohne `current_value` zu
     * bewegen. „Nie benutzt" und „einmal benutzt" sind damit nicht
     * unterscheidbar. `RESTART WITH x` wiederum setzt den **naechsten**
     * auszugebenden Wert auf `x`.
     *
     * Die Probe liefert deshalb den zuletzt ausgegebenen Wert, und
     * fortgesetzt wird bei `Wert + Schrittweite`. Bei einer nie benutzten
     * Sequenz ueberspringt das genau einen Wert — die sichere Richtung: ein
     * uebersprungener Schluessel kostet nichts, ein doppelt vergebener eine
     * Verletzung der Eindeutigkeit.
     */
    fun renderAlterSequenceCurrentValue(op: DiffOperation.AlterSequenceCurrentValue, ctx: MssqlDiffRenderContext) {
        val ref = if (ctx.direction == MssqlRenderDirection.UP) op.applySequenceRef else op.probeSequenceRef
        val value = if (ctx.direction == MssqlRenderDirection.UP) op.currentValue else op.restoreValue
        if (value == null) {
            ctx.skip(
                op,
                "Restoring the current value of sequence '${ref.name}' needs a restore value, and the " +
                    "operation carries none for this direction.",
                code = "ROLLBACK_NOT_POSSIBLE",
            )
            ctx.addBlocker(
                dev.dmigrate.driver.migration.MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE,
                setOf(op.id),
            )
            return
        }
        // Die Schrittweite, nicht 1: eine Sequenz mit `INCREMENT BY 5` laege
        // sonst neben ihrem Raster, und eine ABSTEIGENDE (`INCREMENT BY -1`)
        // liefe rueckwaerts — sie gaebe bereits vergebene Werte ein zweites
        // Mal aus. Ohne die Sequenz-Definition laesst sich der
        // Fortsetzungspunkt nicht sicher bestimmen, also wird geblockt statt
        // geraten; die Schranken braucht es dafuer genauso.
        val sequence = ctx.schemaForDirection()?.sequences?.get(ref.name)
        if (sequence == null) {
            ctx.skip(
                op,
                "Resuming sequence '${ref.name}' needs its definition to compute the next value, but the " +
                    "sequence is not in the schema for this direction. RESTART WITH sets the next value, and " +
                    "guessing a step of 1 would put a wider sequence off its stride and a descending one " +
                    "backwards onto values it already issued.",
                code = "MSSQL_COLUMN_NOT_IN_SCHEMA",
            )
            ctx.addBlocker(
                dev.dmigrate.driver.migration.MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                setOf(op.id),
            )
            return
        }
        val next = resumePoint(value, sequence)
        if (next == null) {
            ctx.skip(
                op,
                "Sequence '${ref.name}' is exhausted at $value and does not cycle, so there is no value to " +
                    "resume at: SQL Server rejects a RESTART WITH outside the sequence's minimum/maximum. " +
                    "Widen the bounds or enable CYCLE if the sequence must continue.",
                code = "MSSQL_SEQUENCE_EXHAUSTED",
            )
            ctx.addBlocker(
                dev.dmigrate.driver.migration.MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                setOf(op.id),
            )
            return
        }
        ctx.emit(op, MssqlSequenceDdl.restartSql(ref.name, next), MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS)
        // Live gemessen: `RESTART WITH` schreibt auch `sys.sequences.start_value`
        // um. Ein Reverse nach der Migration meldet den fortgesetzten Wert als
        // Startwert — das ist eine Abweichung zum authored Schema, die niemand
        // an der Migration selbst erkennen koennte.
        ctx.addInfoDiagnostic(
            code = "MSSQL_RESTART_REWRITES_START",
            operationId = op.id,
            message = "ALTER SEQUENCE '${ref.name}' RESTART WITH also rewrites start_value in sys.sequences. " +
                "A later reverse read reports the resumed value as the sequence's start, which differs from " +
                "the authored schema.",
        )
    }

    /**
     * Der Wert, mit dem fortgesetzt wird — oder `null`, wenn es keinen gibt.
     *
     * `RESTART WITH` muss **innerhalb** von Minimum und Maximum liegen; SQL
     * Server lehnt alles andere ab („The start value for sequence object must
     * be between the minimum and maximum value", live belegt). Am Rand einer
     * zyklischen Sequenz ist der naechste Wert deshalb nicht Wert+Schrittweite,
     * sondern die gegenueberliegende Schranke — den Umbruch macht `RESTART
     * WITH` naemlich NICHT von selbst. Eine nicht-zyklische Sequenz am Rand ist
     * erschoepft: dann gibt es keinen gueltigen Fortsetzungspunkt.
     */
    private fun resumePoint(value: Long, seq: SequenceDefinition): Long? {
        val min = MssqlSequenceDdl.boundedMinValue(seq) ?: Long.MIN_VALUE
        val max = MssqlSequenceDdl.boundedMaxValue(seq) ?: Long.MAX_VALUE
        val next = runCatching { Math.addExact(value, seq.increment) }.getOrNull()
        if (next != null && next in min..max) return next
        return if (seq.cycle) {
            if (seq.increment > 0) min else max
        } else {
            null
        }
    }

    /** T-SQL hat kein `ALTER SEQUENCE … RENAME TO`. */
    fun renderRenameSequence(op: DiffOperation.RenameSequence, ctx: MssqlDiffRenderContext) {
        val (from, to) = if (ctx.direction == MssqlRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(op, ctx.sql.renameSql(from, to), MssqlDiffRenderContext.MSSQL_RENAME_HINTS)
    }

    private fun warnImmutableStart(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        name: String,
        from: SequenceDefinition,
        target: SequenceDefinition,
    ) {
        if (from.start == target.start) return
        ctx.warning(
            op,
            "Sequence '$name' changes its start from ${from.start} to ${target.start}, but T-SQL's " +
                "ALTER SEQUENCE cannot change it — only CREATE SEQUENCE can, and RESTART WITH sets the next " +
                "value rather than the declared start. The other attributes were applied; re-create the " +
                "sequence if the start itself must change.",
            code = "MSSQL_SEQUENCE_START_IMMUTABLE",
        )
    }
}
