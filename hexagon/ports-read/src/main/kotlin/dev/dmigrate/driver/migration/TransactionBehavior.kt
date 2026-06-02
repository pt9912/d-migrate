package dev.dmigrate.driver.migration

/**
 * Dialect-level transactional contract for a single
 * [MigrationDdlStatement] (Plan-2 §A.1).
 *
 * Distinct from [TransactionScope], which describes who *owns* the
 * JDBC transaction (runner vs. stream). [TransactionBehavior]
 * describes what the database itself guarantees once a statement is
 * executed inside (or alongside) that transaction.
 *
 * Renderers MUST set this explicitly via [DialectExecutionHints];
 * [UNKNOWN] is the conservative default for non-renderer
 * construction sites and is treated as "not safe to make
 * transactional claims" by the report.
 */
enum class TransactionBehavior {
    /**
     * Statement runs entirely inside the active transaction and is
     * fully rolled back on failure. PostgreSQL DDL, SQLite DDL inside
     * a `BEGIN`/`COMMIT` bracket.
     */
    FULLY_TRANSACTIONAL,

    /**
     * The database implicitly commits surrounding work and starts a
     * new (auto-)transaction when the statement runs. MySQL DDL is
     * the canonical case. Implies [DialectExecutionHints.implicitCommitPossible].
     */
    IMPLICIT_COMMIT,

    /**
     * Statement cannot run inside the active transaction; it must run
     * outside any wrapping transaction. PostgreSQL `CREATE INDEX
     * CONCURRENTLY` (future), `VACUUM`. Pairs with
     * [TransactionScope.NO_TRANSACTION].
     */
    NOT_TRANSACTIONAL,

    /**
     * Conservative default. Used when a renderer hasn't supplied a
     * dialect contract yet, or when the dialect's behavior depends on
     * server version or runtime mode that the renderer cannot
     * determine offline (MySQL online vs. copy ALTER, for example).
     * The report MUST NOT claim full rollback for `UNKNOWN`.
     */
    UNKNOWN,
}
