package dev.dmigrate.driver.migration

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import java.sql.Connection
import java.sql.SQLException

/**
 * JDBC-backed executor for rendered migration statements.
 *
 * The driving CLI owns target resolution and pool construction; this driven
 * adapter owns JDBC unwrapping, transaction handling, statement execution,
 * rollback mapping, and runner-hook side effects.
 */
object JdbcMigrationStatementExecutor {

    fun execute(
        pool: ConnectionPool,
        statements: List<MigrationDdlStatement>,
    ): MigrationExecutionTrace {
        if (statements.isEmpty()) {
            return MigrationExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = 0,
            )
        }
        return pool.borrow().asJdbc().use { conn ->
            runAll(conn, statements)
        }
    }

    fun runAll(conn: Connection, statements: List<MigrationDdlStatement>): MigrationExecutionTrace =
        if (MigrationStreamClassifier.streamOwnsTransaction(statements)) {
            runStreamOwnedTransaction(conn, statements)
        } else {
            runRunnerOwnedTransaction(conn, statements)
        }

    @Suppress("ReturnCount")
    private fun runRunnerOwnedTransaction(
        conn: Connection,
        statements: List<MigrationDdlStatement>,
    ): MigrationExecutionTrace {
        conn.autoCommit = false
        var attempted = 0
        var lastIds: Set<String> = emptySet()
        try {
            conn.createStatement().use { jdbcStmt ->
                for (stmt in statements) {
                    lastIds = stmt.operationIds
                    attempted++
                    jdbcStmt.execute(stmt.sql)
                }
            }
            conn.commit()
            return MigrationExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastIds,
            )
        } catch (e: SQLException) {
            return rollbackTrace(conn, attempted, lastIds, e, jdbcRollback = true)
        }
    }

    @Suppress("ReturnCount")
    private fun runStreamOwnedTransaction(
        conn: Connection,
        statements: List<MigrationDdlStatement>,
    ): MigrationExecutionTrace {
        conn.autoCommit = true
        var attempted = 0
        var lastIds: Set<String> = emptySet()
        val hookState = JdbcRunnerHookHandler.State()
        try {
            for (stmt in statements) {
                lastIds = stmt.operationIds
                attempted++
                conn.createStatement().use { jdbcStmt ->
                    JdbcRunnerHookHandler.executeOrApply(jdbcStmt, stmt.sql, hookState)
                }
            }
            return MigrationExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastIds,
            )
        } catch (e: SQLException) {
            return rollbackTrace(
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
        hookState: JdbcRunnerHookHandler.State,
    ) {
        if (hookState.savedSqliteForeignKeysPragma == null) return
        try {
            conn.createStatement().use { stmt ->
                JdbcRunnerHookHandler.apply(stmt, "restore-fk-state", hookState)
            }
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") _: Exception) {
            // Best-effort restore. The primary SQLException already drives the failure trace.
        }
    }

    private fun rollbackTrace(
        conn: Connection,
        attempted: Int,
        lastIds: Set<String>,
        cause: SQLException,
        jdbcRollback: Boolean,
        postRollback: () -> Unit = {},
    ): MigrationExecutionTrace {
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
        return MigrationExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = attempted,
            lastStatementOperationIds = lastIds,
            transactionRolledBack = rolledBack,
            sideEffectsPossible = sideEffects,
            executionError = cause.message ?: cause::class.simpleName,
        )
    }
}
