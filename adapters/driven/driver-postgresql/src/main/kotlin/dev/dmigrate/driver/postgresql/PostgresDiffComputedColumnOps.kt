package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.PostgresServerVersion
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Der Migrationspfad fuer **berechnete** Spalten auf PostgreSQL.
 *
 * Eigenes Objekt, weil hier drei Fragen zusammenkommen, die sonst in
 * [PostgresDiffTableOps] zwischen zwanzig anderen Operationen laegen: ob der
 * Ausdruck sich aendern laesst, ob die Speicherform sich aendern laesst, und ob
 * die gewaehlte Form auf diesem Server ueberhaupt existiert. Alle drei haengen
 * an der Serverversion — bei PostgreSQL als einzigem der fuenf Dialekte.
 */
internal object PostgresDiffComputedColumnOps {

    private const val COMPUTED_DROP_NOT_SUPPORTED = "POSTGRES_COMPUTED_DROP_NOT_SUPPORTED"

    private const val COMPUTED_STORAGE_CHANGE_NOT_SUPPORTED = "POSTGRES_COMPUTED_STORAGE_CHANGE_NOT_SUPPORTED"

    private const val COMPUTED_SET_EXPRESSION_UNSUPPORTED = "POSTGRES_COMPUTED_SET_EXPRESSION_UNSUPPORTED"

    private fun storageWord(computed: ColumnGeneration.Computed): String =
        if (computed.stored) "STORED" else "VIRTUAL"

    /**
     * Der Berechnungsausdruck einer Spalte, in place gesetzt.
     *
     * **Ab PostgreSQL 17, und nur da.** Live gemessen gegen 18.6: Index auf der
     * Spalte und abhaengige Sicht ueberleben, der gespeicherte Wert entsteht
     * neu (3 × 7,00 → 42,00 nach Verdopplung des Ausdrucks). Darunter gibt es
     * den Befehl nicht; der einzige Ausweg waere `DROP` + `ADD`, und der nimmt
     * gemessen den Index stillschweigend mit und scheitert an einer
     * abhaengigen Sicht. Etwas stillschweigend zu verlieren ist schlechter,
     * als es nicht zu tun — deshalb wird dort geblockt statt ausgewichen.
     *
     * Ohne bekannte Serverversion (Datei-zu-Datei) wird die Faehigkeit **nicht**
     * unterstellt: eine geratene Zusage waere auf jeder Version unter 17 falsch.
     */
    fun renderAlterColumnGeneration(op: DiffOperation.AlterColumnGeneration, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val target = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        val expression = (target as? ColumnGeneration.Computed)?.expression
        if (expression == null) {
            ctx.skip(
                op,
                "Operation ${op.id} would drop the computed expression of `$table`.`$column`. " +
                    "PostgreSQL cannot turn a generated column into an ordinary one in place; " +
                    "drop and recreate the column manually.",
                code = COMPUTED_DROP_NOT_SUPPORTED,
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        // Die SPEICHERFORM zu wechseln ist kein `SET EXPRESSION` — der Befehl
        // laesst sie, wie sie ist. Aus virtuell gespeichert zu machen (oder
        // umgekehrt) geht nur ueber Loesen und Neuanlegen; das haette dieselben
        // Folgen wie unten und wird deshalb genauso geblockt statt gerendert.
        val before = op.before as? ColumnGeneration.Computed
        val after = op.after as? ColumnGeneration.Computed
        if (before != null && after != null && before.stored != after.stored) {
            ctx.skip(
                op,
                "Operation ${op.id} changes the storage form of the computed column `$table`.`$column` " +
                    "(${storageWord(before)} → ${storageWord(after)}). PostgreSQL has no command for that; " +
                    "`SET EXPRESSION` keeps the column where it is, and the only other route drops and " +
                    "recreates it.",
                code = COMPUTED_STORAGE_CHANGE_NOT_SUPPORTED,
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        val version = (ctx.options.dialectContext as? DdlDialectContext.Postgres)?.serverVersion
        if (version == null || !version.supportsSetExpression) {
            val seen = version?.let { "${it.major}.${it.minor}" } ?: "unknown (file-to-file run)"
            ctx.skip(
                op,
                "Operation ${op.id} changes the computed expression of `$table`.`$column`, which needs " +
                    "`ALTER COLUMN … SET EXPRESSION` — available from PostgreSQL " +
                    "${PostgresServerVersion.SET_EXPRESSION_SINCE_MAJOR} on; target reports $seen. " +
                    "The only route below that drops and recreates the column, losing its indexes and " +
                    "failing when a view depends on it, so it is not taken automatically.",
                code = COMPUTED_SET_EXPRESSION_UNSUPPORTED,
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        ctx.emit(
            op,
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} " +
                "SET EXPRESSION AS ($expression);",
        )
    }

    /**
     * Eine virtuelle berechnete Spalte, die auf diesem Server gespeichert
     * angelegt wird, darf nicht stillschweigend durchgehen — sonst steht im
     * Schema etwas anderes, als die Datenbank tut.
     */
    fun noteDegradedVirtual(
        op: DiffOperation,
        colName: String,
        col: ColumnDefinition,
        version: PostgresServerVersion?,
        ctx: PostgresDiffRenderContext,
    ) {
        val computed = col.generation as? ColumnGeneration.Computed ?: return
        if (!PostgresComputedStorage.isDegraded(computed, version)) return
        ctx.warning(
            op,
            PostgresComputedStorage.degradedMessage(colName, version),
            PostgresComputedStorage.DEGRADED_TO_STORED,
        )
    }
}
