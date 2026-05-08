package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Phase F § 8.6 (F.6 1/3) — pin't das Quota-Release-Verhalten des
 * `UploadSessionService.expireDue`-Pfads:
 *
 * - Mit gewireten [QuotaService]: TTL-/Idle-Expiry gibt die
 *   Init-Quotas (`ACTIVE_UPLOAD_SESSIONS=1`,
 *   `UPLOAD_BYTES=session.sizeBytes`) frei.
 * - Ohne QuotaService (Default): Bestands-Tests bleiben gruen,
 *   keine Quota-Mutation.
 * - Idempotenz: zweiter Sweep-Call ist no-op (release ist
 *   idempotent bei nicht-positivem Counter).
 */
class UploadSessionServiceQuotaReleaseTest : FunSpec({

    val tenant = Fixtures.tenant("acme")

    fun seedExpiredSession(
        sessions: InMemoryUploadSessionStore,
        sessionId: String,
        sizeBytes: Long,
    ) {
        sessions.save(
            Fixtures.uploadSession(
                sessionId,
                idleTimeoutAt = Fixtures.NOW.minusSeconds(10),
                absoluteLeaseExpiresAt = Fixtures.NOW.plusSeconds(10_000),
                sizeBytes = sizeBytes,
            ),
        )
    }

    test("expireDue mit QuotaService gibt ACTIVE_UPLOAD_SESSIONS + UPLOAD_BYTES frei") {
        val sessions = InMemoryUploadSessionStore()
        val segments = InMemoryUploadSegmentStore()
        val artifacts = InMemoryArtifactContentStore()
        val quotaStore = InMemoryQuotaStore()
        val quotaService: QuotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
        // Init-Style Reservierungen pro Hand simulieren — die spaetere
        // Expiry-Sweep muss diese genau wieder freigeben.
        val owner = Fixtures.uploadSession("ignored").ownerPrincipalId
        val sessionsKey = QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, owner)
        val bytesKey = QuotaKey(tenant, QuotaDimension.UPLOAD_BYTES, owner)
        quotaService.reserve(sessionsKey, amount = 1)
        quotaService.reserve(bytesKey, amount = 1024)

        seedExpiredSession(sessions, "stale-1", sizeBytes = 1024)

        val service = UploadSessionService(sessions, segments, artifacts, quotaService = quotaService)
        val expired = service.expireDue(Fixtures.NOW)
        expired.size shouldBe 1

        // Plan § 8.6: Init-Quotas sind nach Expiry-Sweep wieder bei 0.
        quotaStore.current(sessionsKey) shouldBe 0L
        quotaStore.current(bytesKey) shouldBe 0L
    }

    test("expireDue ohne QuotaService laesst Quotas unveraendert (Bestands-Tests gruen)") {
        val sessions = InMemoryUploadSessionStore()
        val segments = InMemoryUploadSegmentStore()
        val artifacts = InMemoryArtifactContentStore()
        seedExpiredSession(sessions, "stale-2", sizeBytes = 64)

        val service = UploadSessionService(sessions, segments, artifacts) // kein QuotaService
        val expired = service.expireDue(Fixtures.NOW)
        expired.size shouldBe 1
        // Kein Crash, kein Side Effect — Bestands-Tests, die ohne
        // QuotaService auskommen, bleiben unveraendert.
    }

    test("zweiter Sweep ist idempotent (Plan: release idempotent ausfuehren)") {
        val sessions = InMemoryUploadSessionStore()
        val segments = InMemoryUploadSegmentStore()
        val artifacts = InMemoryArtifactContentStore()
        val quotaStore = InMemoryQuotaStore()
        val quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
        val owner = Fixtures.uploadSession("ignored").ownerPrincipalId
        val sessionsKey = QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, owner)
        quotaService.reserve(sessionsKey, amount = 1)

        seedExpiredSession(sessions, "stale-3", sizeBytes = 32)

        val service = UploadSessionService(sessions, segments, artifacts, quotaService = quotaService)
        service.expireDue(Fixtures.NOW)
        // Zweiter Sweep mit derselben Zeit findet keine neuen Sessions
        // mehr (sind schon EXPIRED), also kein doppelter Release —
        // aber der Service soll auch bei doppelter Sicht stabil sein.
        service.expireDue(Fixtures.NOW)
        quotaStore.current(sessionsKey) shouldBe 0L

        // Defensiv: ein direkter doppelter Release-Call wuerde
        // theoretisch unter 0 fallen, aber QuotaService.release ist
        // idempotent (no-op bei aktuellem Counter == 0).
        quotaService.release(QuotaReservation(sessionsKey, amount = 1))
        quotaStore.current(sessionsKey) shouldBe 0L
    }
})
