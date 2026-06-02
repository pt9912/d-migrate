package dev.dmigrate.driver.migration

/**
 * Coarse-grained locking footprint a [MigrationDdlStatement] is
 * expected to take (Plan-2 §A.1). Renderers set this from the
 * dialect's DDL contract; the report aggregates it so an operator
 * can estimate concurrency impact before executing.
 *
 * The value reflects the *most restrictive* lock the statement is
 * known to acquire. A statement that takes both a metadata lock and
 * an exclusive table lock reports [TABLE_EXCLUSIVE].
 *
 * [UNKNOWN] is the conservative default: a renderer that cannot
 * commit to a lock contract MUST NOT claim anything lighter.
 */
enum class LockBehavior {
    /** No locks beyond statement-level. PRAGMA statements, version queries. */
    NONE,

    /**
     * Row-level locks only. Reserved for future data-touching
     * statements (Plan §F.1); no current DDL renderer emits this.
     */
    ROW,

    /**
     * Metadata lock only — readers and writers on the same object
     * may continue, but conflicting DDL on the same object blocks.
     * MySQL online DDL would fall here when verifiable.
     */
    METADATA,

    /**
     * Shared table lock — readers continue, writers block. Used by
     * dialect-specific shared-lock DDL paths once they ship.
     */
    TABLE_SHARED,

    /**
     * Exclusive table lock — readers and writers block for the
     * duration of the statement. The conservative default for
     * `ALTER TABLE` / `DROP TABLE` / `CREATE INDEX` (non-CONCURRENTLY)
     * across PostgreSQL, MySQL and SQLite.
     */
    TABLE_EXCLUSIVE,

    /**
     * Renderer has not committed to a lock contract for this
     * statement. The report MUST NOT downgrade UNKNOWN to a lighter
     * value during aggregation.
     */
    UNKNOWN,
}
