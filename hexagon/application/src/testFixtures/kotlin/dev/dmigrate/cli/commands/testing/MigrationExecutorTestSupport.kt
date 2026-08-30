package dev.dmigrate.cli.commands.testing

import dev.dmigrate.cli.commands.ExecutionTrace
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.migration.ExecutionRecoverability
import dev.dmigrate.driver.migration.JdbcRunnerHookHandler as RunnerHookHandler
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.MigrationStreamClassifier
import dev.dmigrate.driver.migration.StreamExecutionModel
import dev.dmigrate.driver.migration.preserve.AtomicPreserveSegment
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import dev.dmigrate.driver.migration.preserve.ExecutableSegment
import dev.dmigrate.driver.migration.preserve.NoTransactionSegment
import dev.dmigrate.driver.migration.preserve.PlainSqlSegment
import java.sql.Connection
import java.sql.SQLException

/**
 * Test-only Unwrap des neutralen [DatabaseConnection] auf die JDBC-[Connection].
 * Die Fixture bleibt bei Reflexion, damit die Live-ITs ihren bereits
 * konfigurierten Pool weiterverwenden können.
 */
private fun DatabaseConnection.jdbc(): Connection =
    javaClass.getMethod("getConnection").invoke(this) as Connection

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
    return pool.borrow().jdbc().use { conn ->
        // Dasselbe Modell wie im produktiven Ausfuehrer, aus derselben Quelle:
        // `MigrationStreamClassifier.executionModel`. Eine eigene Fallunterscheidung
        // hier hat schon einmal `NO_TRANSACTION` uebersehen, und der Live-Test
        // fuhr die Anweisung dann in der Transaktion, die sie ablehnt.
        when (MigrationStreamClassifier.executionModel(statements)) {
            StreamExecutionModel.NO_TRANSACTION -> runWithoutTransaction(conn, statements)
            StreamExecutionModel.STREAM_TRANSACTION -> runStreamOwnedTransaction(conn, statements)
            StreamExecutionModel.RUNNER_TRANSACTION -> runRunnerOwnedTransaction(conn, statements)
        }
    }
}

/**
 * Anweisungen, die die Datenbank in einer offenen Transaktion ablehnt. Kein
 * Rueckrollversuch: es gibt nichts zurueckzurollen, und ein `ROLLBACK` belöge
 * den Bericht.
 */
@Suppress("ReturnCount")
private fun runWithoutTransaction(
    conn: Connection,
    statements: List<MigrationDdlStatement>,
): ExecutionTrace {
    conn.autoCommit = true
    var attempted = 0
    var lastIds: Set<String> = emptySet()
    return try {
        for (s in statements) {
            lastIds = s.operationIds
            attempted++
            conn.createStatement().use { jdbcStmt -> jdbcStmt.execute(s.sql) }
        }
        ExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = attempted,
            lastStatementOperationIds = lastIds,
        )
    } catch (e: SQLException) {
        ExecutionTrace(
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
    val hookState = RunnerHookHandler.State()
    return try {
        // Per-statement fresh Statement — see JdbcMigrationExecutor for
        // the xerial-sqlite Statement-finalisation rationale.
        for (s in statements) {
            lastIds = s.operationIds
            attempted++
            conn.createStatement().use { jdbcStmt ->
                RunnerHookHandler.executeOrApply(jdbcStmt, s.sql, hookState)
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
    hookState: RunnerHookHandler.State,
) {
    if (hookState.savedSqliteForeignKeysPragma == null) return
    try {
        conn.createStatement().use { stmt ->
            RunnerHookHandler.apply(stmt, "restore-fk-state", hookState)
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

/**
 * Atomic-Preserve Phase C.5 (2026-06-01) test-fixture mirror of
 * `SegmentAwareMigrationExecutor.execute` for the live-IT modules
 * (`:test:integration-postgresql` / `-mysql` / `-sqlite`).
 *
 * Why this duplicate exists: same architectural constraint as
 * [executeAgainstPool] — `SegmentAwareMigrationExecutor` is
 * `internal` to `:adapters:driving:cli` (CLI ↔ application boundary).
 * IT modules already have a [ConnectionPool] against their
 * Testcontainer / file-backed DB and only need the **routing** side
 * of the contract: PlainSqlSegment → [executeAgainstPool],
 * AtomicPreserveSegment → caller-supplied [atomicExecutor] on a
 * pool-borrowed connection.
 *
 * Contract MUST stay byte-identical to
 * `SegmentAwareMigrationExecutor.execute`. If the production routing
 * changes (e.g. multi-AtomicPreserveSegment per plan in Phase D),
 * mirror the change here so the live-IT continues to exercise the
 * same path.
 *
 * [atomicExecutor] is the dialect-specific Phase-B
 * [AtomicSequencePreserveExecutor] the IT module instantiates
 * (`PostgresAtomicSequencePreserveExecutor()`, etc.). The helper does
 * NOT dispatch by dialect — that is the IT's responsibility, because
 * the IT modules don't depend on each other's adapter modules.
 */
fun executeSegmentsAgainstPool(
    pool: ConnectionPool,
    segments: List<ExecutableSegment>,
    atomicExecutor: AtomicSequencePreserveExecutor,
    lockTimeoutMillis: Long = 5_000L,
): ExecutionTrace {
    if (segments.isEmpty()) {
        return ExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = 0,
        )
    }
    var attempted = 0
    var completedSegments = 0
    var lastOpIds: Set<String> = emptySet()
    for (segment in segments) {
        val segmentTrace = when (segment) {
            // Wie in der Produktion: derselbe Ausfuehrer, die Transaktionsform
            // entscheidet der Scope der Anweisungen.
            is PlainSqlSegment, is NoTransactionSegment -> executeAgainstPool(pool, segment.statements)
            is AtomicPreserveSegment -> runAtomicSegmentAgainstPool(
                pool = pool,
                segment = segment,
                atomicExecutor = atomicExecutor,
                lockTimeoutMillis = lockTimeoutMillis,
            )
        }
        attempted += segmentTrace.statementsAttempted
        if (segmentTrace.lastStatementOperationIds.isNotEmpty()) {
            lastOpIds = segmentTrace.lastStatementOperationIds
        }
        if (segmentTrace.transactionRolledBack || segmentTrace.executionError != null) {
            return ExecutionTrace(
                executionStarted = true,
                executionCompleted = false,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastOpIds,
                transactionRolledBack = segmentTrace.transactionRolledBack,
                // Ein frueherer Abschnitt hat committet und bleibt stehen.
                sideEffectsPossible = segmentTrace.sideEffectsPossible || completedSegments > 0,
                executionError = segmentTrace.executionError,
                recoverability = segmentTrace.recoverability,
            )
        }
        completedSegments++
    }
    return ExecutionTrace(
        executionStarted = true,
        executionCompleted = true,
        statementsAttempted = attempted,
        lastStatementOperationIds = lastOpIds,
    )
}

private fun runAtomicSegmentAgainstPool(
    pool: ConnectionPool,
    segment: AtomicPreserveSegment,
    atomicExecutor: AtomicSequencePreserveExecutor,
    lockTimeoutMillis: Long,
): ExecutionTrace {
    val followUpIds: Set<String> = segment.batch.internalFollowUpIds.toSet()
    val protectedStatements = segment.statements.filter { stmt ->
        stmt.operationIds.none { it in followUpIds }
    }
    val executeProtectedOps: (DatabaseConnection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult =
        { databaseConnection, _ ->
            val connection = databaseConnection.jdbc()
            for (stmt in protectedStatements) {
                connection.createStatement().use { it.execute(stmt.sql) }
            }
            AtomicProtectedExecutionResult.Succeeded(statementsExecuted = protectedStatements.size)
        }
    val result = pool.borrow().use { handle ->
        atomicExecutor.execute(
            connection = handle,
            batch = segment.batch,
            lockTimeoutMillis = lockTimeoutMillis,
            executeProtectedOperations = executeProtectedOps,
        )
    }
    return mapAtomicResultToTrace(result, segment)
}

private fun mapAtomicResultToTrace(
    result: AtomicSequencePreserveResult,
    segment: AtomicPreserveSegment,
): ExecutionTrace = when (result) {
    is AtomicSequencePreserveResult.Applied -> ExecutionTrace(
        executionStarted = true,
        executionCompleted = true,
        statementsAttempted = segment.statements.size,
        lastStatementOperationIds = segment.statements.lastOrNull()?.operationIds ?: emptySet(),
    )
    is AtomicSequencePreserveResult.NotFound -> ExecutionTrace(
        executionStarted = true,
        executionCompleted = false,
        statementsAttempted = 0,
        transactionRolledBack = true,
        executionError = "Atomic preserve aborted — sequence(s) not found: " +
            result.refs.joinToString(", ") { it.name },
        recoverability = ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED,
    )
    is AtomicSequencePreserveResult.LockTimeout -> ExecutionTrace(
        executionStarted = true,
        executionCompleted = false,
        statementsAttempted = 0,
        transactionRolledBack = true,
        executionError = "SEQUENCE_PRESERVE_LOCK_TIMEOUT for: " +
            result.refs.joinToString(", ") { it.name },
        recoverability = ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED,
    )
    is AtomicSequencePreserveResult.Failed -> ExecutionTrace(
        executionStarted = true,
        executionCompleted = false,
        statementsAttempted = 0,
        transactionRolledBack = true,
        executionError = "Atomic preserve failed for ${result.ref.name}: " +
            (result.cause.message ?: result.cause::class.java.simpleName),
        recoverability = ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED,
    )
    // Service-Mode Sub-Slice E (2026-06-02): test-fixture mirror of
    // SegmentAwareMigrationExecutor's Cancelled branch.
    is AtomicSequencePreserveResult.Cancelled -> ExecutionTrace(
        executionStarted = true,
        executionCompleted = false,
        statementsAttempted = 0,
        transactionRolledBack = true,
        executionError = "Atomic preserve cancelled" +
            (result.reason?.let { " ($it)" } ?: "") +
            ": " + result.refs.joinToString(", ") { it.name },
        recoverability = ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED,
    )
}
