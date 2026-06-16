package dev.dmigrate.driver.mysql

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.MysqlSequenceSupportNaming
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
 * Atomic-Preserve Phase B.3 (2026-05-31): MySQL implementation of
 * [AtomicSequencePreserveExecutor]. Lock strategy per plan §4.2:
 *
 * - `SET SESSION innodb_lock_wait_timeout = <ceil(ms/1000)>` before
 *   the transaction. MySQL's per-row lock-wait budget is
 *   second-grained; the executor reads the prior value, applies the
 *   override, and restores it in `finally` so the next borrow from
 *   the connection pool starts clean. Minimum 1 s — anything finer
 *   is mapped up.
 * - `SELECT next_value, managed_by, format_version FROM
 *   \`dmg_sequences\` WHERE \`name\` = ? FOR UPDATE` takes the
 *   InnoDB row-level X-lock AND reads the probe data in one
 *   round-trip. Unlike the read-only probe in
 *   [MysqlSequenceCurrentValueProbe], the executor's `FOR UPDATE`
 *   serialises against any other writer touching the same row —
 *   including the helper-table emulation's `dmg_nextval` UPDATE
 *   path. App-side `nextval` callers waiting on the row will
 *   either be served after our restore commits OR will themselves
 *   time out via `innodb_lock_wait_timeout`.
 * - SQLState `HY000` errorCode `1205` (ER_LOCK_WAIT_TIMEOUT) +
 *   errorCode `1213` (ER_LOCK_DEADLOCK) both surface as
 *   [AtomicSequencePreserveResult.LockTimeout]. Deadlock is treated
 *   as a lock-timeout-equivalent because MySQL's victim selection
 *   rolls back our transaction, leaving the runner in the same
 *   "could not acquire" state.
 * - errorCode `1146` (ER_NO_SUCH_TABLE) — the `dmg_sequences`
 *   helper table is missing entirely → [AtomicSequencePreserveResult.NotFound].
 * - Empty result-set (no row with `name = ?`) → NotFound.
 * - `managed_by` outside [MysqlSequenceSupportNaming.SUPPORTED_MANAGED_BY]
 *   or `format_version` outside [MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS]
 *   → [AtomicSequencePreserveResult.Failed]; the operator either
 *   reconciles the helper table or backs out of preserve.
 *
 * The executor owns the connection's autocommit + commit / rollback
 * — callers MUST supply a single-owner connection that is not
 * already inside a higher-level transaction.
 *
 * Plan-Doc: `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`.
 */
class MysqlAtomicSequencePreserveExecutor : AtomicSequencePreserveExecutor {

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
        val sortedRequests = batch.requests.sortedWith(
            compareBy({ it.sequenceRef.name }, { it.sequenceRef.schema.orEmpty() }),
        )
        val sortedRefs = sortedRequests.map { it.sequenceRef }
        if (sortedRequests.isEmpty()) {
            return AtomicSequencePreserveResult.Applied(emptyList())
        }
        AtomicSequencePreserveExecutor.requireOwnedConnection(connection)

        // Service-Mode Sub-Slice E checkpoint 1 (pre-BEGIN): no
        // transaction or session-timeout override yet; short-circuit
        // without touching the connection.
        if (cancellationToken.isCancellationRequested) {
            return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
        }

        val previousAutoCommit = connection.autoCommit
        val previousLockWaitTimeout = readLockWaitTimeout(connection)
        applyLockWaitTimeout(connection, ceilDivToSeconds(lockTimeoutMillis))
        connection.autoCommit = false
        try {
            val probeResults = mutableMapOf<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>()
            for (request in sortedRequests) {
                val ref = request.sequenceRef
                lockAndProbe(connection, ref, probeResults)?.let { earlyExit -> return earlyExit }
            }
            // Service-Mode Sub-Slice E checkpoint 2: rollback
            // releases the `SELECT … FOR UPDATE` row-locks acquired
            // above on `dmg_sequences`.
            if (cancellationToken.isCancellationRequested) {
                runCatching { connection.rollback() }
                return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
            }
            runProtected(connection, batch.protectedOperationIds, executeProtectedOperations, sortedRefs)
                ?.let { earlyExit -> return earlyExit }
            // Service-Mode Sub-Slice E checkpoint 3: rollback undoes
            // the protected-operation statements so `dmg_sequences`
            // and any sequence-bearing tables are unchanged after
            // the caller-observed cancellation.
            if (cancellationToken.isCancellationRequested) {
                runCatching { connection.rollback() }
                return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
            }
            for (request in sortedRequests) {
                restore(connection, request, probeResults.getValue(request.sequenceRef))
                    ?.let { earlyExit -> return earlyExit }
            }
            connection.commit()
            return AtomicSequencePreserveResult.Applied(sortedRefs)
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            return AtomicSequencePreserveResult.Failed(sortedRefs.first(), e)
        } finally {
            // `SET SESSION` persists across the transaction — restore
            // the prior value so the next pool borrow inherits clean
            // state, matching the plan-doc §6 Risk-6 mitigation
            // ("Session-Timeout-Leak").
            runCatching { applyLockWaitTimeout(connection, previousLockWaitTimeout) }
            runCatching { connection.autoCommit = previousAutoCommit }
        }
    }

    private fun lockAndProbe(
        connection: Connection,
        ref: SequenceObjectRef,
        probeResults: MutableMap<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>,
    ): AtomicSequencePreserveResult? {
        // Step 1: acquire the row X-lock with a minimal SELECT FOR
        // UPDATE. Step 2 (probe) runs against the same transaction
        // and sees the same row without re-blocking — InnoDB lets
        // the lock owner read the row freely. Splitting the lock
        // and the validated read keeps row-shape validation (managed_by,
        // format_version, multi-row defense) in the single probe
        // implementation that already has full unit-test coverage.
        val lookupKey = MysqlSequenceSupportNaming.lookupKey(ref)
        val lockSql = "SELECT 1 FROM `${MysqlSequenceSupportNaming.SUPPORT_TABLE}` " +
            "WHERE `name` = ? FOR UPDATE"
        try {
            connection.prepareStatement(lockSql).use { ps ->
                ps.setString(1, lookupKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        connection.rollback()
                        return AtomicSequencePreserveResult.NotFound(listOf(ref))
                    }
                }
            }
        } catch (e: SQLException) {
            connection.rollback()
            return classifyLockSqlException(ref, e)
        }

        val probe = try {
            MysqlSequenceCurrentValueProbe.probe(connection, ref)
        } catch (e: Throwable) {
            connection.rollback()
            return AtomicSequencePreserveResult.Failed(ref, e)
        }
        return when (probe) {
            is SequenceCurrentValueProbeResult.Read -> {
                probeResults[ref] = probe
                null
            }
            is SequenceCurrentValueProbeResult.NotFound -> {
                // Race window: row vanished between our lock and
                // the probe. InnoDB held the lock against any other
                // writer, so this is almost impossible — surface it
                // as NotFound for the runner.
                connection.rollback()
                AtomicSequencePreserveResult.NotFound(listOf(ref))
            }
            is SequenceCurrentValueProbeResult.Failed -> {
                connection.rollback()
                AtomicSequencePreserveResult.Failed(
                    ref,
                    IllegalStateException("MySQL probe failed: ${probe.code} ${probe.message}"),
                )
            }
            is SequenceCurrentValueProbeResult.NotApplicable -> {
                // MySQL probe never returns NotApplicable; defensive
                // branch.
                connection.rollback()
                AtomicSequencePreserveResult.Failed(
                    ref,
                    IllegalStateException("MySQL probe returned NotApplicable for $ref — unreachable"),
                )
            }
        }
    }

    private fun runProtected(
        connection: Connection,
        protectedOperationIds: List<ProtectedOperationId>,
        executeProtectedOperations: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
        sortedRefs: List<SequenceObjectRef>,
    ): AtomicSequencePreserveResult? {
        return try {
            executeProtectedOperations(connection, protectedOperationIds)
            null
        } catch (e: Throwable) {
            connection.rollback()
            AtomicSequencePreserveResult.Failed(sortedRefs.last(), e)
        }
    }

    private fun restore(
        connection: Connection,
        request: AtomicSequencePreserveRequest,
        probe: SequenceCurrentValueProbeResult.Read,
    ): AtomicSequencePreserveResult? {
        val statements = try {
            request.renderRestore(probe)
        } catch (e: Throwable) {
            connection.rollback()
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
            connection.rollback()
            AtomicSequencePreserveResult.Failed(request.sequenceRef, e)
        }
    }

    private fun readLockWaitTimeout(connection: Connection): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun applyLockWaitTimeout(connection: Connection, seconds: Long) {
        connection.createStatement().use { stmt ->
            stmt.execute("SET SESSION innodb_lock_wait_timeout = $seconds")
        }
    }

    private fun ceilDivToSeconds(millis: Long): Long {
        // MySQL `innodb_lock_wait_timeout` is in seconds with a
        // minimum of 1. Round up so a sub-second request still maps
        // to a meaningful wait window rather than truncating to 0
        // (which MySQL would reject).
        val seconds = (millis + 999) / 1000
        return seconds.coerceAtLeast(1)
    }

    companion object {
        /** MySQL error code 1205: ER_LOCK_WAIT_TIMEOUT (MySQL Errors 5.7+ Appendix B). */
        const val MYSQL_ERR_LOCK_WAIT_TIMEOUT: Int = 1205

        /** MySQL error code 1213: ER_LOCK_DEADLOCK. */
        const val MYSQL_ERR_LOCK_DEADLOCK: Int = 1213

        /** MySQL error code 1146: ER_NO_SUCH_TABLE. */
        const val MYSQL_ERR_NO_SUCH_TABLE: Int = 1146

        /**
         * Maps a [SQLException]'s `errorCode` to the matching
         * terminal [AtomicSequencePreserveResult]. Extracted as a
         * pure function so the per-error-code routing is unit-
         * testable without a JDBC stub. The caller has already
         * rolled the transaction back; this function only chooses
         * the result shape.
         */
        internal fun classifyLockSqlException(
            ref: SequenceObjectRef,
            e: SQLException,
        ): AtomicSequencePreserveResult = when (e.errorCode) {
            MYSQL_ERR_LOCK_WAIT_TIMEOUT, MYSQL_ERR_LOCK_DEADLOCK ->
                AtomicSequencePreserveResult.LockTimeout(listOf(ref))
            MYSQL_ERR_NO_SUCH_TABLE ->
                AtomicSequencePreserveResult.NotFound(listOf(ref))
            else -> AtomicSequencePreserveResult.Failed(ref, e)
        }
    }
}
