package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.ports.UploadInitClaim
import dev.dmigrate.server.ports.UploadInitClaimOutcome
import dev.dmigrate.server.ports.UploadInitClaimScope
import dev.dmigrate.server.ports.UploadInitClaimStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — Contract-Tests fuer [UploadInitClaimStore].
 * Pinnt LF-010 / LF-013 / LN-009 / LN-011-Atomicity:
 *
 * - Ersten Acquire gewinnt.
 * - Konkurrenter Acquire mit gleichem Fingerprint -> InProgress.
 * - Acquire nach Lease-Ablauf -> Reclaimed.
 * - Acquire mit abweichendem Fingerprint -> Conflict.
 * - Parallele Acquires: genau einer Acquired, alle anderen InProgress.
 * - release loescht nur fuer den passenden claimId.
 * - Negative Clock-Jumps verlaengern keine Leases.
 */
abstract class UploadInitClaimStoreContractTests(
    factory: () -> UploadInitClaimStore,
) : FunSpec({

    val now: Instant = Fixtures.NOW
    val scope = UploadInitClaimScope(
        tenantId = Fixtures.tenant("acme"),
        callerId = Fixtures.principal("alice"),
        toolName = "artifact_upload_init",
        approvalKey = "key-1",
    )

    test("erster Acquire liefert Acquired mit frischem Claim") {
        val store = factory()
        val outcome = store.acquire(
            scope = scope,
            payloadFingerprint = "fp",
            claimId = "claim-1",
            leaseExpiresAt = now.plusSeconds(60),
            now = now,
        )
        val acquired = outcome.shouldBeInstanceOf<UploadInitClaimOutcome.Acquired>()
        acquired.claim.claimId shouldBe "claim-1"
        acquired.claim.claimedAt shouldBe now
        acquired.claim.leaseExpiresAt shouldBe now.plusSeconds(60)
    }

    test("zweiter Acquire mit gleichem fingerprint und aktiver Lease liefert InProgress") {
        val store = factory()
        store.acquire(scope, "fp", "claim-1", now.plusSeconds(60), now)
        val second = store.acquire(scope, "fp", "claim-2", now.plusSeconds(60), now.plusSeconds(1))
        val inProgress = second.shouldBeInstanceOf<UploadInitClaimOutcome.InProgress>()
        inProgress.current.claimId shouldBe "claim-1"
    }

    test("Acquire nach Lease-Ablauf mit gleichem fingerprint -> Reclaimed") {
        val store = factory()
        store.acquire(scope, "fp", "claim-1", now.plusSeconds(60), now)
        val later = now.plusSeconds(120) // > leaseExpiresAt
        val second = store.acquire(scope, "fp", "claim-2", later.plusSeconds(60), later)
        val reclaimed = second.shouldBeInstanceOf<UploadInitClaimOutcome.Reclaimed>()
        reclaimed.claim.claimId shouldBe "claim-2"
        reclaimed.previous.claimId shouldBe "claim-1"
    }

    test("Acquire mit abweichendem fingerprint -> Conflict (egal ob Lease aktiv oder abgelaufen)") {
        val store = factory()
        store.acquire(scope, "fp-a", "claim-1", now.plusSeconds(60), now)

        val active = store.acquire(scope, "fp-b", "claim-2", now.plusSeconds(60), now.plusSeconds(1))
        val activeConflict = active.shouldBeInstanceOf<UploadInitClaimOutcome.Conflict>()
        activeConflict.existingFingerprint shouldBe "fp-a"

        val expired = store.acquire(scope, "fp-b", "claim-3", now.plusSeconds(120), now.plusSeconds(120))
        val expiredConflict = expired.shouldBeInstanceOf<UploadInitClaimOutcome.Conflict>()
        expiredConflict.existingFingerprint shouldBe "fp-a"
    }

    test("parallele Acquires: genau einer Acquired, Rest InProgress") {
        val store = factory()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..16).map { i ->
                Callable {
                    store.acquire(
                        scope = scope,
                        payloadFingerprint = "fp",
                        claimId = "claim-$i",
                        leaseExpiresAt = now.plusSeconds(60),
                        now = now,
                    )
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            val acquiredCount = results.count { it is UploadInitClaimOutcome.Acquired }
            val inProgressCount = results.count { it is UploadInitClaimOutcome.InProgress }
            acquiredCount shouldBe 1
            inProgressCount shouldBe 15
        } finally {
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    test("release mit korrekter claimId entfernt den Eintrag; release mit fremder claimId ist no-op") {
        val store = factory()
        store.acquire(scope, "fp", "claim-1", now.plusSeconds(60), now)
        store.findById(scope).shouldNotBeNull()

        // Wrong claimId — no-op.
        store.release(scope, "claim-other") shouldBe false
        store.findById(scope).shouldNotBeNull()

        // Right claimId — removed.
        store.release(scope, "claim-1") shouldBe true
        store.findById(scope).shouldBeNull()

        // Idempotent: zweiter release ist no-op.
        store.release(scope, "claim-1") shouldBe false
    }

    test("Negative Clock-Jumps verlaengern keine bestehende Lease — Acquire mit now<claimedAt liefert weiterhin InProgress") {
        val store = factory()
        store.acquire(scope, "fp", "claim-1", now.plusSeconds(60), now)
        // Clock springt zurueck
        val pastNow = now.minusSeconds(30)
        val outcome = store.acquire(scope, "fp", "claim-2", pastNow.plusSeconds(60), pastNow)
        // pastNow < leaseExpiresAt -> InProgress, Lease bleibt.
        val inProgress = outcome.shouldBeInstanceOf<UploadInitClaimOutcome.InProgress>()
        inProgress.current.claimId shouldBe "claim-1"
        inProgress.current.leaseExpiresAt shouldBe now.plusSeconds(60)
    }

    test("Acquire mit verschiedenen Scopes ist unabhaengig") {
        val store = factory()
        val a = scope.copy(approvalKey = "key-a")
        val b = scope.copy(approvalKey = "key-b")
        store.acquire(a, "fp", "claim-a", now.plusSeconds(60), now)
            .shouldBeInstanceOf<UploadInitClaimOutcome.Acquired>()
        store.acquire(b, "fp", "claim-b", now.plusSeconds(60), now)
            .shouldBeInstanceOf<UploadInitClaimOutcome.Acquired>()
        store.findById(a)?.claimId shouldBe "claim-a"
        store.findById(b)?.claimId shouldBe "claim-b"
    }
})

@Suppress("unused")
private fun UploadInitClaim.touch() = this // keep import alive for future contract growth
