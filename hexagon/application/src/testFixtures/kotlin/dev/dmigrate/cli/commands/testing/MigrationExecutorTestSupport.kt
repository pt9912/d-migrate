package dev.dmigrate.cli.commands.testing

import dev.dmigrate.cli.commands.ExecutionTrace
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
        if (statements.any { isExplicitBeginStatement(it.sql) }) {
            runStreamOwnedTransaction(conn, statements)
        } else {
            runRunnerOwnedTransaction(conn, statements)
        }
    }
}

private fun isExplicitBeginStatement(sql: String): Boolean {
    val trimmed = sql.trimStart().uppercase()
    return trimmed.startsWith("BEGIN;") ||
        trimmed.startsWith("BEGIN ") ||
        trimmed == "BEGIN"
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
    return try {
        conn.createStatement().use { jdbcStmt ->
            for (s in statements) {
                lastIds = s.operationIds
                attempted++
                jdbcStmt.execute(s.sql)
            }
        }
        ExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = attempted,
            lastStatementOperationIds = lastIds,
        )
    } catch (e: SQLException) {
        rollbackTrace(conn, attempted, lastIds, e, jdbcRollback = false)
    }
}

private fun rollbackTrace(
    conn: Connection,
    attempted: Int,
    lastIds: Set<String>,
    cause: SQLException,
    jdbcRollback: Boolean,
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
