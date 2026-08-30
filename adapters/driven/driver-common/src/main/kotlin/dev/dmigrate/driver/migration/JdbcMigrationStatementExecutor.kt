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

    /**
     * Fuehrt einen Strom aus, der beide Ausfuehrungsmodelle mischen darf.
     *
     * Getrennt wird hier, nicht beim Aufrufer: `schema migrate` schneidet den
     * Plan zwar selbst in Abschnitte, `schema rollback` reicht die Anweisungen
     * aber flach durch — und ein Rollback-Artefakt einer Volltext-Migration
     * traegt genau die Anweisungen, die in einer offenen Transaktion scheitern.
     * Was hier ankommt, laeuft deshalb Lauf fuer Lauf, jeder mit dem Modell,
     * das seine Anweisungen verlangen.
     *
     * Jeder Lauf bekommt eine eigene Verbindung: eine Transaktion darf nicht
     * offen bleiben, waehrend daneben mit `autoCommit` gearbeitet wird.
     */
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
        val runs = splitByExecutionModel(statements)
        var attempted = 0
        var lastIds: Set<String> = emptySet()
        var committedRuns = 0
        for (run in runs) {
            val trace = pool.borrow().asJdbc().use { conn -> runAll(conn, run) }
            attempted += trace.statementsAttempted
            if (trace.lastStatementOperationIds.isNotEmpty()) lastIds = trace.lastStatementOperationIds
            if (trace.executionError != null || trace.transactionRolledBack) {
                return trace.copy(
                    statementsAttempted = attempted,
                    lastStatementOperationIds = lastIds,
                    // Was ein frueherer Lauf festgeschrieben hat, nimmt der
                    // Rueckbau dieses Laufs nicht mit.
                    sideEffectsPossible = trace.sideEffectsPossible || committedRuns > 0,
                )
            }
            committedRuns++
        }
        return MigrationExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = attempted,
            lastStatementOperationIds = lastIds,
        )
    }

    /** Aufeinanderfolgende Anweisungen desselben Ausfuehrungsmodells, in Reihenfolge. */
    private fun splitByExecutionModel(
        statements: List<MigrationDdlStatement>,
    ): List<List<MigrationDdlStatement>> {
        val runs = mutableListOf<List<MigrationDdlStatement>>()
        var current = mutableListOf<MigrationDdlStatement>()
        var currentOutside = statements.first().transactionScope == TransactionScope.NO_TRANSACTION
        for (statement in statements) {
            val outside = statement.transactionScope == TransactionScope.NO_TRANSACTION
            if (outside != currentOutside && current.isNotEmpty()) {
                runs += current.toList()
                current = mutableListOf()
            }
            currentOutside = outside
            current += statement
        }
        if (current.isNotEmpty()) runs += current.toList()
        return runs
    }

    fun runAll(conn: Connection, statements: List<MigrationDdlStatement>): MigrationExecutionTrace =
        when (MigrationStreamClassifier.executionModel(statements)) {
            StreamExecutionModel.NO_TRANSACTION -> runWithoutTransaction(conn, statements)
            StreamExecutionModel.STREAM_TRANSACTION -> runStreamOwnedTransaction(conn, statements)
            StreamExecutionModel.RUNNER_TRANSACTION -> runRunnerOwnedTransaction(conn, statements)
        }

    /**
     * Anweisungen, die die Datenbank in einer offenen Transaktion ablehnt —
     * SQL Servers Volltext-DDL, PostgreSQLs `CREATE INDEX CONCURRENTLY`.
     *
     * Es gibt hier nichts zurückzurollen, und deshalb wird es auch nicht
     * versucht: `autoCommit` steht an, jede Anweisung gilt sofort. Ein
     * Fehlschlag lässt stehen, was vorher lief, und meldet das als
     * Seiteneffekt — die Alternative wäre ein `ROLLBACK`, das nichts
     * rückgängig macht und den Bericht belöge.
     *
     * Die Aufrufer bekommen einen einheitlichen Strom: [segmentForExecute]
     * trennt am Scope-Wechsel, sodass hier nie gemischte Anweisungen ankommen.
     */
    @Suppress("ReturnCount")
    private fun runWithoutTransaction(
        conn: Connection,
        statements: List<MigrationDdlStatement>,
    ): MigrationExecutionTrace {
        conn.autoCommit = true
        var attempted = 0
        var lastIds: Set<String> = emptySet()
        try {
            for (stmt in statements) {
                lastIds = stmt.operationIds
                attempted++
                conn.createStatement().use { jdbcStmt -> jdbcStmt.execute(stmt.sql) }
            }
            return MigrationExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastIds,
            )
        } catch (e: SQLException) {
            return MigrationExecutionTrace(
                executionStarted = true,
                executionCompleted = false,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastIds,
                transactionRolledBack = false,
                sideEffectsPossible = true,
                executionError = e.message ?: e.toString(),
            )
        }
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
