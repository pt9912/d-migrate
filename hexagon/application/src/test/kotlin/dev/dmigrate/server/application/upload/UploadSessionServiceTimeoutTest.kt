package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.upload.FinalizationOutcomeStatus
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * Phase F § 8.9 (F.9 2/3) — pin't den Upload-Finalisierungs-Timeout-
 * Sweeper:
 *
 * - findStaleFinalizing trifft FINALIZING-Sessions mit abgelaufenem
 *   Lease.
 * - timeoutStaleFinalizingSessions persistiert FailureOutcome
 *   (sanitizedErrorCode=OPERATION_TIMEOUT), transitioniert zu
 *   ABORTED, raeumt Segmente, gibt Init-Quotas frei.
 * - Sessions mit aktivem Lease bleiben unangetastet.
 * - Idempotent: zweiter Sweep ueberspringt bereits getimeoutete
 *   Sessions.
 */
class UploadSessionServiceTimeoutTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val owner = Fixtures.principal("alice")
    val now: Instant = Fixtures.NOW

    fun finalizingSession(
        sessionId: String,
        leaseExpiresAt: Instant,
        sizeBytes: Long = 1024,
    ): UploadSession = Fixtures.uploadSession(
        sessionId = sessionId,
        sizeBytes = sizeBytes,
    ).copy(
        state = UploadSessionState.FINALIZING,
        finalizingClaimId = "claim-$sessionId",
        finalizingClaimedAt = now.minusSeconds(60),
        finalizingLeaseExpiresAt = leaseExpiresAt,
    )

    test("findStaleFinalizing trifft FINALIZING-Sessions mit abgelaufenem Lease, ueberspringt aktive") {
        val sessions = InMemoryUploadSessionStore()
        sessions.save(finalizingSession("ups-stale", leaseExpiresAt = now.minusSeconds(10)))
        sessions.save(finalizingSession("ups-fresh", leaseExpiresAt = now.plusSeconds(60)))
        // Kontroll-ACTIVE-Session, soll vom Filter ausgeschlossen sein.
        sessions.save(Fixtures.uploadSession("ups-active"))

        val stale = sessions.findStaleFinalizing(now)
        stale.map { it.uploadSessionId } shouldBe listOf("ups-stale")
    }

    test("timeoutStaleFinalizingSessions transitioniert ABORTED + persistiert FAILED-Outcome (OPERATION_TIMEOUT)") {
        val sessions = InMemoryUploadSessionStore()
        val segments = InMemoryUploadSegmentStore()
        val artifacts = InMemoryArtifactContentStore()
        val quotaStore = InMemoryQuotaStore()
        val quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
        val sessionsKey = QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, owner)
        val bytesKey = QuotaKey(tenant, QuotaDimension.UPLOAD_BYTES, owner)
        // Init-Reservierungen pro Hand simulieren.
        quotaService.reserve(sessionsKey, amount = 1)
        quotaService.reserve(bytesKey, amount = 1024)

        sessions.save(finalizingSession("ups-timeout-1", leaseExpiresAt = now.minusSeconds(30)))
        // Pre-stage Segmente, damit der Cleanup-Schritt etwas zu loeschen hat.
        segments.writeSegment(
            UploadSegment("ups-timeout-1", 1, 0, 512, "h"),
            ByteArrayInputStream(ByteArray(512)),
        )

        val service = UploadSessionService(sessions, segments, artifacts, quotaService = quotaService)
        val timedOut = service.timeoutStaleFinalizingSessions(now)
        timedOut shouldBe 1

        // Plan-§-8.9 Akzeptanz:
        // - Session ABORTED + FailureOutcome OPERATION_TIMEOUT
        val session = sessions.findById(tenant, "ups-timeout-1").shouldNotBeNull()
        session.state shouldBe UploadSessionState.ABORTED
        val outcome = session.finalizationOutcome.shouldNotBeNull()
        outcome.status shouldBe FinalizationOutcomeStatus.FAILED
        outcome.sanitizedErrorCode shouldBe "OPERATION_TIMEOUT"

        // - Cleanup
        segments.listSegments("ups-timeout-1") shouldBe emptyList()

        // - Quota-Release
        quotaStore.current(sessionsKey) shouldBe 0L
        quotaStore.current(bytesKey) shouldBe 0L
    }

    test("Sessions mit aktivem Lease bleiben unveraendert (kein false-positive)") {
        val sessions = InMemoryUploadSessionStore()
        val segments = InMemoryUploadSegmentStore()
        val artifacts = InMemoryArtifactContentStore()
        sessions.save(finalizingSession("ups-fresh", leaseExpiresAt = now.plusSeconds(60)))

        val service = UploadSessionService(sessions, segments, artifacts)
        val timedOut = service.timeoutStaleFinalizingSessions(now)
        timedOut shouldBe 0

        // Session bleibt FINALIZING.
        sessions.findById(tenant, "ups-fresh")!!.state shouldBe UploadSessionState.FINALIZING
    }

    test("Zweiter Sweep mit identischem now ist idempotent (bereits ABORTED)") {
        val sessions = InMemoryUploadSessionStore()
        val segments = InMemoryUploadSegmentStore()
        val artifacts = InMemoryArtifactContentStore()
        sessions.save(finalizingSession("ups-double", leaseExpiresAt = now.minusSeconds(10)))

        val service = UploadSessionService(sessions, segments, artifacts)
        service.timeoutStaleFinalizingSessions(now) shouldBe 1
        // Second sweep: Session ist jetzt ABORTED -> findStaleFinalizing
        // findet nichts mehr.
        service.timeoutStaleFinalizingSessions(now) shouldBe 0
    }
})
