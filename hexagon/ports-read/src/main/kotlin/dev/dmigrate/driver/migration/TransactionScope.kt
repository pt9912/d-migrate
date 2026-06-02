package dev.dmigrate.driver.migration

/**
 * Who owns the JDBC transaction during execution of a
 * [MigrationDdlStatement]. Replaces the SQL-content-sniffing
 * heuristic in `MigrationStreamClassifier` (Plan-2 §G.1; formerly
 * Plan §11.2 Carve-out F.4-1).
 *
 * Renderers must set this explicitly per statement; consumers
 * (executors, classifiers) dispatch on this field instead of
 * parsing the SQL body. This is the foundation for Plan-2 §G.2
 * (structured rollback-artefact statement serialization) and §G.3
 * (per-group `transactionBoundary` / `recoverability`).
 */
enum class TransactionScope {
    /**
     * Runner sets `autoCommit = false`, runs all statements in one
     * outer JDBC transaction, commits on success and rolls back on
     * failure. Default for PostgreSQL and MySQL diff streams and for
     * single-statement SQLite operations.
     */
    RUNNER_OWNED,

    /**
     * The SQL stream contains its own `BEGIN`/`COMMIT`/`ROLLBACK`
     * markers; the runner stays in `autoCommit = true` and never
     * calls `conn.commit()`. Used by SQLite's rebuild pipeline,
     * which emits `BEGIN IMMEDIATE;` … `COMMIT;` as part of the
     * statement stream.
     */
    STREAM_OWNED,

    /**
     * Statement must not (or cannot) run inside a runner-managed
     * transaction. Examples (future use): PostgreSQL
     * `CREATE INDEX CONCURRENTLY`, MySQL DDL with implicit commit
     * where the side effect is intentional. Plan-2 §A.1 will refine
     * how this is surfaced; G.1 only reserves the value.
     */
    NO_TRANSACTION,
}
