package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.idempotency.IdempotencyState
import dev.dmigrate.server.core.idempotency.InitResumeOutcome
import dev.dmigrate.server.core.idempotency.InitResumeScope
import dev.dmigrate.server.ports.IdempotencyStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryIdempotencyStore(
    private val pendingLeaseSeconds: Long = 60,
    private val awaitingApprovalSeconds: Long = 600,
    private val deniedRetentionSeconds: Long = 600,
    private val committedRetentionSeconds: Long = 86_400,
    private val initResumeSeconds: Long = 600,
) : IdempotencyStore {

    private data class Entry(
        val scope: IdempotencyScope,
        val fingerprint: String,
        val state: IdempotencyState,
        val expiresAt: Instant,
        val resultRef: String? = null,
        val deniedReason: String? = null,
    )

    private data class InitEntry(
        val scope: InitResumeScope,
        val fingerprint: String,
        val sessionId: String,
        val expiresAt: Instant,
    )

    private val entries = ConcurrentHashMap<IdempotencyScope, Entry>()
    private val initEntries = ConcurrentHashMap<InitResumeScope, InitEntry>()

    override fun reserve(
        scope: IdempotencyScope,
        payloadFingerprint: String,
        now: Instant,
    ): IdempotencyReserveOutcome {
        var outcome: IdempotencyReserveOutcome? = null
        entries.compute(scope) { _, existing ->
            outcome = computeReserveOutcome(scope, payloadFingerprint, now, existing)
            buildEntry(scope, payloadFingerprint, now, existing, outcome!!)
        }
        return outcome!!
    }

    private fun computeReserveOutcome(
        scope: IdempotencyScope,
        fingerprint: String,
        now: Instant,
        existing: Entry?,
    ): IdempotencyReserveOutcome {
        if (existing == null) {
            return IdempotencyReserveOutcome.Reserved(scope, now.plusSeconds(pendingLeaseSeconds))
        }
        if (existing.fingerprint != fingerprint) {
            return IdempotencyReserveOutcome.Conflict(scope, existing.fingerprint)
        }
        return when (existing.state) {
            IdempotencyState.PENDING -> {
                if (existing.expiresAt.isBefore(now)) {
                    IdempotencyReserveOutcome.Reserved(scope, now.plusSeconds(pendingLeaseSeconds))
                } else {
                    IdempotencyReserveOutcome.ExistingPending(scope, existing.expiresAt)
                }
            }
            IdempotencyState.AWAITING_APPROVAL ->
                IdempotencyReserveOutcome.AwaitingApproval(scope, existing.expiresAt)
            IdempotencyState.COMMITTED ->
                IdempotencyReserveOutcome.Committed(scope, existing.resultRef!!)
            IdempotencyState.DENIED ->
                IdempotencyReserveOutcome.Denied(scope, existing.expiresAt, existing.deniedReason!!)
        }
    }

    private fun buildEntry(
        scope: IdempotencyScope,
        fingerprint: String,
        now: Instant,
        existing: Entry?,
        outcome: IdempotencyReserveOutcome,
    ): Entry = when (outcome) {
        is IdempotencyReserveOutcome.Reserved ->
            Entry(scope, fingerprint, IdempotencyState.PENDING, outcome.leaseExpiresAt)
        is IdempotencyReserveOutcome.ExistingPending,
        is IdempotencyReserveOutcome.AwaitingApproval,
        is IdempotencyReserveOutcome.Committed,
        is IdempotencyReserveOutcome.Denied,
        is IdempotencyReserveOutcome.Conflict -> existing!!
    }

    override fun reserveInitResume(
        scope: InitResumeScope,
        payloadFingerprint: String,
        sessionId: String,
        now: Instant,
    ): InitResumeOutcome {
        var outcome: InitResumeOutcome? = null
        initEntries.compute(scope) { _, existing ->
            when {
                existing == null -> {
                    val expires = now.plusSeconds(initResumeSeconds)
                    outcome = InitResumeOutcome.Reserved(scope, sessionId, expires)
                    InitEntry(scope, payloadFingerprint, sessionId, expires)
                }
                existing.fingerprint != payloadFingerprint -> {
                    outcome = InitResumeOutcome.Conflict(scope, existing.fingerprint)
                    existing
                }
                else -> {
                    outcome = InitResumeOutcome.Existing(scope, existing.sessionId, existing.expiresAt)
                    existing
                }
            }
        }
        return outcome!!
    }

    override fun markAwaitingApproval(scope: IdempotencyScope, now: Instant): Boolean {
        var transitioned = false
        entries.computeIfPresent(scope) { _, existing ->
            if (existing.state == IdempotencyState.PENDING) {
                transitioned = true
                existing.copy(
                    state = IdempotencyState.AWAITING_APPROVAL,
                    expiresAt = now.plusSeconds(awaitingApprovalSeconds),
                )
            } else {
                existing
            }
        }
        return transitioned
    }

    override fun commit(scope: IdempotencyScope, resultRef: String, now: Instant): Boolean {
        var transitioned = false
        entries.computeIfPresent(scope) { _, existing ->
            if (existing.state == IdempotencyState.PENDING ||
                existing.state == IdempotencyState.AWAITING_APPROVAL
            ) {
                transitioned = true
                existing.copy(
                    state = IdempotencyState.COMMITTED,
                    resultRef = resultRef,
                    expiresAt = now.plusSeconds(committedRetentionSeconds),
                )
            } else {
                existing
            }
        }
        return transitioned
    }

    override fun deny(scope: IdempotencyScope, reason: String, now: Instant): Boolean {
        var transitioned = false
        entries.computeIfPresent(scope) { _, existing ->
            if (existing.state == IdempotencyState.PENDING ||
                existing.state == IdempotencyState.AWAITING_APPROVAL
            ) {
                transitioned = true
                existing.copy(
                    state = IdempotencyState.DENIED,
                    deniedReason = reason,
                    expiresAt = now.plusSeconds(deniedRetentionSeconds),
                )
            } else {
                existing
            }
        }
        return transitioned
    }

    override fun cleanupExpired(now: Instant): Int {
        var removed = 0
        val terminal = setOf(IdempotencyState.COMMITTED, IdempotencyState.DENIED)
        val expiredKeys = entries.entries
            .filter { it.value.state in terminal && it.value.expiresAt.isBefore(now) }
            .map { it.key }
        expiredKeys.forEach {
            if (entries.remove(it) != null) removed++
        }
        val expiredInits = initEntries.entries
            .filter { it.value.expiresAt.isBefore(now) }
            .map { it.key }
        expiredInits.forEach { if (initEntries.remove(it) != null) removed++ }
        return removed
    }
}
