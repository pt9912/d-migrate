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
 * 2. Open a Hikari pool and dispatch on the statement stream's
 *    transaction-ownership model (see [runAll] below).
 *
 * Two transaction-ownership models are supported, dispatched per-
 * stream by inspecting the SQL content for an explicit `BEGIN`
 * marker:
 *
 * - **Runner-owned tx (default)** — for PG, MySQL, and SQLite-
 *   direct streams. `autoCommit = false`, all statements run inside
 *   one outer JDBC transaction, `conn.commit()` on success,
 *   `conn.rollback()` on `SQLException`.
 *
 * - **SQL-stream-owned tx** — for SQLite-rebuild streams (`migrate
 *   --execute` ones rendered by `SqliteRebuildRenderer`; `rollback
 *   --execute` ones reconstructed by `SchemaRollbackRunner.splitArtefactBody`
 *   from the artefact body). Detected by the presence of an
 *   explicit `BEGIN ` statement in the stream. Setting
 *   `autoCommit = false` here would race xerial-sqlite's implicit-
 *   tx-on-first-statement and produce
 *   `cannot start a transaction within a transaction` (F.4 was the
 *   forcing function). For these streams we keep `autoCommit = true`
 *   and never call `conn.commit()` — the stream's own COMMIT closes
 *   the rebuild, surrounding PRAGMAs run outside any tx.
 *
 * The detection key is the SQL itself rather than the
 * [MigrationDdlStatement.phase] tag because the rollback path
 * cannot reconstruct per-statement phases from the artefact body.
 * Long-term: an explicit `transactionScope` field on
 * [MigrationDdlStatement] (and round-tripped through the artefact)
 * would let renderers declare ownership directly.
 *
 * Error mapping (runner-owned tx):
 *
 * - first statement fails before any execute → `transactionRolledBack=true`,
 *   `sideEffectsPossible=false`.
 * - failure after at least one statement succeeded → rollback is
 *   attempted; success of the rollback flips `transactionRolledBack=true`,
 *   else `sideEffectsPossible=true`.
 *
 * Error mapping (stream-owned tx): on `SQLException` an explicit
 * `ROLLBACK;` is sent; success → `transactionRolledBack=true`, else
 * `sideEffectsPossible=true`.
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

    private fun runAll(conn: java.sql.Connection, statements: List<MigrationDdlStatement>): ExecutionTrace =
        if (MigrationStreamClassifier.streamOwnsTransaction(statements)) {
            runStreamOwnedTransaction(conn, statements)
        } else {
            runRunnerOwnedTransaction(conn, statements)
        }

    @Suppress("ReturnCount")
    private fun runRunnerOwnedTransaction(
        conn: java.sql.Connection,
        statements: List<MigrationDdlStatement>,
    ): ExecutionTrace {
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
            return rollbackTrace(conn, attempted, lastIds, e, jdbcRollback = true)
        }
    }

    @Suppress("ReturnCount")
    private fun runStreamOwnedTransaction(
        conn: java.sql.Connection,
        statements: List<MigrationDdlStatement>,
    ): ExecutionTrace {
        // The stream contains its OWN `BEGIN IMMEDIATE` / `COMMIT`. Keep
        // autoCommit=true so JDBC doesn't open a competing implicit tx;
        // never call conn.commit() — the stream's COMMIT did it.
        conn.autoCommit = true
        var attempted = 0
        var lastIds: Set<String> = emptySet()
        // Phase H.3b: runner-hook state for SQLite-rebuild PRAGMA save/restore.
        // **Per-statement fresh JDBC Statement**: xerial-sqlite finalises
        // a `Statement` after its ResultSet is closed (the hook's
        // `PRAGMA foreign_keys;` query consumes one). Reusing the outer
        // Statement across the loop then throws "The prepared statement
        // has been finalized" on the next execute. A fresh Statement per
        // iteration sidesteps the lifecycle quirk; the per-DDL cost is
        // dominated by network/IO and irrelevant against the test budget.
        val hookState = RunnerHookHandler.State()
        try {
            for (stmt in statements) {
                lastIds = stmt.operationIds
                attempted++
                conn.createStatement().use { jdbcStmt ->
                    RunnerHookHandler.executeOrApply(jdbcStmt, stmt.sql, hookState)
                }
            }
            return ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastIds,
            )
        } catch (e: SQLException) {
            return rollbackTrace(conn, attempted, lastIds, e, jdbcRollback = false)
        }
    }

    // Phase H.3b runner-hook parser + applier lives in shared
    // [RunnerHookHandler] (hexagon:application) so the test-fixture
    // variant in MigrationExecutorTestSupport applies the same hook
    // contract instead of running the markers through jdbcStmt.execute
    // as raw SQL comments.

    private fun rollbackTrace(
        conn: java.sql.Connection,
        attempted: Int,
        lastIds: Set<String>,
        cause: SQLException,
        jdbcRollback: Boolean,
    ): ExecutionTrace {
        val (rolledBack, sideEffects) = try {
            if (jdbcRollback) {
                conn.rollback()
            } else {
                // Stream-owned tx: send an explicit ROLLBACK; statement so
                // we don't depend on the driver's view of the JDBC tx state
                // (it is `autoCommit=true` here).
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
}
