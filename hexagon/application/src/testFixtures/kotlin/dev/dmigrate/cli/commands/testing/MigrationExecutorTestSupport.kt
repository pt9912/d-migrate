package dev.dmigrate.cli.commands.testing

import dev.dmigrate.cli.commands.ExecutionTrace
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.migration.MigrationDdlStatement
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
 * Contract is **byte-identical** to `JdbcMigrationExecutor.runAll`:
 *
 * 1. Empty statement list → `executionStarted = true,
 *    executionCompleted = true`, no rows attempted.
 * 2. Borrow one connection from the pool, set
 *    `autoCommit = false`, run each statement through a single
 *    `Statement.execute` per call, then `commit()`.
 * 3. On [SQLException], attempt `rollback()`; success flips
 *    `transactionRolledBack = true`, failure flips
 *    `sideEffectsPossible = true`. The trace carries the
 *    statement's `operationIds` for the LAST attempted statement.
 *
 * If you fix a bug here, fix it in `JdbcMigrationExecutor.runAll`
 * too (and vice versa). A drift would silently make the smoke tests
 * lie about what production does.
 */
@Suppress("ReturnCount")
fun executeAgainstPool(
    pool: ConnectionPool,
    statements: List<MigrationDdlStatement>,
): ExecutionTrace {
    if (statements.isEmpty()) {
        return ExecutionTrace(executionStarted = true, executionCompleted = true)
    }
    return pool.borrow().use { conn ->
        conn.autoCommit = false
        var attempted = 0
        var lastIds: Set<String> = emptySet()
        try {
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
            val (rolledBack, sideEffects) = try {
                conn.rollback()
                true to false
            } catch (_: SQLException) {
                false to true
            }
            ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastIds,
                transactionRolledBack = rolledBack,
                sideEffectsPossible = sideEffects,
                executionError = e.message ?: e::class.simpleName,
            )
        }
    }
}
