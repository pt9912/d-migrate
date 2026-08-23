package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ViewQueryTransformer
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Sichten im Diff-Pfad (Sub-Slice 5c).
 *
 * T-SQL macht diesen Teil billiger als bei den anderen drei Dialekten:
 * `CREATE OR ALTER VIEW` gibt es nativ, `ReplaceView` ist damit ein einziges
 * Statement ohne Drop-und-Neu-Fenster. Zwei Dinge sind dafuer teurer:
 *
 * - **Materialized Views hat SQL Server nicht.** Der Generate-Pfad degradiert
 *   sie zur gewoehnlichen Sicht und meldet W103; im Diff-Pfad waere das kein
 *   Teilerfolg, sondern eine stille Zusage — eine materialisierte Sicht, die
 *   als gewoehnliche entsteht, liefert dieselben Zeilen mit voellig anderem
 *   Laufzeitverhalten. Sie bleibt deshalb dauerhaft geblockt (kein Slice
 *   holt das nach, T-SQL hat kein Aequivalent).
 * - **Der Rumpf wird nicht uebersetzt.** Eine Sicht aus einem anderen Dialekt
 *   muss T-SQL-faehig sein; die Pruefung ist dieselbe, die der Generate-Pfad
 *   fahrt ([ViewQueryTransformer]), und sie blockt mit E053 statt ungueltiges
 *   DDL zu emittieren.
 */
internal object MssqlDiffViewOps {

    fun renderCreateView(op: DiffOperation.CreateView, ctx: MssqlDiffRenderContext) {
        val name = op.objectRef.rootName
        if (blockMaterialized(op, ctx, name, op.view)) return
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, dropViewSql(ctx, name), MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS)
            return
        }
        emitView(op, ctx, name, op.view)
    }

    /**
     * `CREATE OR ALTER VIEW` ersetzt den Rumpf in einem Schritt — anders als
     * bei Dialekten ohne diese Form gibt es kein Fenster, in dem die Sicht
     * fehlt. Beide Richtungen rendern dieselbe Form, nur mit dem jeweils
     * anderen Rumpf.
     */
    fun renderReplaceView(op: DiffOperation.ReplaceView, ctx: MssqlDiffRenderContext) {
        val name = op.objectRef.rootName
        val target = if (ctx.direction == MssqlRenderDirection.UP) op.after else op.before
        if (blockMaterialized(op, ctx, name, op.before) || blockMaterialized(op, ctx, name, op.after)) return
        emitView(op, ctx, name, target)
    }

    fun renderDropView(op: DiffOperation.DropView, ctx: MssqlDiffRenderContext) {
        val name = op.objectRef.rootName
        if (blockMaterialized(op, ctx, name, op.view)) return
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            // DropView ist umkehrbar: der Rumpf steht in der Operation.
            emitView(op, ctx, name, op.view)
            return
        }
        ctx.emit(op, dropViewSql(ctx, name), MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS)
    }

    /**
     * `sp_rename` benennt die Sicht um, laesst ihren **Rumpf** aber unberuehrt:
     * in `sys.sql_modules` steht danach weiterhin `CREATE VIEW <alterName>`.
     * Fuer SQL Server ist das folgenlos, fuer d-migrate nicht — der Reverse
     * liest genau diesen Text.
     */
    fun renderRenameView(op: DiffOperation.RenameView, ctx: MssqlDiffRenderContext) {
        val (from, to) = if (ctx.direction == MssqlRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(op, ctx.sql.renameSql(from, to), MssqlDiffRenderContext.MSSQL_RENAME_HINTS)
        ctx.addInfoDiagnostic(
            code = "MSSQL_RENAME_KEEPS_VIEW_BODY",
            operationId = op.id,
            message = "sp_rename renames view '$from' to '$to', but the stored definition in sys.sql_modules " +
                "keeps saying CREATE VIEW '$from'. SQL Server does not care; a reverse read of the body does.",
        )
    }

    private fun emitView(op: DiffOperation, ctx: MssqlDiffRenderContext, name: String, view: ViewDefinition) {
        val query = view.query
        if (query.isNullOrBlank()) {
            ctx.skip(
                op,
                "View '$name' carries no query, so there is nothing to render.",
                code = "MSSQL_VIEW_WITHOUT_QUERY",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return
        }
        val transformer = ViewQueryTransformer(DatabaseDialect.MSSQL)
        val portability = transformer.assessPortability(query, view.sourceDialect)
        if (!portability.portable) {
            ctx.skip(
                op,
                "View '$name' body is not portable to SQL Server (${portability.reason}); d-migrate does not " +
                    "translate view bodies between dialects. Rewrite the body in T-SQL and re-run.",
                code = "E053",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return
        }
        val (transformed, notes) = transformer.transform(query, view.sourceDialect)
        ctx.emit(
            op,
            "CREATE OR ALTER VIEW ${ctx.sql.quote(name)} AS\n$transformed;",
            MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS,
        )
        ctx.carryOverNotes(op, notes)
    }

    private fun dropViewSql(ctx: MssqlDiffRenderContext, name: String) = "DROP VIEW ${ctx.sql.quote(name)};"

    private fun blockMaterialized(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        name: String,
        view: ViewDefinition,
    ): Boolean {
        if (!view.materialized) return false
        ctx.skip(
            op,
            "View '$name' is materialized, and SQL Server has no equivalent. Creating it as a plain view would " +
                "return the same rows with entirely different runtime behaviour — that is a silent promise, not " +
                "a partial success. (An indexed view is schema-bound and heavily restricted; the neutral model " +
                "carries none of what that needs.)",
            code = "DIALECT_UNSUPPORTED_OPERATION",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
        return true
    }
}
