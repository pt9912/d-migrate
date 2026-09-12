package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ColumnGenerationTransition
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DialectCapabilities
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

    private const val COMPUTED_STORAGE_CHANGE_NOT_SUPPORTED = "POSTGRES_COMPUTED_STORAGE_CHANGE_NOT_SUPPORTED"

    private const val COMPUTED_SET_EXPRESSION_UNSUPPORTED = "POSTGRES_COMPUTED_SET_EXPRESSION_UNSUPPORTED"

    private const val COMPUTED_ADD_NOT_SUPPORTED = "POSTGRES_COMPUTED_ADD_NOT_SUPPORTED"

    private const val IDENTITY_ADD_WOULD_COLLIDE = "POSTGRES_IDENTITY_ADD_WOULD_COLLIDE"

    private const val IDENTITY_FROM_COMPUTED_NOT_SUPPORTED = "POSTGRES_IDENTITY_FROM_COMPUTED_NOT_SUPPORTED"

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
        val up = ctx.direction == PostgresRenderDirection.UP
        val from = if (up) op.before else op.after
        val target = if (up) op.after else op.before
        when (ColumnGenerationTransition.of(from, target)) {
            ColumnGenerationTransition.IDENTITY -> {
                renderIdentityTransition(op, ctx, table, column, from, target)
                return
            }
            ColumnGenerationTransition.COMPUTED_ADDED -> {
                ctx.skip(
                    op,
                    "Operation ${op.id} would make the ordinary column `$table`.`$column` computed. " +
                        "PostgreSQL refuses that in place — `SET EXPRESSION` answers `column \"$column\" … " +
                        "is not a generated column` (measured against 18.6). The only route is dropping and " +
                        "recreating the column, which loses its indexes; do it manually.",
                    code = COMPUTED_ADD_NOT_SUPPORTED,
                )
                ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
                return
            }
            ColumnGenerationTransition.COMPUTED_DROPPED -> {
                // Live gemessen gegen 18.6: der gespeicherte Wert bleibt als
                // gewoehnliche Daten stehen (42 blieb 42) und die Spalte ist
                // danach beschreibbar. Genau das sagt das Soll.
                ctx.emit(
                    op,
                    "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} " +
                        "DROP EXPRESSION;",
                )
                return
            }
            ColumnGenerationTransition.COMPUTED_EXPRESSION -> Unit
        }
        val expression = (target as ColumnGeneration.Computed).expression
        // Die SPEICHERFORM zu wechseln ist kein `SET EXPRESSION` — der Befehl
        // laesst sie, wie sie ist. Aus virtuell gespeichert zu machen (oder
        // umgekehrt) geht nur ueber Loesen und Neuanlegen; das haette dieselben
        // Folgen wie unten und wird deshalb genauso geblockt statt gerendert.
        val before = from as? ColumnGeneration.Computed
        val after = target as? ColumnGeneration.Computed
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
        // Eine Frage, eine Stelle — die Tabelle rechnet die Version ein und
        // weiss, dass „unbekannt" hier die konservative Seite bedeutet.
        if (!DialectCapabilities.forTarget(DatabaseDialect.POSTGRESQL, version).supportsComputedExpressionInPlace) {
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

    /**
     * Die Identity einer Spalte, soweit PostgreSQL sie in place aendert.
     *
     * Gemessen gegen 18.6, und die vierte Zeile ist der Grund fuer die
     * Ablehnung:
     *
     * | Uebergang | Ergebnis |
     * | --- | --- |
     * | `ALWAYS` ↔ `BY DEFAULT` | `SET GENERATED …`, Werte und Sequenz bleiben |
     * | Identity → gewoehnlich | `DROP IDENTITY`, Werte bleiben, Sequenz wird entfernt |
     * | berechnet ↔ Identity | `SET EXPRESSION` scheitert („is not a generated column") |
     * | gewoehnlich → Identity | **laeuft** — aber die neue Sequenz beginnt bei 1, und der naechste `INSERT` kollidiert mit dem Bestand (`duplicate key value violates unique constraint`) |
     *
     * Der letzte Fall waere eine Migration, die die Tabelle still unbrauchbar
     * zuruecklaesst; deshalb wird er benannt abgelehnt statt gerendert. Ihn
     * sauber zu fahren braeuchte ein Nachziehen der Sequenz auf `max(spalte)`,
     * also eine Datenanweisung im DDL-Plan — eine eigene Entscheidung.
     */
    private fun renderIdentityTransition(
        op: DiffOperation.AlterColumnGeneration,
        ctx: PostgresDiffRenderContext,
        table: String,
        column: String,
        from: ColumnGeneration?,
        target: ColumnGeneration?,
    ) {
        val fromIdentity = from as? ColumnGeneration.Identity
        val targetIdentity = target as? ColumnGeneration.Identity
        when {
            fromIdentity != null && targetIdentity != null -> ctx.emit(
                op,
                "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} " +
                    "SET GENERATED ${identityWord(targetIdentity)};",
            )

            fromIdentity != null && target == null -> ctx.emit(
                op,
                "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} DROP IDENTITY;",
            )

            targetIdentity != null -> {
                ctx.skip(
                    op,
                    "Operation ${op.id} would make `$table`.`$column` an identity column. PostgreSQL " +
                        "accepts the statement, but the new sequence starts at 1 — the next `INSERT` then " +
                        "collides with the rows already there (`duplicate key value violates unique " +
                        "constraint`, measured against 18.6). Set the identity up yourself and reseed its " +
                        "sequence to `max($column)`, or express the column through the type (`identifier`).",
                    code = IDENTITY_ADD_WOULD_COLLIDE,
                )
                ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            }

            else -> {
                ctx.skip(
                    op,
                    "Operation ${op.id} turns the identity column `$table`.`$column` into a computed one. " +
                        "PostgreSQL has no in-place route: `SET EXPRESSION` answers `is not a generated " +
                        "column` (measured against 18.6), and adding an expression means dropping and " +
                        "recreating the column; do it manually.",
                    code = IDENTITY_FROM_COMPUTED_NOT_SUPPORTED,
                )
                ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            }
        }
    }

    private fun identityWord(identity: ColumnGeneration.Identity): String =
        if (identity.mode == IdentityMode.ALWAYS) "ALWAYS" else "BY DEFAULT"

}
