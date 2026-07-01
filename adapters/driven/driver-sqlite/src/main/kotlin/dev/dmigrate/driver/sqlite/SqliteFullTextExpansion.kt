package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.IndexDefinition

/**
 * ADR 0025 (Slice P4): expand a neutral [dev.dmigrate.core.model.IndexType.FULLTEXT] index into a
 * SQLite **FTS5 external-content virtual table** + initial `'rebuild'` + **three sync triggers**
 * over the source text columns. Structural counterpart to the MySQL `CREATE FULLTEXT INDEX`
 * (P3) and the PostgreSQL `tsvector`+trigger+GiST expansion — one neutral index becomes several
 * DDL objects, mirroring the SpatiaLite geometry expansion ([SqliteSpatialDiffOps]).
 *
 * **Single SQL source** for BOTH emit paths (as [SqliteFullTextDegradation] was for the P2 skip):
 * the plain generate path ([SqliteTableDdlSupport.generateIndex]) wraps [createStatements] /
 * [dropStatements] in [dev.dmigrate.driver.DdlStatement]s, the diff/migrate render path
 * ([SqliteDiffSimpleOps]) streams them via [emitCreate] / [emitDrop] → `ctx.emit`.
 *
 * **Form — external content, implicit rowid.** `content='<base table>'` makes the index share the
 * base table's storage (no duplicated text); `content_rowid` defaults to `rowid`, so no
 * integer-PK lookup is needed and any rowid table works. The `'delete'` command form in the
 * UPDATE/DELETE triggers keeps an external-content index consistent (SQLite FTS5 docs pattern).
 *
 * **Deterministic naming.** The virtual table is named after the neutral index and the triggers
 * are `<index>_ai` / `<index>_ad` / `<index>_au`, so Slice P5's reverse drift filter can identify
 * and re-fold these synthesized objects instead of reverse-engineering them as user objects.
 *
 * **Not here (Slice P5 — diff/migrate hardening):** the reverse drift filter (FTS5 shadow tables
 * `_data`/`_idx`/`_docsize`/`_config` + the three sync triggers) and the table-rebuild recreate
 * path. [SqliteDiffSqlBuilders.createIndexSql] still degrades a FULLTEXT index to the W132 skip
 * inside a rebuild bucket until P5 teaches the rebuild about these dependent objects.
 */
internal object SqliteFullTextExpansion {

    /** The three sync-trigger names for [ftsName], deterministic so P5 can filter them on reverse. */
    fun triggerNames(ftsName: String): Triple<String, String, String> =
        Triple("${ftsName}_ai", "${ftsName}_ad", "${ftsName}_au")

    /**
     * The ordered DDL for one FULLTEXT index: create the external-content FTS5 virtual table,
     * populate it once from the existing content, then install the three sync triggers.
     * [baseTable], [ftsName] and [sourceColumns] are raw identifiers; [quote] applies SQLite
     * identifier quoting. Emit these after the base table exists (POST_DATA on generate).
     */
    fun createStatements(
        baseTable: String,
        ftsName: String,
        sourceColumns: List<String>,
        quote: (String) -> String,
    ): List<String> {
        val cols = sourceColumns.joinToString(", ") { quote(it) }
        val newVals = sourceColumns.joinToString(", ") { "new.${quote(it)}" }
        val oldVals = sourceColumns.joinToString(", ") { "old.${quote(it)}" }
        val fts = quote(ftsName)
        val base = quote(baseTable)
        val contentLiteral = baseTable.replace("'", "''")
        val (ai, ad, au) = triggerNames(ftsName)
        return listOf(
            "CREATE VIRTUAL TABLE $fts USING fts5($cols, content='$contentLiteral');",
            "INSERT INTO $fts($fts) VALUES('rebuild');",
            "CREATE TRIGGER ${quote(ai)} AFTER INSERT ON $base BEGIN\n" +
                "    INSERT INTO $fts(rowid, $cols) VALUES (new.rowid, $newVals);\n" +
                "END;",
            "CREATE TRIGGER ${quote(ad)} AFTER DELETE ON $base BEGIN\n" +
                "    INSERT INTO $fts($fts, rowid, $cols) VALUES('delete', old.rowid, $oldVals);\n" +
                "END;",
            "CREATE TRIGGER ${quote(au)} AFTER UPDATE ON $base BEGIN\n" +
                "    INSERT INTO $fts($fts, rowid, $cols) VALUES('delete', old.rowid, $oldVals);\n" +
                "    INSERT INTO $fts(rowid, $cols) VALUES (new.rowid, $newVals);\n" +
                "END;",
        )
    }

    /**
     * The inverse of [createStatements]: drop the three sync triggers, then the virtual table.
     * The FTS5 virtual table and its triggers are separate schema objects — dropping the base
     * table alone leaves them orphaned — so DOWN/rollback and DropIndex must tear them down
     * explicitly. `IF EXISTS` keeps the drop safe when a rebuild has already cascaded the triggers.
     */
    fun dropStatements(ftsName: String, quote: (String) -> String): List<String> {
        val (ai, ad, au) = triggerNames(ftsName)
        return listOf(
            "DROP TRIGGER IF EXISTS ${quote(ai)};",
            "DROP TRIGGER IF EXISTS ${quote(ad)};",
            "DROP TRIGGER IF EXISTS ${quote(au)};",
            "DROP TABLE IF EXISTS ${quote(ftsName)};",
        )
    }

    /** Diff/migrate render path: stream the FTS5 expansion for [index] on [table] via `ctx.emit`. */
    fun emitCreate(op: DiffOperation, ctx: SqliteDiffRenderContext, table: String, index: IndexDefinition) {
        val ftsName = ctx.sql.effectiveIndexName(table, index)
        createStatements(table, ftsName, index.columnNames, ctx.sql::quote).forEach { ctx.emit(op, it) }
    }

    /** Diff/migrate render path: stream the inverse teardown for [index] on [table] via `ctx.emit`. */
    fun emitDrop(op: DiffOperation, ctx: SqliteDiffRenderContext, table: String, index: IndexDefinition) {
        val ftsName = ctx.sql.effectiveIndexName(table, index)
        dropStatements(ftsName, ctx.sql::quote).forEach { ctx.emit(op, it) }
    }
}
