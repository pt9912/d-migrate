package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import java.sql.Connection
import java.sql.SQLException

/**
 * Atomic-Preserve Phase B.4 (2026-05-31): SQLite implementation of
 * [AtomicSequencePreserveExecutor]. Lock strategy per plan §4.3:
 *
 * - `BEGIN IMMEDIATE` acquires the SQLite database's `RESERVED`
 *   lock on transaction start. The lock blocks every concurrent
 *   writer — including the `dmg_nextval`-trigger's `UPDATE` on
 *   `dmg_sequences` — until COMMIT. Readers stay unblocked
 *   (RESERVED only conflicts with other write attempts).
 * - `PRAGMA busy_timeout = <ms>` configures how long SQLite waits
 *   for the lock before returning `SQLITE_BUSY`. The executor
 *   reads the prior value, applies the override, and restores it
 *   in `finally` so the next pool borrow inherits clean state
 *   (Plan §6 Risk 6 Session-Timeout-Leak mitigation).
 * - `BEGIN IMMEDIATE` and `COMMIT`/`ROLLBACK` are issued as raw
 *   SQL with `autoCommit = true` at the JDBC level — the xerial
 *   driver's default `autoCommit = false` would issue a DEFERRED
 *   `BEGIN` that doesn't acquire the lock up front. Direct SQL
 *   keeps the IMMEDIATE-semantics explicit.
 * - SQLite error code `SQLITE_BUSY` (5) on `BEGIN IMMEDIATE` →
 *   [AtomicSequencePreserveResult.LockTimeout] for the whole
 *   batch (the lock is database-wide; the partial-subset semantics
 *   from PG/MySQL don't apply).
 * - Probe outcomes route per the existing
 *   [SqliteSequenceCurrentValueProbe] contract: `NotFound` →
 *   batch rollback + [AtomicSequencePreserveResult.NotFound].
 *
 * The executor owns the connection's autocommit and the explicit
 * BEGIN/COMMIT pair — callers MUST supply a single-owner
 * connection that is not already inside a higher-level
 * transaction.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`.
 */
class SqliteAtomicSequencePreserveExecutor : AtomicSequencePreserveExecutor {

    override fun execute(
        connection: Connection,
        batch: AtomicSequencePreserveBatch,
        lockTimeoutMillis: Long,
        cancellationToken: CancellationToken,
        executeProtectedOperations: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
    ): AtomicSequencePreserveResult {
        require(lockTimeoutMillis > 0) {
            "lockTimeoutMillis must be > 0, was $lockTimeoutMillis"
        }
        // SQLite lookupKey is just `ref.name` (no schema column in
        // dmg_sequences). The compareBy on name + empty-schema
        // keeps the call shape symmetric with PG/MySQL — plan §2 (3)
        // deadlock-diamond avoidance is moot here because SQLite's
        // lock is database-wide, but the deterministic order keeps
        // the test surface stable.
        val sortedRequests = batch.requests.sortedWith(
            compareBy({ it.sequenceRef.name }, { it.sequenceRef.schema.orEmpty() }),
        )
        val sortedRefs = sortedRequests.map { it.sequenceRef }
        if (sortedRequests.isEmpty()) {
            return AtomicSequencePreserveResult.Applied(emptyList())
        }
        AtomicSequencePreserveExecutor.requireOwnedConnection(connection)

        // Service-Mode Sub-Slice E checkpoint 1 (pre-BEGIN IMMEDIATE):
        // no transaction or `PRAGMA busy_timeout` override yet;
        // short-circuit without touching the connection.
        if (cancellationToken.isCancellationRequested) {
            return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
        }

        val previousAutoCommit = connection.autoCommit
        val previousBusyTimeout = readBusyTimeout(connection)
        connection.autoCommit = true
        applyBusyTimeout(connection, lockTimeoutMillis)
        try {
            try {
                connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            } catch (e: SQLException) {
                return if (e.errorCode == SQLITE_BUSY) {
                    AtomicSequencePreserveResult.LockTimeout(sortedRefs)
                } else {
                    AtomicSequencePreserveResult.Failed(sortedRefs.first(), e)
                }
            }
            return runUnderLock(connection, sortedRequests, sortedRefs, batch, executeProtectedOperations, cancellationToken)
        } finally {
            runCatching { applyBusyTimeout(connection, previousBusyTimeout) }
            runCatching { connection.autoCommit = previousAutoCommit }
        }
    }

    private fun runUnderLock(
        connection: Connection,
        sortedRequests: List<AtomicSequencePreserveRequest>,
        sortedRefs: List<SequenceObjectRef>,
        batch: AtomicSequencePreserveBatch,
        executeProtectedOperations: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
        cancellationToken: CancellationToken,
    ): AtomicSequencePreserveResult {
        val probeResults = mutableMapOf<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>()
        for (request in sortedRequests) {
            probe(connection, request.sequenceRef, probeResults)?.let { earlyExit ->
                rollbackQuietly(connection)
                return earlyExit
            }
        }
        // Service-Mode Sub-Slice E checkpoint 2 (post-probe,
        // pre-protected-operations): rollback releases the
        // database-wide RESERVED lock acquired by BEGIN IMMEDIATE so
        // other writers can proceed.
        if (cancellationToken.isCancellationRequested) {
            rollbackQuietly(connection)
            return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
        }
        try {
            executeProtectedOperations(connection, batch.protectedOperationIds)
        } catch (e: Throwable) {
            rollbackQuietly(connection)
            return AtomicSequencePreserveResult.Failed(sortedRefs.last(), e)
        }
        // Service-Mode Sub-Slice E checkpoint 3 (post-protected,
        // pre-restore): rollback undoes the protected-operation
        // statements so `dmg_sequences` is unchanged after a
        // caller-observed cancellation.
        if (cancellationToken.isCancellationRequested) {
            rollbackQuietly(connection)
            return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
        }
        for (request in sortedRequests) {
            val probe = probeResults.getValue(request.sequenceRef)
            executeRestore(connection, request, probe)?.let { earlyExit ->
                rollbackQuietly(connection)
                return earlyExit
            }
        }
        return try {
            connection.createStatement().use { it.execute("COMMIT") }
            AtomicSequencePreserveResult.Applied(sortedRefs)
        } catch (e: SQLException) {
            rollbackQuietly(connection)
            AtomicSequencePreserveResult.Failed(sortedRefs.first(), e)
        }
    }

    private fun probe(
        connection: Connection,
        ref: SequenceObjectRef,
        probeResults: MutableMap<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>,
    ): AtomicSequencePreserveResult? {
        val result = try {
            SqliteSequenceCurrentValueProbe.probe(connection, ref)
        } catch (e: Throwable) {
            return AtomicSequencePreserveResult.Failed(ref, e)
        }
        return when (result) {
            is SequenceCurrentValueProbeResult.Read -> {
                probeResults[ref] = result
                null
            }
            is SequenceCurrentValueProbeResult.NotFound ->
                AtomicSequencePreserveResult.NotFound(listOf(ref))
            is SequenceCurrentValueProbeResult.Failed ->
                AtomicSequencePreserveResult.Failed(
                    ref,
                    IllegalStateException("SQLite probe failed: ${result.code} ${result.message}"),
                )
            is SequenceCurrentValueProbeResult.NotApplicable ->
                AtomicSequencePreserveResult.Failed(
                    ref,
                    IllegalStateException("SQLite probe returned NotApplicable for $ref — unreachable"),
                )
        }
    }

    private fun executeRestore(
        connection: Connection,
        request: AtomicSequencePreserveRequest,
        probe: SequenceCurrentValueProbeResult.Read,
    ): AtomicSequencePreserveResult? {
        val statements = try {
            request.renderRestore(probe)
        } catch (e: Throwable) {
            return AtomicSequencePreserveResult.Failed(request.sequenceRef, e)
        }
        return try {
            connection.createStatement().use { stmt ->
                for (sql in statements) {
                    stmt.execute(sql)
                }
            }
            null
        } catch (e: SQLException) {
            AtomicSequencePreserveResult.Failed(request.sequenceRef, e)
        }
    }

    private fun rollbackQuietly(connection: Connection) {
        runCatching {
            connection.createStatement().use { it.execute("ROLLBACK") }
        }
    }

    private fun readBusyTimeout(connection: Connection): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA busy_timeout").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun applyBusyTimeout(connection: Connection, millis: Long) {
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA busy_timeout = $millis")
        }
    }

    companion object {
        /** SQLite extended error code `SQLITE_BUSY` (driver returns base code 5). */
        const val SQLITE_BUSY: Int = 5
    }
}
