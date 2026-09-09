package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ViewQueryTransformer
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Per-operation renderers for view DDL (Sub-Slice 5c).
 *
 * Oracle-Eigenheiten, live gemessen (2026-09-06,
 * `gvenzl/oracle-free:23-slim-faststart`):
 * - **`CREATE OR REPLACE VIEW` darf die Spaltenliste frei aendern**
 *   (Spaltenzahl UND Namen, verifiziert). Oracle hat damit **kein**
 *   Signaturproblem wie PostgreSQL, dessen Diff-Pfad ein `ReplaceView` mit
 *   unbekannter oder unvertraeglicher Signatur blockt. Ein solcher Waechter
 *   waere fuer Oracle ohnehin wirkungslos: `ViewDefinition.columns` befuellen
 *   nur PostgreSQLs Reverse und der Datei-Parser, Oracles Reverse nie -- er
 *   blockte hier also jedes aus der Datenbank gelesene `ReplaceView`.
 * - **`ALTER VIEW ... RENAME TO` gibt es nicht** (`ORA-00922`). Umbenannt
 *   wird mit der freistehenden Anweisung `RENAME alt TO neu`.
 * - **`FORCE` legt eine View auch ueber fehlendem Unterbau an** (Status
 *   `INVALID`, verifiziert) -- deshalb rendert schon der Generate-Pfad
 *   `CREATE OR REPLACE FORCE VIEW`, und der Diff-Pfad tut es genauso: eine
 *   Sicht, deren Abhaengigkeit erst spaeter in der Operationsreihenfolge
 *   entsteht, scheitert sonst sofort.
 * - **Kein `IF EXISTS`**: `DROP VIEW` auf eine unbekannte View scheitert
 *   (`ORA-00942`). Die Down-Richtung darf nicht auf Idempotenz bauen.
 */
internal object OracleDiffViewOps {

    private val transformer = ViewQueryTransformer(OracleViewPortabilityRules)

    fun renderCreateView(op: DiffOperation.CreateView, ctx: OracleDiffRenderContext) {
        val name = op.objectRef.rootName
        // Der Waechter steht VOR der Richtungsverzweigung: sonst blockte die
        // Up-Richtung, waehrend die Down-Richtung ein `DROP VIEW` auf eine
        // materialisierte Sicht absetzte (ORA-00942).
        if (blockMaterialized(op, ctx, name, op.view)) return
        if (ctx.direction == OracleRenderDirection.DOWN) {
            ctx.emit(op, dropViewSql(ctx, name), OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
            return
        }
        emitView(op, ctx, name, op.view)
    }

    /**
     * Beide Seiten pruefen, nicht nur die der Richtung: `OperationMapper`
     * erzeugt fuer einen View-/MV-Wechsel (`before.materialized !=
     * after.materialized`) ein gewoehnliches `ReplaceView`. Ein MV→View-Wechsel
     * rendert sonst in der Up-Richtung ein `CREATE OR REPLACE FORCE VIEW` ueber
     * einer materialisierten Ausgangssicht, ohne dass der Waechter greift.
     */
    fun renderReplaceView(op: DiffOperation.ReplaceView, ctx: OracleDiffRenderContext) {
        val name = op.objectRef.rootName
        if (blockMaterialized(op, ctx, name, op.before)) return
        if (blockMaterialized(op, ctx, name, op.after)) return
        val target = if (ctx.direction == OracleRenderDirection.UP) op.after else op.before
        emitView(op, ctx, name, target)
    }

    fun renderDropView(op: DiffOperation.DropView, ctx: OracleDiffRenderContext) {
        val name = op.objectRef.rootName
        if (blockMaterialized(op, ctx, name, op.view)) return
        if (ctx.direction == OracleRenderDirection.DOWN) {
            emitView(op, ctx, name, op.view)
            return
        }
        ctx.emit(op, dropViewSql(ctx, name), OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS)
    }

    /**
     * Live bestaetigt: `RENAME alt TO neu` ist der Weg, `ALTER VIEW ...
     * RENAME TO` existiert nicht. Die freistehende Anweisung arbeitet
     * ausschliesslich im eigenen Schema -- eine schema-qualifizierte
     * Umbenennung kennt Oracle nicht, was hier aber nicht auffaellt, weil
     * das neutrale Modell Views ohnehin unqualifiziert fuehrt.
     */
    fun renderRenameView(op: DiffOperation.RenameView, ctx: OracleDiffRenderContext) {
        val (oldName, newName) = if (ctx.direction == OracleRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(
            op,
            "RENAME ${ctx.sql.quote(oldName)} TO ${ctx.sql.quote(newName)};",
            OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS,
        )
    }

    private fun dropViewSql(ctx: OracleDiffRenderContext, name: String): String =
        "DROP VIEW ${ctx.sql.quote(name)};"

    private fun emitView(op: DiffOperation, ctx: OracleDiffRenderContext, name: String, view: ViewDefinition) {
        if (blockMaterialized(op, ctx, name, view)) return
        val query = view.query
        if (query.isNullOrBlank()) {
            // Der Generate-Pfad ueberspringt eine View ohne Rumpf still
            // (SkippedObject). Im Migrationspfad waere das falsch: die
            // Operation behauptet, eine Sicht anzulegen, und es entstuende
            // keine.
            ctx.skip(
                op,
                "Operation ${op.id}: view '$name' carries no query; there is nothing to create.",
                code = "ORACLE_VIEW_WITHOUT_QUERY",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return
        }
        val portability = transformer.assessPortability(query, view.sourceDialect)
        if (!portability.portable) {
            ctx.skip(
                op,
                "Operation ${op.id}: view '$name' body is not portable to Oracle (${portability.reason}); " +
                    "d-migrate does not translate view bodies between dialects.",
                code = "E053",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return
        }
        val (portableQuery, notes) = transformer.transform(query, view.sourceDialect)
        // Wie im Generate-Pfad: unquotiert sucht Oracle GROSSSCHREIBUNG und
        // findet die wortgetreu angelegte Tabelle nicht (ORA-00942).
        val transformedQuery = OracleIdentifierRequoter.requote(
            portableQuery,
            ctx.schemaForDirection()?.let(OracleIdentifierRequoter::knownIdentifiers).orEmpty(),
            ctx.sql::quote,
        )
        ctx.emit(
            op,
            "CREATE OR REPLACE FORCE VIEW ${ctx.sql.quote(name)} AS\n$transformedQuery;",
            OracleDiffRenderContext.ORACLE_METADATA_DDL_HINTS,
        )
        ctx.carryOverNotes(op, notes)
    }

    /**
     * Blockt eine materialisierte Sicht auf dem **gewoehnlichen** View-Pfad.
     *
     * Die eigenen MV-Operationen laufen ueber [OracleDiffMaterializedViewOps];
     * was hier ankommt, ist ein View-/MV-Wechsel — dafuer hat Oracle keine
     * Umwandlung. Der Waechter steht in jedem der vier Renderer, damit die
     * Down-Richtung kein `DROP VIEW` auf eine materialisierte Sicht absetzt
     * (ORA-00942).
     */
    private fun blockMaterialized(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        name: String,
        view: ViewDefinition,
    ): Boolean {
        if (!view.materialized) return false
        // Eine materialisierte Sicht, die den GEWOEHNLICHEN View-Pfad
        // erreicht, ist ein View-/MV-Wechsel: die eigenen MV-Operationen
        // laufen ueber [OracleDiffMaterializedViewOps]. Oracle kennt dafuer
        // keine Umwandlung -- es waere ein Loeschen und ein Anlegen unter
        // demselben Namen, und der Bestand der MV entstuende neu.
        ctx.skip(
            op,
            "Operation ${op.id}: '$name' changes between a plain and a materialized view. Oracle has no " +
                "conversion for that; drop the one and create the other explicitly.",
            code = "ORACLE_MATERIALIZED_VIEW_CONVERSION_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
        return true
    }
}
