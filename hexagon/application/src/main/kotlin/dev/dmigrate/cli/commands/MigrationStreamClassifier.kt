package dev.dmigrate.cli.commands

import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionScope

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
 *   rendered SQL stream contains its own `BEGIN IMMEDIATE` / `COMMIT`
 *   markers (`SqliteRebuildRenderer`'s rebuild bracket). On failure
 *   the executor sends an explicit `ROLLBACK;` statement.
 *
 * Plan-2 §G.1: classification is now sourced from
 * [MigrationDdlStatement.transactionScope] set by the renderer.
 * The earlier SQL-content sniff (`sql.trimStart().startsWith("BEGIN")`)
 * is gone — renderers that emit routine bodies starting with
 * `BEGIN ... END` (PL/pgSQL, MySQL stored procedures, planned for
 * Plan-2 §E.1/§E.2) used to silently misclassify the stream and
 * disable the runner-managed tx for PG/MySQL.
 */
object MigrationStreamClassifier {

    /**
     * True iff any statement in the stream is rendered with
     * `transactionScope = STREAM_OWNED`. A mixed stream (some
     * STREAM_OWNED + some RUNNER_OWNED statements) currently
     * resolves to stream-owned for executor dispatch — the §G.1
     * "gemischte Streams blockieren vor Ausfuehrung" rule is
     * deferred to Plan-2 §G.3, which introduces the
     * `TRANSACTION_SCOPE_UNSUPPORTED` blocker and the
     * `transactionBoundary` contract. Until §G.3 lands, no current
     * renderer produces mixed streams; this method's fallthrough is
     * a known gap, not silent best-effort.
     */
    fun streamOwnsTransaction(statements: List<MigrationDdlStatement>): Boolean =
        statements.any { it.transactionScope == TransactionScope.STREAM_OWNED }

    /**
     * Plan-2 §G.3 execute guard. The current executor can run one
     * coherent ownership model at a time. Mixed scopes, standalone
     * `NO_TRANSACTION` statements and stream-owned statements without
     * boundary hints are rejected before the first SQL statement.
     */
    fun unsupportedTransactionScopeReason(statements: List<MigrationDdlStatement>): String? {
        if (statements.isEmpty()) return null
        val scopes = statements.map { it.transactionScope }.toSet()
        return when {
            TransactionScope.NO_TRANSACTION in scopes ->
                "NO_TRANSACTION statements require a dedicated execution strategy"
            scopes.size > 1 ->
                "mixed transaction scopes are not executable as one migration stream"
            scopes.single() == TransactionScope.STREAM_OWNED &&
                statements.any { it.hints.transactionBehavior == TransactionBehavior.UNKNOWN } ->
                "stream-owned transaction boundaries are not fully described"
            else -> null
        }
    }
}
