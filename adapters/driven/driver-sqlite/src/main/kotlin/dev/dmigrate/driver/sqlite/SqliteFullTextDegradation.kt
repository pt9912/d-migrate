package dev.dmigrate.driver.sqlite

/**
 * ADR 0025: SQLite has no fulltext index without an FTS5 virtual table (slice P4). Until that
 * lands a FULLTEXT index degrades with the dedicated **W132** note — single source of the
 * skip marker, message and hint so the generate and diff/migrate paths stay byte-identical.
 */
internal object SqliteFullTextDegradation {

    const val W_CODE: String = "W132"

    /** No-op marker emitted in place of the index (never a silent plain BTREE). [quotedName] is pre-quoted. */
    fun skipComment(quotedName: String): String =
        "-- FULLTEXT index $quotedName skipped: SQLite needs an FTS5 virtual table for fulltext search"

    fun message(indexName: String, tableName: String): String =
        "FULLTEXT index '$indexName' on table '$tableName' is not supported in SQLite without an " +
            "FTS5 virtual table; it has been skipped."

    const val HINT: String =
        "Create an FTS5 virtual table over the source columns plus sync triggers to retain full-text search."
}
