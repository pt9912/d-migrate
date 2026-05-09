package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.migration.MigrationDdlStatement
import java.nio.file.Path
import java.sql.SQLException

/**
 * JDBC-backed implementation of [ExecutorFn] for the
 * [SchemaMigrateRunner] / [SchemaRollbackRunner] pipeline.
 *
 * Connection lifecycle:
 *
 * 1. Resolve the operand string via [NamedConnectionResolver]
 *    (config-aware) to a JDBC URL. Wraps non-config errors in
 *    [CompareConfigException] so the runner exits 7.
 * 2. Open a Hikari pool, set `autoCommit = false`, run all
 *    statements through one [java.sql.Statement.execute] each, and
 *    commit on the last. On any failure, roll back and surface the
 *    SQL message into [ExecutionTrace.executionError].
 * 3. SQLite specifics: the rebuild pipeline emits its own
 *    `BEGIN IMMEDIATE;` / `COMMIT;` markers; we still set
 *    `autoCommit = false` to keep xerial-sqlite's wrapping out of
 *    the way. The PRAGMA / BEGIN / COMMIT statements are sent
 *    through normal `execute()` calls — SQLite handles them
 *    inline.
 *
 * Error mapping:
 *
 * - first statement fails before any execute → `transactionRolledBack=true`,
 *   `sideEffectsPossible=false`.
 * - failure after at least one statement succeeded → rollback is
 *   attempted; success of the rollback flips `transactionRolledBack=true`,
 *   else `sideEffectsPossible=true`.
 */
internal object JdbcMigrationExecutor {

    @Suppress("ReturnCount")
    fun execute(
        target: CompareOperand.Database,
        statements: List<MigrationDdlStatement>,
        configPath: Path?,
    ): ExecutionTrace {
        if (statements.isEmpty()) {
            return ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = 0,
            )
        }
        val url = try {
            NamedConnectionResolver(configPathFromCli = configPath).resolve(target.source)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
        val config = try {
            ConnectionUrlParser.parse(url)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "URL parse failed", e)
        }
        val pool = HikariConnectionPoolFactory.create(config)
        return pool.use { p ->
            p.borrow().use { conn ->
                runAll(conn, statements)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun runAll(conn: java.sql.Connection, statements: List<MigrationDdlStatement>): ExecutionTrace {
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
            return ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastIds,
            )
        } catch (e: SQLException) {
            return rollbackTrace(conn, attempted, lastIds, e)
        }
    }

    private fun rollbackTrace(
        conn: java.sql.Connection,
        attempted: Int,
        lastIds: Set<String>,
        cause: SQLException,
    ): ExecutionTrace {
        val (rolledBack, sideEffects) = try {
            conn.rollback()
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
}
