package dev.dmigrate.driver.postgresql

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
 * Atomic-Preserve Phase B.2 (2026-05-31): PostgreSQL implementation
 * of [AtomicSequencePreserveExecutor]. Lock strategy per plan §4.1
 * (corrected after the initial `LOCK TABLE` assumption surfaced as
 * non-viable — PG rejects `LOCK TABLE` against a sequence relation
 * with "This operation is not supported for sequences."):
 *
 * - `SELECT pg_advisory_xact_lock(<stable_hash(sequence_ref)>)` per
 *   sequence in deterministic name-sorted order. The advisory lock
 *   serialises **parallel d-migrate runs** that target the same
 *   sequence — it does NOT block app-side `nextval` calls (PG
 *   sequences are by-design lock-free). The atomic-preserve
 *   contract in this dialect is therefore "smaller race window than
 *   the two-transaction fallback PLUS no inter-d-migrate
 *   corruption". Plan-Doc §6 Risk 8 documents the residual app
 *   race.
 * - The lock key is `hashtext("d-migrate:seq:" || schema || "." ||
 *   name)::bigint`. `hashtext` is PG-internal and stable across
 *   sessions; the explicit namespace prefix prevents collision with
 *   other advisory-lock consumers in the same database.
 * - `SET LOCAL lock_timeout = '<ms>ms'` before the locks so the
 *   advisory acquisition respects the per-batch budget. `LOCAL`
 *   scopes the setting to the current transaction — PG reverts on
 *   commit / rollback without leaking into the next borrow.
 * - SQLSTATE `55P03` (`lock_not_available`) on lock acquisition →
 *   [AtomicSequencePreserveResult.LockTimeout].
 * - SQLSTATE `42P01` (`undefined_table`) on the probe SELECT →
 *   [AtomicSequencePreserveResult.NotFound].
 *
 * The executor owns the connection's autocommit + commit / rollback
 * — callers MUST supply a single-owner connection that is not
 * already inside a higher-level transaction.
 *
 * Plan-Doc: `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`.
 */
class PostgresAtomicSequencePreserveExecutor : AtomicSequencePreserveExecutor {

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
        // Deterministic lock order: name-then-schema. Plan §2 (3)
        // pins this so two parallel `schema migrate` invocations
        // touching overlapping sequence sets serialise instead of
        // deadlock-diamonding.
        val sortedRequests = batch.requests.sortedWith(
            compareBy({ it.sequenceRef.name }, { it.sequenceRef.schema.orEmpty() }),
        )
        val sortedRefs = sortedRequests.map { it.sequenceRef }
        if (sortedRequests.isEmpty()) {
            // Empty batch is a no-op success — the runner gets a
            // canonical Applied with no refs, the transaction is
            // never opened. The owner-vertrag check runs only when
            // the executor actually touches the connection.
            return AtomicSequencePreserveResult.Applied(emptyList())
        }
        AtomicSequencePreserveExecutor.requireOwnedConnection(connection)

        // Service-Mode Sub-Slice E checkpoint 1 (pre-BEGIN): no
        // transaction state to revert yet, so just short-circuit.
        if (cancellationToken.isCancellationRequested) {
            return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
        }

        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { stmt ->
                stmt.execute("SET LOCAL lock_timeout = '${lockTimeoutMillis}ms'")
            }
            val probeResults = mutableMapOf<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>()
            for (request in sortedRequests) {
                val ref = request.sequenceRef
                lockAndProbe(connection, ref, probeResults)?.let { earlyExit -> return earlyExit }
            }
            // Service-Mode Sub-Slice E checkpoint 2 (post-probe,
            // pre-protected-operations): rollback releases the
            // advisory locks acquired above.
            if (cancellationToken.isCancellationRequested) {
                runCatching { connection.rollback() }
                return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
            }
            runProtected(connection, batch.protectedOperationIds, executeProtectedOperations, sortedRefs)
                ?.let { earlyExit -> return earlyExit }
            // Service-Mode Sub-Slice E checkpoint 3 (post-protected,
            // pre-restore): rollback undoes the protected-operation
            // statements so the sequence state is unchanged for the
            // caller-observed cancellation.
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
            // `SET LOCAL lock_timeout` is transaction-scoped (PG
            // reverts on commit/rollback) — no extra reset needed.
            // Only the autocommit override needs to be undone so the
            // pooled connection lands back in its borrow-time state.
            runCatching { connection.autoCommit = previousAutoCommit }
        }
    }

    /**
     * Acquire the per-sequence lock and run the probe on the same
     * transaction. Populates [probeResults] on success; returns a
     * terminal [AtomicSequencePreserveResult] (rolling back first)
     * on any failure path. Returning `null` means "carry on with the
     * next request".
     */
    private fun lockAndProbe(
        connection: Connection,
        ref: SequenceObjectRef,
        probeResults: MutableMap<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>,
    ): AtomicSequencePreserveResult? {
        try {
            connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)",
            ).use { ps ->
                ps.setString(1, lockKey(ref))
                ps.executeQuery().use { rs ->
                    // hashtext-then-cast returns one row; consume it
                    // so JDBC doesn't leave the cursor open across
                    // the next statement on the same connection.
                    rs.next()
                }
            }
        } catch (e: SQLException) {
            connection.rollback()
            return when (e.sqlState) {
                SQLSTATE_LOCK_NOT_AVAILABLE ->
                    AtomicSequencePreserveResult.LockTimeout(listOf(ref))
                else -> AtomicSequencePreserveResult.Failed(ref, e)
            }
        }
        val probe = try {
            PostgresSequenceCurrentValueProbe.probe(connection, ref)
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
                connection.rollback()
                AtomicSequencePreserveResult.NotFound(listOf(ref))
            }
            is SequenceCurrentValueProbeResult.Failed -> {
                connection.rollback()
                AtomicSequencePreserveResult.Failed(
                    ref,
                    IllegalStateException("PG probe failed: ${probe.code} ${probe.message}"),
                )
            }
            is SequenceCurrentValueProbeResult.NotApplicable -> {
                // PG probe never returns NotApplicable (the outcome
                // is reserved for the cross-dialect gate); branch
                // defensively so a future probe refactor surfaces
                // here instead of being silently dropped.
                connection.rollback()
                AtomicSequencePreserveResult.Failed(
                    ref,
                    IllegalStateException("PG probe returned NotApplicable for $ref — unreachable"),
                )
            }
        }
    }

    /**
     * Run the runner-supplied protected operations on the same
     * connection. Exceptions surface as Failed pinning the last
     * sequence in the sorted batch — that's the best the executor
     * can do because the runner's lambda is opaque about which
     * protected op fired the throw.
     */
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

    /**
     * Render the restore SQL for one request and execute it on the
     * locked connection. Render exceptions and SQL exceptions both
     * surface as Failed against the sequence in question.
     */
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

    /**
     * Stable advisory-lock key string for [ref]. The
     * `d-migrate:seq:` namespace prefix isolates our locks from
     * any other advisory-lock consumer in the same PG database;
     * `hashtext(...)::bigint` (computed inside PG) produces the
     * 64-bit key that `pg_advisory_xact_lock` accepts.
     */
    private fun lockKey(ref: SequenceObjectRef): String {
        val schema = ref.schema.orEmpty()
        return "d-migrate:seq:$schema.${ref.name}"
    }

    companion object {
        /** SQLSTATE for `lock_not_available` (PG manual §52.5). */
        const val SQLSTATE_LOCK_NOT_AVAILABLE: String = "55P03"
    }
}
