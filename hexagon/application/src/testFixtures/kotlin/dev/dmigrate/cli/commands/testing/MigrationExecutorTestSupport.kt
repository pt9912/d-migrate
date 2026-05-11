package dev.dmigrate.cli.commands.testing

import dev.dmigrate.cli.commands.ExecutionTrace
import dev.dmigrate.cli.commands.MigrationStreamClassifier
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.migration.MigrationDdlStatement
import java.sql.Connection
import java.sql.SQLException

/**
 * Test-only mirror of the production `JdbcMigrationExecutor.runAll`
 * for the round-trip smoke tests in `test/integration-*`.
 *
 * Why this duplicate exists: the real `JdbcMigrationExecutor` is
 * `internal` to the `:adapters:driving:cli` module (it owns its own
 * pool lifecycle via `NamedConnectionResolver` + Hikari URL parsing),
 * which the integration-test modules cannot link to without breaking
 * the layering boundary `hexagon:application` → `:adapters:driving:cli`.
 * The smoke tests already have a configured pool against their
 * Testcontainer; they only need the **execution** side of the
 * contract — autocommit toggling, single-statement loop, rollback on
 * failure, [ExecutionTrace] population.
 *
 * Contract is **byte-identical** to `JdbcMigrationExecutor.runAll`,
 * including its two transaction-ownership models:
 *
 * - **Runner-owned tx (default)** — `autoCommit = false`, all
 *   statements in one outer JDBC tx, `conn.commit()` on success,
 *   `conn.rollback()` on `SQLException`. Used for PG, MySQL, and
 *   SQLite-direct streams.
 *
 * - **Stream-owned tx** — selected when the stream contains an
 *   explicit `BEGIN ` statement (SQLite rebuild path, both migrate-
 *   Up and rollback-Down). `autoCommit = true`, no `conn.commit()`
 *   — the stream's own `BEGIN IMMEDIATE` / `COMMIT` markers manage
 *   the tx. On error, an explicit `ROLLBACK;` is sent.
 *
 * Empty statement list → `executionStarted = true,
 * executionCompleted = true`, no rows attempted (both models).
 *
 * If you fix a bug here, fix it in `JdbcMigrationExecutor.runAll`
 * too (and vice versa). A drift would silently make the smoke tests
 * lie about what production does.
 */
fun executeAgainstPool(
    pool: ConnectionPool,
    statements: List<MigrationDdlStatement>,
): ExecutionTrace {
    if (statements.isEmpty()) {
        return ExecutionTrace(executionStarted = true, executionCompleted = true)
    }
    return pool.borrow().use { conn ->
        if (MigrationStreamClassifier.streamOwnsTransaction(statements)) {
            runStreamOwnedTransaction(conn, statements)
        } else {
            runRunnerOwnedTransaction(conn, statements)
        }
    }
}

@Suppress("ReturnCount")
private fun runRunnerOwnedTransaction(
    conn: Connection,
    statements: List<MigrationDdlStatement>,
): ExecutionTrace {
    conn.autoCommit = false
    var attempted = 0
    var lastIds: Set<String> = emptySet()
    return try {
        conn.createStatement().use { jdbcStmt ->
            for (s in statements) {
                lastIds = s.operationIds
                attempted++
                jdbcStmt.execute(s.sql)
            }
        }
        conn.commit()
        ExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = attempted,
            lastStatementOperationIds = lastIds,
        )
    } catch (e: SQLException) {
        rollbackTrace(conn, attempted, lastIds, e, jdbcRollback = true)
    }
}

@Suppress("ReturnCount")
private fun runStreamOwnedTransaction(
    conn: Connection,
    statements: List<MigrationDdlStatement>,
): ExecutionTrace {
    conn.autoCommit = true
    var attempted = 0
    var lastIds: Set<String> = emptySet()
    // Phase H.3b: route Hook-Marker through the shared
    // RunnerHookHandler so test-fixture behaviour matches the production
    // executor. Before this, hooks were passed verbatim to
    // jdbcStmt.execute as SQL comments (xerial-sqlite tolerated them
    // silently, masking H.3b regressions in Application-layer tests).
    val hookState = dev.dmigrate.cli.commands.RunnerHookHandler.State()
    return try {
        // Per-statement fresh Statement — see JdbcMigrationExecutor for
        // the xerial-sqlite Statement-finalisation rationale.
        for (s in statements) {
            lastIds = s.operationIds
            attempted++
            conn.createStatement().use { jdbcStmt ->
                dev.dmigrate.cli.commands.RunnerHookHandler.executeOrApply(jdbcStmt, s.sql, hookState)
            }
        }
        ExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = attempted,
            lastStatementOperationIds = lastIds,
        )
    } catch (e: SQLException) {
        // Phase H.3b: post-rollback FK-state restore — must mirror
        // production (`JdbcMigrationExecutor`) byte-for-byte so the
        // application-layer smoke tests pin the same contract. SQLite
        // ignores `PRAGMA foreign_keys = ...` inside an open tx, so
        // the restore is gated on rollback success and emitted after.
        rollbackTrace(
            conn = conn,
            attempted = attempted,
            lastIds = lastIds,
            cause = e,
            jdbcRollback = false,
            postRollback = { tryRestoreFkStateAfterRollback(conn, hookState) },
        )
    }
}

private fun tryRestoreFkStateAfterRollback(
    conn: Connection,
    hookState: dev.dmigrate.cli.commands.RunnerHookHandler.State,
) {
    if (hookState.savedSqliteForeignKeysPragma == null) return
    try {
        conn.createStatement().use { stmt ->
            dev.dmigrate.cli.commands.RunnerHookHandler.apply(stmt, "restore-fk-state", hookState)
        }
    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") _: Exception) {
        // Best-effort: original SQLException already drives the trace.
    }
}

private fun rollbackTrace(
    conn: Connection,
    attempted: Int,
    lastIds: Set<String>,
    cause: SQLException,
    jdbcRollback: Boolean,
    postRollback: () -> Unit = {},
): ExecutionTrace {
    val (rolledBack, sideEffects) = try {
        if (jdbcRollback) {
            conn.rollback()
        } else {
            conn.createStatement().use { it.execute("ROLLBACK;") }
        }
        true to false
    } catch (_: SQLException) {
        false to true
    }
    if (rolledBack) {
        postRollback()
    }
    return ExecutionTrace(
        executionStarted = true,
        executionCompleted = true,
        statementsAttempted = attempted,
        lastStatementOperationIds = lastIds,
        transactionRolledBack = rolledBack,
        sideEffectsPossible = sideEffects,
        executionError = cause.message ?: cause::class.simpleName,
    )
}
