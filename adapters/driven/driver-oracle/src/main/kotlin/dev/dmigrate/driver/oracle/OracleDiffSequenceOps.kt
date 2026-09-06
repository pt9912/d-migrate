package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Per-operation renderers for sequence DDL (Sub-Slice 5d).
 *
 * **Nicht zu verwechseln mit den identity-gestuetzten Sequenzen aus Slice
 * 3.** Hier geht es um eigenstaendige, benannte `SequenceDefinition`-Objekte;
 * die system-generierte Sequenz hinter einer Identity-Spalte (`ISEQ$$_n`)
 * laesst sich mit `ALTER SEQUENCE` gar nicht anfassen (`ORA-32793`) und wird
 * ueber die Identity-Klausel der Tabelle nachgezogen.
 *
 * Alle SQL-Formen stammen aus [OracleSequenceDdl], das sich der Generate-Pfad
 * teilt. Live gemessene Eigenheiten (2026-09-06):
 * - **`START WITH` ist unveraenderlich** (`ORA-02283`) -- eine Start-Aenderung
 *   wird gemeldet, nicht gerendert.
 * - **Kein `IF EXISTS`**: `DROP SEQUENCE` auf eine unbekannte Sequenz
 *   scheitert (`ORA-02289`).
 * - **Umbenannt wird mit der freistehenden Anweisung** `RENAME alt TO neu`,
 *   wie bei Views.
 */
internal object OracleDiffSequenceOps {

    fun renderCreateSequence(op: DiffOperation.CreateSequence, ctx: OracleDiffRenderContext) {
        val name = op.objectRef.rootName
        if (ctx.direction == OracleRenderDirection.DOWN) {
            ctx.emit(op, OracleSequenceDdl.dropSql(name), OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
            return
        }
        ctx.emit(op, OracleSequenceDdl.createSql(name, op.sequence), OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
    }

    fun renderDropSequence(op: DiffOperation.DropSequence, ctx: OracleDiffRenderContext) {
        val name = op.objectRef.rootName
        if (ctx.direction == OracleRenderDirection.DOWN) {
            ctx.emit(op, OracleSequenceDdl.createSql(name, op.sequence), OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
            return
        }
        ctx.emit(op, OracleSequenceDdl.dropSql(name), OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
    }

    fun renderAlterSequence(op: DiffOperation.AlterSequence, ctx: OracleDiffRenderContext) {
        val name = op.objectRef.rootName
        val target = if (ctx.direction == OracleRenderDirection.UP) op.after else op.before
        val source = if (ctx.direction == OracleRenderDirection.UP) op.before else op.after
        ctx.emit(op, OracleSequenceDdl.alterSql(name, target), OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
        warnImmutableStart(op, ctx, name, source, target)
    }

    fun renderRenameSequence(op: DiffOperation.RenameSequence, ctx: OracleDiffRenderContext) {
        val (from, to) = if (ctx.direction == OracleRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(op, OracleSequenceDdl.renameSql(from, to), OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
    }

    /**
     * Setzt den Laufzeitstand einer Sequenz.
     *
     * **Der Fortsetzungspunkt ist der gelesene Wert selbst**, nicht der Wert
     * plus Schrittweite. Oracles `ALL_SEQUENCES.LAST_NUMBER` fuehrt den
     * naechsten noch nicht reservierten Wert (gemessen: `START WITH 42`
     * meldet frisch `42`, nach einer Ziehung `43`) -- anders als T-SQLs
     * `sys.sequences.current_value`, das den zuletzt ausgegebenen meint und
     * deshalb dort um die Schrittweite erhoeht wird.
     *
     * Der Unterschied ist nicht kosmetisch: bei `CACHE n` springt
     * `LAST_NUMBER` blockweise voraus (gemessen: nach EINER Ziehung mit
     * `CACHE 20` steht es auf `21`, Oracle hat 1-20 reserviert). Auf dem
     * gelesenen Wert fortzusetzen laesst die reservierten Werte aus -- eine
     * Luecke, aber keine Doppelvergabe, denn `LAST_NUMBER` liegt stets ueber
     * allem je Ausgegebenen (bei einem seriell gelesenen Wert; zieht die
     * Anwendung zwischen Probe und `RESTART`, schuetzt erst der atomare
     * Pfad). Haette man die T-SQL-Arithmetik
     * uebernommen und vom zuletzt ausgegebenen Wert gerechnet, kaeme `2`
     * heraus und `2..20` wuerden ein zweites Mal vergeben.
     *
     * Erreichbar ist diese Operation fuer Oracle heute nicht:
     * `SequencePreserveStage` fuehrt Oracle nicht in seiner Dialektliste, der
     * Kandidat blockt also vorher. Der Renderer steht trotzdem, weil er die
     * gemessene Semantik festhaelt und der Sub-Slice, der die Stage
     * verdrahtet (5e), sonst zwei Dinge gleichzeitig entscheiden muesste.
     * `SequenceCapabilityDefaults.supportsCurrentValuePreserve` bleibt
     * derweil bewusst `false` -- das Feld sagt laut seinem KDoc den ganzen
     * Stage-Vertrag zu, nicht nur den Renderer.
     */
    fun renderAlterSequenceCurrentValue(op: DiffOperation.AlterSequenceCurrentValue, ctx: OracleDiffRenderContext) {
        val up = ctx.direction == OracleRenderDirection.UP
        val ref = if (up) op.applySequenceRef else op.probeSequenceRef
        val value = if (up) op.currentValue else op.restoreValue
        if (value == null) {
            ctx.skip(
                op,
                "Restoring the current value of sequence '${ref.name}' needs a restore value, and the " +
                    "operation carries none for this direction.",
                code = "ROLLBACK_NOT_POSSIBLE",
            )
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, setOf(op.id))
            return
        }
        // Die Schranken kommen aus der Sequenz-Definition; ohne sie laesst
        // sich nicht pruefen, ob der Fortsetzungspunkt ueberhaupt im
        // zulaessigen Bereich liegt -- dann blocken statt raten.
        val sequence = ctx.schemaForDirection()?.sequences?.get(ref.name)
        if (sequence == null) {
            ctx.skip(
                op,
                "Restoring the current value of sequence '${ref.name}' needs its definition to check the " +
                    "value against MINVALUE/MAXVALUE, but the schema for this direction carries none.",
                code = "ORACLE_SEQUENCE_NOT_IN_SCHEMA",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return
        }
        if (value == DiffOperation.AlterSequenceCurrentValue.ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE) {
            // Der Atomic-Preserve-Pfad legt den Follow-up nur als
            // Audit-Marker an und probt den echten Wert erst innerhalb der
            // Sperre; `currentValue` traegt dann diese Null. Sie als
            // Fortsetzungspunkt zu rendern setzte die Sequenz auf 0 zurueck.
            // PostgreSQL, MySQL und SQLite fangen das ebenso ab.
            ctx.skip(
                op,
                "Sequence '${ref.name}' carries the atomic-preserve sentinel instead of a probed value; " +
                    "the real value is read inside the lock at execution time, so there is nothing to render here.",
                code = "ORACLE_SEQUENCE_PRESERVE_SENTINEL",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return
        }
        if (!withinBounds(value, sequence)) {
            ctx.skip(
                op,
                "Sequence '${ref.name}' cannot resume at $value: the value lies outside its declared " +
                    "MINVALUE/MAXVALUE range.",
                code = "ORACLE_SEQUENCE_EXHAUSTED",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return
        }
        ctx.emit(op, OracleSequenceDdl.restartSql(ref.name, value), OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
    }

    /**
     * `null` heisst im neutralen Modell "nicht deklariert" -- in Oracle
     * bedeutet das aber NICHT unbegrenzt, sondern den richtungsabhaengigen
     * Default: `NOMINVALUE` ist bei aufsteigenden Sequenzen **1**,
     * `NOMAXVALUE` bei absteigenden **-1**. Die jeweils andere Seite ist
     * praktisch unbegrenzt (Oracle erlaubt 28 Stellen, mehr als `Long`
     * traegt) und wird deshalb an der `Long`-Grenze gekappt.
     *
     * Ohne diese Richtungsabhaengigkeit liesse der Waechter einen
     * Fortsetzungspunkt durch, den Oracle beim Ausfuehren mit `ORA-04006`
     * ablehnt.
     */
    private fun withinBounds(value: Long, seq: SequenceDefinition): Boolean {
        val ascending = seq.increment >= 0
        val min = seq.minValue ?: if (ascending) 1L else Long.MIN_VALUE
        val max = seq.maxValue ?: if (ascending) Long.MAX_VALUE else -1L
        return value in min..max
    }

    /**
     * Oracle kann den Startwert einer bestehenden Sequenz nicht aendern
     * (`ORA-02283`, gemessen). Eine Start-Abweichung wird deshalb gemeldet
     * statt gerendert.
     *
     * Fuer Oracle ist das haeufiger als anderswo: der Reverse liest
     * `LAST_NUMBER` als `start` (`R345`), sodass jede je gezogene Sequenz im
     * Diff mit abweichendem Startwert erscheint, ohne dass sich am Modell
     * etwas geaendert haette.
     */
    private fun warnImmutableStart(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        name: String,
        source: SequenceDefinition,
        target: SequenceDefinition,
    ) {
        if (source.start == target.start) return
        ctx.warning(
            op,
            "Sequence '$name' changes its start value from ${source.start} to ${target.start}, but Oracle " +
                "cannot alter the starting number of an existing sequence (ORA-02283); the rendered " +
                "ALTER SEQUENCE leaves it untouched. Note that Oracle's reverse read reports the current " +
                "LAST_NUMBER as the start value (R345), so a sequence that has ever been drawn from will " +
                "show this difference without the model having changed.",
            code = "ORACLE_SEQUENCE_START_IMMUTABLE",
        )
    }
}
