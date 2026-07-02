package dev.dmigrate.driver.sqlite

/**
 * ADR 0025: the FULLTEXT → FTS5 structural expansion lives in [SqliteFullTextExpansion] (Slice
 * P4). This object is the single source of the **conservative-degradation** text used where the
 * expansion can't be built — never a silent plain BTREE over the source columns. Two callers:
 *  1. [SqliteFullTextExpansion] and the table-rebuild recreate path ([SqliteRebuildRenderer])
 *     when [SqliteFullTextExpansion.unsupportedReason] applies (base table WITHOUT ROWID, or a
 *     reserved/colliding FTS5 column name) — degrade with the W132 note.
 *  2. [SqliteDiffSqlBuilders.createIndexSql] as a safety net for any remaining caller that
 *     routes a FULLTEXT index at a plain `CREATE INDEX` — emits the visible skip marker.
 */
internal object SqliteFullTextDegradation {

    const val W_CODE: String = "W132"

    /** No-op marker emitted in place of the index. [quotedName] is pre-quoted. */
    fun skipComment(quotedName: String): String =
        "-- FULLTEXT index $quotedName skipped: SQLite needs an FTS5 virtual table for fulltext search"

    fun message(indexName: String, tableName: String, reason: String): String =
        "FULLTEXT index '$indexName' on table '$tableName' could not be expanded to an FTS5 virtual " +
            "table ($reason); it has been skipped."

    const val HINT: String =
        "Create an FTS5 virtual table over the source columns plus sync triggers to retain full-text search."
}
