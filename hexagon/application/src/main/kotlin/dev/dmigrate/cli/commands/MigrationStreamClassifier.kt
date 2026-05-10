package dev.dmigrate.cli.commands

import dev.dmigrate.driver.migration.MigrationDdlStatement

/**
 * Classifies a migration SQL stream by which side owns the JDBC
 * transaction during execution. Used by `JdbcMigrationExecutor`
 * (production) and `MigrationExecutorTestSupport.executeAgainstPool`
 * (test fixture) to dispatch to the matching execution strategy:
 *
 * - **Runner-owned** (`streamOwnsTransaction = false`): the executor
 *   sets `autoCommit = false`, runs all statements inside one outer
 *   JDBC tx, `conn.commit()` on success, `conn.rollback()` on
 *   failure. Default for PG, MySQL, and SQLite-direct streams.
 *
 * - **Stream-owned** (`streamOwnsTransaction = true`): the executor
 *   leaves `autoCommit = true` and never calls `conn.commit()`. The
 *   rendered SQL stream contains its OWN `BEGIN IMMEDIATE` / `COMMIT`
 *   markers (`SqliteRebuildRenderer`'s 9-statement rebuild). On
 *   failure the executor sends an explicit `ROLLBACK;` statement.
 *
 * See `docs/planning/in-progress/diffresult-migration-plan.md §11.2`
 * Carve-out F.4-1 for the long-term plan: replace this content-
 * sniffing heuristic with an explicit `transactionScope` field on
 * [MigrationDdlStatement] before any diff renderer starts emitting
 * statements that themselves contain `BEGIN ... END` routine bodies.
 */
object MigrationStreamClassifier {

    /**
     * True iff the rendered SQL stream owns its own transaction via
     * an explicit `BEGIN`-style statement.
     *
     * Detection: any statement's first non-whitespace token is
     * `BEGIN` (case-insensitive), optionally followed by a transaction
     * mode (`IMMEDIATE`, `DEFERRED`, `EXCLUSIVE`, `TRANSACTION`) or
     * a terminator (`;`). Currently only emitted by
     * `SqliteRebuildRenderer`.
     *
     * Bounded false-positive risk: a future renderer that emits a
     * routine body whose first token is `BEGIN ... END` (PL/pgSQL,
     * MySQL stored procedures) would misclassify the stream and
     * silently disable the runner-managed tx for PG/MySQL. Currently
     * no diff renderer takes that path — see Carve-out F.4-1.
     */
    fun streamOwnsTransaction(statements: List<MigrationDdlStatement>): Boolean =
        statements.any { isBeginStatement(it.sql) }

    /**
     * Per-statement begin-token check. Internal so unit tests can
     * exercise the boundary cases directly without constructing
     * full [MigrationDdlStatement] lists.
     */
    internal fun isBeginStatement(sql: String): Boolean {
        val trimmed = sql.trimStart().uppercase()
        return trimmed.startsWith("BEGIN;") ||
            trimmed.startsWith("BEGIN ") ||
            trimmed == "BEGIN"
    }
}
