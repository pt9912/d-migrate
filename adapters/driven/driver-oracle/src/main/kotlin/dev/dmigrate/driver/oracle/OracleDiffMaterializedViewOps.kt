package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ViewQueryTransformer
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Materialized Views im Diff-Pfad.
 *
 * Anders als bei Sichten und Routinen gibt es **kein `CREATE OR REPLACE`**
 * (gemessen: ORA-00922). Ein Ersetzen ist deshalb ein Loeschen und ein
 * Anlegen — mit einem Fenster, in dem die Sicht fehlt, und einem Bestand, der
 * neu aufgebaut wird. Beides steht als eigene Anweisung im Plan, damit der
 * Betreiber es sieht, statt es hinter einem Statement zu verbergen.
 *
 * Ob sich eine MV ueberhaupt anlegen laesst, entscheidet dieselbe Quelle wie
 * im Generate-Pfad ([OracleMaterializedViewDdl]).
 *
 * Anders als Sichten-DDL laufen diese Anweisungen **nicht** als reine
 * Katalogschreibung: `CREATE MATERIALIZED VIEW … AS <abfrage>` fuehrt den
 * Erstaufbau aus und liest dabei alle Zeilen der Basistabellen, `DROP`
 * vernichtet abgelegte Daten. Sie tragen deshalb die gewoehnlichen
 * Oracle-DDL-Hinweise, nicht die der Metadaten-Anweisungen.
 */
internal object OracleDiffMaterializedViewOps {

    private val transformer = ViewQueryTransformer(DatabaseDialect.ORACLE)

    fun renderCreate(op: DiffOperation.CreateMaterializedView, ctx: OracleDiffRenderContext) {
        val name = op.objectRef.rootName
        // Beide Richtungen pruefen dieselbe Form: was die Up-Richtung nicht
        // anlegen kann, existiert auch nicht, und ein `DROP MATERIALIZED
        // VIEW` darauf scheitert (ein `IF EXISTS` gibt es hier nicht).
        if (blocked(op, ctx, name, op.view)) return
        if (ctx.direction == OracleRenderDirection.DOWN) {
            emitDrop(op, ctx, name)
            return
        }
        emitCreate(op, ctx, name, op.view, alreadyChecked = true)
    }

    fun renderDrop(op: DiffOperation.DropMaterializedView, ctx: OracleDiffRenderContext) {
        if (ctx.direction == OracleRenderDirection.DOWN) {
            emitCreate(op, ctx, op.objectRef.rootName, op.view)
            return
        }
        emitDrop(op, ctx, op.objectRef.rootName)
    }

    /**
     * Loeschen und Anlegen, in dieser Reihenfolge. Der Bestand der Sicht
     * entsteht dabei neu — bei `ON DEMAND` erst beim naechsten Refresh.
     */
    fun renderReplace(op: DiffOperation.ReplaceMaterializedView, ctx: OracleDiffRenderContext) {
        val name = op.objectRef.rootName
        val target = if (ctx.direction == OracleRenderDirection.UP) op.after else op.before
        // Die Pruefung steht VOR dem Loeschen: sonst entstuende ein `DROP`
        // ohne das `CREATE` dahinter, und die Sicht waere weg.
        if (blocked(op, ctx, name, target)) return
        emitDrop(op, ctx, name)
        emitCreate(op, ctx, name, target, alreadyChecked = true)
    }

    private fun emitDrop(op: DiffOperation, ctx: OracleDiffRenderContext, name: String) {
        ctx.emit(
            op,
            OracleMaterializedViewDdl.dropSql(name) { ctx.sql.quote(it) },
        )
    }

    private fun emitCreate(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        name: String,
        view: ViewDefinition,
        alreadyChecked: Boolean = false,
    ) {
        if (!alreadyChecked && blocked(op, ctx, name, view)) return
        val (query, _) = transformer.transform(checkNotNull(view.query), view.sourceDialect)
        ctx.emit(
            op,
            OracleMaterializedViewDdl.createSql(name, view, query) { ctx.sql.quote(it) },
        )
    }

    private fun blocked(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        name: String,
        view: ViewDefinition,
    ): Boolean {
        if (view.query.isNullOrBlank()) {
            ctx.skip(
                op,
                "Operation ${op.id}: materialized view '$name' carries no query; there is nothing to create.",
                code = "ORACLE_VIEW_WITHOUT_QUERY",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return true
        }
        val problem = OracleMaterializedViewDdl.unsupportedShape(name, view) ?: return false
        ctx.skip(op, "${problem.reason} ${problem.hint}", code = "E053")
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
        return true
    }
}
