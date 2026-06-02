package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.ai.AiToolAcquireOutcome
import dev.dmigrate.server.core.ai.AiToolClaimId
import dev.dmigrate.server.core.ai.AiToolOutcome
import dev.dmigrate.server.core.ai.AiToolScope
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — Akzeptanz für den
 * Single-Writer-Lease + Reclaim-Vertrag.
 */
class InProcessAiToolOutcomeStoreTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val scope = AiToolScope(tenant, alice, "procedure_transform_plan", "key-1")
    val fp = "0".repeat(64)
    val otherFp = "1".repeat(64)
    val now: Instant = Instant.parse("2026-05-07T12:00:00Z")
    val lease = Duration.ofSeconds(60)

    fun deterministicStore(): InProcessAiToolOutcomeStore {
        val counter = AtomicInteger(0)
        return InProcessAiToolOutcomeStore(
            claimIdFactory = { AiToolClaimId("claim-${counter.incrementAndGet()}") },
        )
    }

    test("Acquire fresh -> Acquired(attemptCount=1)") {
        val store = deterministicStore()
        val outcome = store.acquire(scope, fp, lease, now)
        val acquired = outcome.shouldBeInstanceOf<AiToolAcquireOutcome.Acquired>()
        acquired.attemptCount shouldBe 1
        acquired.leaseExpiresAt shouldBe now.plus(lease)
        acquired.claimId shouldBe AiToolClaimId("claim-1")
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: parallele identische Pending-Reserves liefern InProgress") {
        // Hier liegt der LF-010 / LF-013 / LN-009 / LN-011-SyncEffectIdempotencyStore-Bug
        // (`InProcessUploadControlStores.kt:51`) — bei aktiver Lease mit
        // gleichem Fingerprint wird `Reserved` erneut zurueckgegeben.
        // Der KI-Store loest das mit InProgress.
        val store = deterministicStore()
        store.acquire(scope, fp, lease, now)
        val parallel = store.acquire(scope, fp, lease, now.plusSeconds(5))
        val inProgress = parallel.shouldBeInstanceOf<AiToolAcquireOutcome.InProgress>()
        inProgress.leaseExpiresAt shouldBe now.plus(lease)
    }

    test("Acquire nach abgelaufener Lease reclaimed mit attemptCount+1") {
        val store = deterministicStore()
        store.acquire(scope, fp, lease, now)
        val afterExpiry = now.plus(lease).plusSeconds(1)
        val reclaimed = store.acquire(scope, fp, lease, afterExpiry)
        val acquired = reclaimed.shouldBeInstanceOf<AiToolAcquireOutcome.Acquired>()
        acquired.attemptCount shouldBe 2
        acquired.claimId shouldBe AiToolClaimId("claim-2")
    }

    test("Acquire nach Succeeded liefert Existing(Succeeded) ohne neuen Provider-Aufruf") {
        val store = deterministicStore()
        val acquired = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        val succeeded = AiToolOutcome.Succeeded(
            scope = scope,
            payloadFingerprint = fp,
            resultRef = "dmigrate://tenants/acme/artifacts/art-result",
            outputFingerprint = "a".repeat(64),
            providerName = "noop",
            model = "noop:default",
            providerRequestId = null,
            committedAt = now.plusSeconds(1),
        )
        store.commit(scope, acquired.claimId, succeeded, now.plusSeconds(1)) shouldBe true

        val replay = store.acquire(scope, fp, lease, now.plusSeconds(2))
        val existing = replay.shouldBeInstanceOf<AiToolAcquireOutcome.Existing>()
        existing.outcome shouldBe succeeded
    }

    test("Acquire nach FailedTerminal liefert Existing(FailedTerminal)") {
        val store = deterministicStore()
        val acquired = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        val terminal = AiToolOutcome.FailedTerminal(
            scope = scope,
            payloadFingerprint = fp,
            toolErrorCode = ToolErrorCode.PROMPT_HYGIENE_BLOCKED,
            scrubbedMessage = "secret pattern detected",
            committedAt = now.plusSeconds(1),
        )
        store.commit(scope, acquired.claimId, terminal, now.plusSeconds(1)) shouldBe true

        val replay = store.acquire(scope, fp, lease, now.plusSeconds(2))
        val existing = replay.shouldBeInstanceOf<AiToolAcquireOutcome.Existing>()
        existing.outcome shouldBe terminal
    }

    test("Acquire nach provider-seitigem FailedRetryable replayt retryable Status ohne neuen Versuch") {
        val store = deterministicStore()
        val first = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        val retryable = AiToolOutcome.FailedRetryable(
            scope = scope,
            payloadFingerprint = fp,
            toolErrorCode = ToolErrorCode.OPERATION_TIMEOUT,
            scrubbedMessage = "provider timeout",
            attemptCount = first.attemptCount,
            lastAttemptAt = now.plusSeconds(1),
        )
        store.commit(scope, first.claimId, retryable, now.plusSeconds(1)) shouldBe true

        val retry = store.acquire(scope, fp, lease, now.plusSeconds(2))
        val existing = retry.shouldBeInstanceOf<AiToolAcquireOutcome.ExistingRetryable>()
        existing.outcome shouldBe retryable
    }

    test("Acquire nach Policy-Challenge FailedRetryable startet neuen Versuch mit previousRetryable") {
        val store = deterministicStore()
        val first = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        val retryable = AiToolOutcome.FailedRetryable(
            scope = scope,
            payloadFingerprint = fp,
            toolErrorCode = ToolErrorCode.POLICY_REQUIRED,
            scrubbedMessage = "approval required",
            attemptCount = first.attemptCount,
            lastAttemptAt = now.plusSeconds(1),
            approvalRequestId = "apr-1",
        )
        store.commit(scope, first.claimId, retryable, now.plusSeconds(1)) shouldBe true

        val retry = store.acquire(scope, fp, lease, now.plusSeconds(2))
        val acquired = retry.shouldBeInstanceOf<AiToolAcquireOutcome.Acquired>()
        acquired.attemptCount shouldBe 2
        acquired.previousRetryable shouldBe retryable
    }

    test("Acquire mit anderem Fingerprint im Pending-Scope -> Conflict") {
        val store = deterministicStore()
        store.acquire(scope, fp, lease, now)
        val conflict = store.acquire(scope, otherFp, lease, now.plusSeconds(1))
        val res = conflict.shouldBeInstanceOf<AiToolAcquireOutcome.Conflict>()
        res.existingFingerprint shouldBe fp
    }

    test("Acquire mit anderem Fingerprint nach terminalem Outcome -> Conflict") {
        val store = deterministicStore()
        val acquired = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        store.commit(
            scope,
            acquired.claimId,
            AiToolOutcome.Succeeded(
                scope, fp,
                resultRef = "dmigrate://tenants/acme/artifacts/art-x",
                outputFingerprint = "b".repeat(64),
                providerName = "noop", model = "noop:default", providerRequestId = null,
                committedAt = now.plusSeconds(1),
            ),
            now.plusSeconds(1),
        )
        val replay = store.acquire(scope, otherFp, lease, now.plusSeconds(2))
        replay.shouldBeInstanceOf<AiToolAcquireOutcome.Conflict>()
    }

    test("Commit liefert false wenn die Lease an einen anderen Caller gegangen ist") {
        val store = deterministicStore()
        val first = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        // Lease abgelaufen, Reclaim-Acquire holt eine neue ClaimId.
        val afterExpiry = now.plus(lease).plusSeconds(1)
        store.acquire(scope, fp, lease, afterExpiry) as AiToolAcquireOutcome.Acquired

        // Erster Caller versucht nachträglich zu committen — Lease ist
        // weg.
        val outcome = AiToolOutcome.Succeeded(
            scope, fp,
            resultRef = "dmigrate://tenants/acme/artifacts/art-late",
            outputFingerprint = "c".repeat(64),
            providerName = "noop", model = "noop:default", providerRequestId = null,
            committedAt = afterExpiry,
        )
        store.commit(scope, first.claimId, outcome, afterExpiry) shouldBe false
    }

    test("Commit liefert false bei unbekanntem ClaimId") {
        val store = deterministicStore()
        store.acquire(scope, fp, lease, now)
        val outcome = AiToolOutcome.FailedTerminal(
            scope, fp, ToolErrorCode.INTERNAL_AGENT_ERROR, "x", now.plusSeconds(1),
        )
        store.commit(scope, AiToolClaimId("unknown"), outcome, now.plusSeconds(1)) shouldBe false
    }

    test("Commit-Wert vom Typ Pending wird abgelehnt") {
        val store = deterministicStore()
        val acq = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        shouldThrow<IllegalArgumentException> {
            store.commit(
                scope, acq.claimId,
                AiToolOutcome.Pending(scope, fp, acq.claimId, acq.leaseExpiresAt, acq.attemptCount),
                now.plusSeconds(1),
            )
        }
    }

    test("Commit mit abweichender Scope -> IllegalArgumentException") {
        val store = deterministicStore()
        val acq = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        val otherScope = scope.copy(approvalKey = "key-2")
        shouldThrow<IllegalArgumentException> {
            store.commit(
                scope, acq.claimId,
                AiToolOutcome.Succeeded(
                    otherScope, fp,
                    resultRef = "dmigrate://tenants/acme/artifacts/art-y",
                    outputFingerprint = "d".repeat(64),
                    providerName = "noop", model = "noop:default", providerRequestId = null,
                    committedAt = now,
                ),
                now,
            )
        }
    }

    test("ReclaimExpired transitioniert abgelaufene Pending zu FailedRetryable mit OPERATION_TIMEOUT") {
        val store = deterministicStore()
        val acq = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        val sweep = store.reclaimExpired(now.plus(lease).plusSeconds(1))
        sweep shouldBe 1

        // Direkter Acquire muss jetzt einen frischen Claim ausstellen
        // (FailedRetryable im Store).
        val retry = store.acquire(scope, fp, lease, now.plus(lease).plusSeconds(2))
        val acquired = retry.shouldBeInstanceOf<AiToolAcquireOutcome.Acquired>()
        acquired.attemptCount shouldBe acq.attemptCount + 1
    }

    test("ReclaimExpired ist idempotent (zweiter Sweep liefert 0)") {
        val store = deterministicStore()
        store.acquire(scope, fp, lease, now)
        val first = store.reclaimExpired(now.plus(lease).plusSeconds(1))
        val second = store.reclaimExpired(now.plus(lease).plusSeconds(2))
        first shouldBe 1
        second shouldBe 0
    }

    test("ReclaimExpired ruehrt Succeeded und FailedTerminal nicht an") {
        val store = deterministicStore()
        val acq = store.acquire(scope, fp, lease, now) as AiToolAcquireOutcome.Acquired
        val succeeded = AiToolOutcome.Succeeded(
            scope, fp,
            resultRef = "dmigrate://tenants/acme/artifacts/art-z",
            outputFingerprint = "e".repeat(64),
            providerName = "noop", model = "noop:default", providerRequestId = null,
            committedAt = now,
        )
        store.commit(scope, acq.claimId, succeeded, now)
        store.reclaimExpired(now.plus(Duration.ofDays(7))) shouldBe 0

        // Replay liefert weiterhin Succeeded.
        val replay = store.acquire(scope, fp, lease, now.plus(Duration.ofDays(7)))
        val existing = replay.shouldBeInstanceOf<AiToolAcquireOutcome.Existing>()
        existing.outcome shouldBe succeeded
    }

    test("Konstruktor-Invarianten der Core-Typen greifen") {
        // AiToolScope blockiert blanks
        shouldThrow<IllegalArgumentException> { AiToolScope(tenant, alice, "", "k") }
        shouldThrow<IllegalArgumentException> { AiToolScope(tenant, alice, "tool", " ") }
        shouldThrow<IllegalArgumentException> { AiToolClaimId(" ") }

        // AiToolOutcome.Pending: attemptCount >= 1
        shouldThrow<IllegalArgumentException> {
            AiToolOutcome.Pending(scope, fp, AiToolClaimId("c"), now, attemptCount = 0)
        }

        // AiToolAcquireOutcome.Existing nur fuer terminale Outcomes
        shouldThrow<IllegalArgumentException> {
            AiToolAcquireOutcome.Existing(
                scope,
                AiToolOutcome.Pending(scope, fp, AiToolClaimId("c"), now, attemptCount = 1),
            )
        }
    }
})
