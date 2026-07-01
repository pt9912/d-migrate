package dev.dmigrate.driver.sqlite

/**
 * ADR 0025: the FULLTEXT → FTS5 structural expansion lives in [SqliteFullTextExpansion] (Slice
 * P4). This object holds only the remaining interim degradation: the table-**rebuild** recreate
 * path ([SqliteDiffSqlBuilders.createIndexSql], driven by [SqliteRebuildRenderer]) still emits a
 * visible skip marker instead of rebuilding the FTS5 objects — teaching the rebuild about the
 * virtual table + its three sync triggers as dependent objects is Slice P5. Kept as a single
 * source so that path never silently emits a plain BTREE over the source columns.
 */
internal object SqliteFullTextDegradation {

    /** No-op marker emitted in place of the index in a rebuild bucket. [quotedName] is pre-quoted. */
    fun skipComment(quotedName: String): String =
        "-- FULLTEXT index $quotedName skipped: SQLite needs an FTS5 virtual table for fulltext search"
}
