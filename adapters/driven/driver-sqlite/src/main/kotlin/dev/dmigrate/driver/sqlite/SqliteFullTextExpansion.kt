package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.IndexDefinition

/**
 * ADR 0025 (Slice P4): expand a neutral [dev.dmigrate.core.model.IndexType.FULLTEXT] index into a
 * SQLite **FTS5 external-content virtual table** + initial `'rebuild'` + **three sync triggers**
 * over the source text columns. Structural counterpart to the MySQL `CREATE FULLTEXT INDEX` (P3)
 * and the PostgreSQL `tsvector`+trigger+GiST expansion — one neutral index becomes several DDL
 * objects, mirroring the SpatiaLite geometry expansion ([SqliteSpatialDiffOps]).
 *
 * **Single SQL source** for BOTH emit paths: the plain generate path
 * ([SqliteTableDdlSupport.generateIndex]) wraps [createStatements] / [dropStatements] in
 * [dev.dmigrate.driver.DdlStatement]s, the diff/migrate render path ([SqliteDiffSimpleOps]) streams
 * them via [emitCreate] / [emitDrop] → `ctx.emit`. [ftsName] is the single naming source so both
 * paths agree on the virtual-table + trigger names (generate/migrate parity + P5 reverse-fold).
 *
 * **Form — external content, implicit rowid.** `content='<base table>'` makes the index share the
 * base table's storage (no duplicated text); `content_rowid` defaults to `rowid`. The `'delete'`
 * command form in the UPDATE/DELETE triggers keeps an external-content index consistent (SQLite
 * FTS5 docs pattern). This requires a real rowid and non-reserved column names — see
 * [unsupportedReason]; when it doesn't hold the index degrades conservatively (never broken DDL).
 *
 * **Not here (Slice P5 — diff/migrate hardening):** the reverse drift filter (FTS5 shadow tables
 * `_data`/`_idx`/`_docsize`/`_config` + the three sync triggers) and the table-rebuild recreate
 * path ([SqliteDiffSqlBuilders.createIndexSql] still degrades a FULLTEXT index in a rebuild bucket).
 */
internal object SqliteFullTextExpansion {

    // FTS5 reserves these column names, and external content requires the FTS columns to match the
    // content-table columns by name — so a source column named one of these can't be indexed.
    private val RESERVED_FTS5_COLUMNS = setOf("rowid", "rank")

    /**
     * Deterministic FTS5 virtual-table name for [index] — the neutral index name, or an anonymous
     * fallback that is IDENTICAL on the generate and diff paths. The generate path's own
     * anonymous-index scheme (`idx_<table>_<cols>`) differs from the diff path's
     * (`<table>_<cols>_idx`); routing both through this one helper keeps the synthesized FTS5
     * objects byte-identical across paths (generate/migrate parity) and lets the P5 reverse filter
     * recognise them. The trigger names derive from it via [triggerNames].
     */
    fun ftsName(table: String, index: IndexDefinition): String =
        index.name ?: "${table}_${index.columnNames.joinToString("_")}_idx"

    /**
     * Why external-content FTS5 cannot be built for [index] on this base table (→ conservative
     * degradation via [SqliteFullTextDegradation]), or null if it can. Direction-agnostic — it
     * depends only on (table, index) — so the degradation is automatically rollback-symmetric.
     */
    fun unsupportedReason(baseTableWithoutRowid: Boolean, index: IndexDefinition, ftsName: String): String? {
        if (baseTableWithoutRowid) {
            return "the base table is WITHOUT ROWID (external-content FTS5 needs the implicit rowid)"
        }
        index.columnNames.firstOrNull { it.lowercase() in RESERVED_FTS5_COLUMNS }?.let {
            return "source column '$it' is a reserved FTS5 column name"
        }
        index.columnNames.firstOrNull { it.equals(ftsName, ignoreCase = true) }?.let {
            return "source column '$it' collides with the FTS5 virtual-table name"
        }
        return null
    }

    /** The three sync-trigger names for [ftsName], deterministic so P5 can filter them on reverse. */
    fun triggerNames(ftsName: String): Triple<String, String, String> =
        Triple("${ftsName}_ai", "${ftsName}_ad", "${ftsName}_au")

    /**
     * The ordered DDL for one FULLTEXT index: create the external-content FTS5 virtual table,
     * populate it once from the existing content, then install the three sync triggers. Callers
     * must first check [unsupportedReason]; the identifiers/base table are raw and quoted here.
     * Emit these after the base table exists (POST_DATA on generate).
     */
    fun createStatements(baseTable: String, ftsName: String, sourceColumns: List<String>): List<String> {
        val cols = sourceColumns.joinToString(", ") { quoteSqliteIdentifier(it) }
        val newVals = sourceColumns.joinToString(", ") { "new.${quoteSqliteIdentifier(it)}" }
        val oldVals = sourceColumns.joinToString(", ") { "old.${quoteSqliteIdentifier(it)}" }
        val fts = quoteSqliteIdentifier(ftsName)
        val base = quoteSqliteIdentifier(baseTable)
        val (ai, ad, au) = triggerNames(ftsName)
        return listOf(
            "CREATE VIRTUAL TABLE $fts USING fts5($cols, content=${quoteSqliteStringLiteral(baseTable)});",
            "INSERT INTO $fts($fts) VALUES('rebuild');",
            "CREATE TRIGGER ${quoteSqliteIdentifier(ai)} AFTER INSERT ON $base BEGIN\n" +
                "    INSERT INTO $fts(rowid, $cols) VALUES (new.rowid, $newVals);\n" +
                "END;",
            "CREATE TRIGGER ${quoteSqliteIdentifier(ad)} AFTER DELETE ON $base BEGIN\n" +
                "    INSERT INTO $fts($fts, rowid, $cols) VALUES('delete', old.rowid, $oldVals);\n" +
                "END;",
            "CREATE TRIGGER ${quoteSqliteIdentifier(au)} AFTER UPDATE ON $base BEGIN\n" +
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
    fun dropStatements(ftsName: String): List<String> {
        val (ai, ad, au) = triggerNames(ftsName)
        return listOf(
            "DROP TRIGGER IF EXISTS ${quoteSqliteIdentifier(ai)};",
            "DROP TRIGGER IF EXISTS ${quoteSqliteIdentifier(ad)};",
            "DROP TRIGGER IF EXISTS ${quoteSqliteIdentifier(au)};",
            "DROP TABLE IF EXISTS ${quoteSqliteIdentifier(ftsName)};",
        )
    }

    /** Diff/migrate render path: stream the FTS5 expansion for [index] on [table], or degrade
     *  conservatively (skip marker + W132) when [unsupportedReason] applies. */
    fun emitCreate(op: DiffOperation, ctx: SqliteDiffRenderContext, table: String, index: IndexDefinition) {
        val fts = ftsName(table, index)
        val reason = unsupportedReason(ctx.tableWithoutRowid(table), index, fts)
        if (reason != null) {
            emitDegrade(op, ctx, table, fts, reason)
            return
        }
        createStatements(table, fts, index.columnNames).forEach { ctx.emit(op, it) }
    }

    /** Diff/migrate render path: the inverse teardown for [index] on [table]. When the table is
     *  unsupported the UP built nothing, so DOWN emits the same no-op skip marker (never a
     *  `DROP` of a non-existent object). */
    fun emitDrop(op: DiffOperation, ctx: SqliteDiffRenderContext, table: String, index: IndexDefinition) {
        val fts = ftsName(table, index)
        if (unsupportedReason(ctx.tableWithoutRowid(table), index, fts) != null) {
            ctx.emit(op, SqliteFullTextDegradation.skipComment(quoteSqliteIdentifier(fts)))
            return
        }
        dropStatements(fts).forEach { ctx.emit(op, it) }
    }

    private fun emitDegrade(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        table: String,
        fts: String,
        reason: String,
    ) {
        ctx.emit(op, SqliteFullTextDegradation.skipComment(quoteSqliteIdentifier(fts)))
        ctx.warning(
            op,
            "${SqliteFullTextDegradation.message(fts, table, reason)} ${SqliteFullTextDegradation.HINT}",
            code = SqliteFullTextDegradation.W_CODE,
        )
    }
}
