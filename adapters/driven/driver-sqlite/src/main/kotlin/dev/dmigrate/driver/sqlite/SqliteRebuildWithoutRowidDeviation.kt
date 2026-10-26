package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation

/**
 * Die Datei fuehrt `without_rowid: true`, die Datenbank-Tabelle aber ist eine Rowid-Tabelle.
 *
 * Ein Tabellenneubau behaelt das Merkmal der Datenbank ([SqliteRebuildPlan.withoutRowid]); die Datei
 * aendert es nicht. Die Migration laeuft weiter, `W164` sagt, dass die Tabelle eine Rowid-Tabelle bleibt.
 * Eine fehlende oder `false`-Angabe der Datei warnt nicht (nicht unterscheidbar, Modelldefault `false`).
 */
internal object SqliteRebuildWithoutRowidDeviation {

    const val W_CODE: String = "W164"

    fun message(table: String): String =
        "Table '$table' declares without_rowid: true, but the database table is a rowid table: the rebuild " +
            "keeps the database's form, and the table stays a rowid table."

    /** Nur Up: die Datei weicht von der Datenbank ab, die der Neubau fortschreibt. */
    fun warnIfDeviates(op: DiffOperation, ctx: SqliteDiffRenderContext, plan: SqliteRebuildPlan) {
        if (ctx.direction != SqliteRenderDirection.UP || !plan.fileDeclaresWithoutRowid) return
        ctx.warning(op, message(plan.originalTableName), code = W_CODE)
    }
}
