package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.upload.FinalizationOutcome
import dev.dmigrate.server.core.upload.FinalizationOutcomeStatus
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.ClaimOutcome
import dev.dmigrate.server.ports.PersistOutcome
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSessionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

abstract class UploadSessionStoreContractTests(factory: () -> UploadSessionStore) : FunSpec({

    test("save and findById round-trip within tenant") {
        val store = factory()
        val session = Fixtures.uploadSession("u1")
        store.save(session)
        store.findById(Fixtures.tenant("acme"), "u1") shouldBe session
    }

    test("list filters by state") {
        val store = factory()
        store.save(Fixtures.uploadSession("active1", state = UploadSessionState.ACTIVE))
        store.save(Fixtures.uploadSession("done1", state = UploadSessionState.COMPLETED))
        val page = store.list(
            tenantId = Fixtures.tenant("acme"),
            page = PageRequest(pageSize = 10),
            stateFilter = UploadSessionState.ACTIVE,
        )
        page.items.map { it.uploadSessionId } shouldBe listOf("active1")
    }

    test("transition ACTIVE to COMPLETED applies and updates state") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        val result = store.transition(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            newState = UploadSessionState.COMPLETED,
            now = Fixtures.NOW.plusSeconds(60),
        )
        result.shouldBeInstanceOf<TransitionOutcome.Applied>()
        result.session.state shouldBe UploadSessionState.COMPLETED
        store.findById(Fixtures.tenant("acme"), "u1")?.state shouldBe UploadSessionState.COMPLETED
    }

    test("transition rejects illegal target") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1", state = UploadSessionState.COMPLETED))
        val result = store.transition(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            newState = UploadSessionState.ACTIVE,
            now = Fixtures.NOW,
        )
        result.shouldBeInstanceOf<TransitionOutcome.IllegalTransition>()
        result.from shouldBe UploadSessionState.COMPLETED
        result.to shouldBe UploadSessionState.ACTIVE
    }

    test("transition returns NotFound for unknown session") {
        val store = factory()
        store.transition(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "missing",
            newState = UploadSessionState.COMPLETED,
            now = Fixtures.NOW,
        ) shouldBe TransitionOutcome.NotFound
    }

    // ────────────────────────────────────────────────────────────────
    // AP 6.22: FINALIZING-claim CAS, stale-lease reclaim and outcome
    // persistence. Pinned at the contract level so SQL/object-store
    // adapters land with the same race semantics as the InMemory ref.
    // ────────────────────────────────────────────────────────────────

    test("tryClaimFinalization moves ACTIVE → FINALIZING with claim id and lease") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        val claimedAt = Fixtures.NOW.plusSeconds(10)
        val leaseExpires = claimedAt.plusSeconds(60)

        val outcome = store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = claimedAt,
            leaseExpiresAt = leaseExpires,
        )

        val acquired = outcome.shouldBeInstanceOf<ClaimOutcome.Acquired>()
        acquired.session.state shouldBe UploadSessionState.FINALIZING
        acquired.session.finalizingClaimId shouldBe "claim-1"
        acquired.session.finalizingClaimedAt shouldBe claimedAt
        acquired.session.finalizingLeaseExpiresAt shouldBe leaseExpires

        val reread = store.findById(Fixtures.tenant("acme"), "u1")
        reread.shouldNotBeNull().state shouldBe UploadSessionState.FINALIZING
    }

    test("tryClaimFinalization rejects a second claim while the first lease is live") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        val firstLease = Fixtures.NOW.plusSeconds(60)
        store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = Fixtures.NOW,
            leaseExpiresAt = firstLease,
        ).shouldBeInstanceOf<ClaimOutcome.Acquired>()

        val second = store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-2",
            claimedAt = Fixtures.NOW.plusSeconds(5),
            leaseExpiresAt = Fixtures.NOW.plusSeconds(120),
        )

        val claimed = second.shouldBeInstanceOf<ClaimOutcome.AlreadyClaimed>()
        claimed.currentClaimId shouldBe "claim-1"
        claimed.leaseExpiresAt shouldBe firstLease
    }

    test("tryClaimFinalization on a non-ACTIVE session returns WrongState") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1", state = UploadSessionState.COMPLETED))

        val outcome = store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = Fixtures.NOW,
            leaseExpiresAt = Fixtures.NOW.plusSeconds(60),
        )

        outcome.shouldBeInstanceOf<ClaimOutcome.WrongState>()
            .state shouldBe UploadSessionState.COMPLETED
    }

    test("tryClaimFinalization NotFound for unknown session") {
        val store = factory()
        val outcome = store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "missing",
            claimId = "claim-1",
            claimedAt = Fixtures.NOW,
            leaseExpiresAt = Fixtures.NOW.plusSeconds(60),
        )
        outcome shouldBe ClaimOutcome.NotFound
    }

    test("reclaimStaleFinalization replaces the claim id once the lease has expired") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        val firstLease = Fixtures.NOW.plusSeconds(60)
        store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = Fixtures.NOW,
            leaseExpiresAt = firstLease,
        )

        val now = firstLease.plusSeconds(1)
        val newLease = now.plusSeconds(60)
        val reclaim = store.reclaimStaleFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            newClaimId = "claim-2",
            claimedAt = now,
            leaseExpiresAt = newLease,
            now = now,
        )

        val acquired = reclaim.shouldBeInstanceOf<ClaimOutcome.Acquired>()
        acquired.session.state shouldBe UploadSessionState.FINALIZING
        acquired.session.finalizingClaimId shouldBe "claim-2"
        acquired.session.finalizingLeaseExpiresAt shouldBe newLease
    }

    test("reclaimStaleFinalization refuses while the lease is still live (forward clock-jump)") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        val firstLease = Fixtures.NOW.plusSeconds(60)
        store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = Fixtures.NOW,
            leaseExpiresAt = firstLease,
        )

        // Forward jump but still inside the lease.
        val now = firstLease.minusSeconds(1)
        val outcome = store.reclaimStaleFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            newClaimId = "claim-2",
            claimedAt = now,
            leaseExpiresAt = now.plusSeconds(60),
            now = now,
        )

        outcome.shouldBeInstanceOf<ClaimOutcome.AlreadyClaimed>()
            .currentClaimId shouldBe "claim-1"
    }

    test("reclaimStaleFinalization refuses on a backward clock-jump") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        val firstLease = Fixtures.NOW.plusSeconds(60)
        store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = Fixtures.NOW,
            leaseExpiresAt = firstLease,
        )

        // Clock jumps BACKWARD: stored expiry stays authoritative.
        val now = Fixtures.NOW.minusSeconds(120)
        val outcome = store.reclaimStaleFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            newClaimId = "claim-2",
            claimedAt = now,
            leaseExpiresAt = now.plusSeconds(60),
            now = now,
        )

        outcome.shouldBeInstanceOf<ClaimOutcome.AlreadyClaimed>()
            .currentClaimId shouldBe "claim-1"
    }

    test("reclaimStaleFinalization rejects non-FINALIZING with WrongState") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1", state = UploadSessionState.ACTIVE))
        store.reclaimStaleFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            newClaimId = "claim",
            claimedAt = Fixtures.NOW,
            leaseExpiresAt = Fixtures.NOW.plusSeconds(60),
            now = Fixtures.NOW,
        ).shouldBeInstanceOf<ClaimOutcome.WrongState>()
            .state shouldBe UploadSessionState.ACTIVE
    }

    test("persistFinalizationOutcome writes the record under the active claim id") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        val claimedAt = Fixtures.NOW
        store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = claimedAt,
            leaseExpiresAt = claimedAt.plusSeconds(60),
        )

        val outcome = FinalizationOutcome(
            claimId = "claim-1",
            payloadSha256 = "0".repeat(64),
            artifactId = "art-1",
            schemaId = "sch-1",
            format = "json",
            status = FinalizationOutcomeStatus.SUCCEEDED,
        )
        val persisted = store.persistFinalizationOutcome(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            outcome = outcome,
            now = claimedAt.plusSeconds(5),
        )

        val ok = persisted.shouldBeInstanceOf<PersistOutcome.Persisted>()
        ok.session.finalizationOutcome shouldBe outcome
        // Persist does NOT change state — caller chains a transition.
        ok.session.state shouldBe UploadSessionState.FINALIZING
    }

    test("commitFinalization atomically sets SUCCEEDED outcome + schemaRef + COMPLETED state") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        val claimedAt = Fixtures.NOW
        store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = claimedAt,
            leaseExpiresAt = claimedAt.plusSeconds(60),
        )

        val outcome = FinalizationOutcome(
            claimId = "claim-1",
            payloadSha256 = "0".repeat(64),
            artifactId = "art-1",
            schemaId = "sch-1",
            format = "json",
            status = FinalizationOutcomeStatus.SUCCEEDED,
        )
        val result = store.commitFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            outcome = outcome,
            finalisedSchemaRef = "dmigrate://tenants/acme/schemas/sch-1",
            now = claimedAt.plusSeconds(5),
        )

        val ok = result.shouldBeInstanceOf<PersistOutcome.Persisted>()
        ok.session.state shouldBe UploadSessionState.COMPLETED
        ok.session.finalizationOutcome shouldBe outcome
        ok.session.finalisedSchemaRef shouldBe "dmigrate://tenants/acme/schemas/sch-1"
    }

    test("commitFinalization rejects a stale claim id with ClaimMismatch and leaves state untouched") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = Fixtures.NOW,
            leaseExpiresAt = Fixtures.NOW.plusSeconds(60),
        )

        val outcome = FinalizationOutcome(
            claimId = "claim-2",
            payloadSha256 = "0".repeat(64),
            artifactId = "art-1",
            schemaId = "sch-1",
            format = "json",
            status = FinalizationOutcomeStatus.SUCCEEDED,
        )
        val rejected = store.commitFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-2",
            outcome = outcome,
            finalisedSchemaRef = "dmigrate://tenants/acme/schemas/sch-1",
            now = Fixtures.NOW.plusSeconds(5),
        )

        rejected.shouldBeInstanceOf<PersistOutcome.ClaimMismatch>()
            .currentClaimId shouldBe "claim-1"
        // State and schemaRef untouched: still FINALIZING under claim-1.
        store.findById(Fixtures.tenant("acme"), "u1")!!.state shouldBe UploadSessionState.FINALIZING
        store.findById(Fixtures.tenant("acme"), "u1")!!.finalisedSchemaRef shouldBe null
    }

    test("persistFinalizationOutcome rejects a stale claim id with ClaimMismatch") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        store.tryClaimFinalization(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-1",
            claimedAt = Fixtures.NOW,
            leaseExpiresAt = Fixtures.NOW.plusSeconds(60),
        )

        val outcome = FinalizationOutcome(
            claimId = "claim-2",
            payloadSha256 = "0".repeat(64),
            artifactId = "art-1",
            schemaId = "sch-1",
            format = "json",
            status = FinalizationOutcomeStatus.SUCCEEDED,
        )
        val rejected = store.persistFinalizationOutcome(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            claimId = "claim-2",
            outcome = outcome,
            now = Fixtures.NOW.plusSeconds(5),
        )

        rejected.shouldBeInstanceOf<PersistOutcome.ClaimMismatch>()
            .currentClaimId shouldBe "claim-1"
    }

    test("expireDue marks idle/lease-expired ACTIVE sessions as EXPIRED") {
        val store = factory()
        store.save(
            Fixtures.uploadSession(
                "stale",
                idleTimeoutAt = Fixtures.NOW.minusSeconds(10),
                absoluteLeaseExpiresAt = Fixtures.NOW.plusSeconds(10_000),
            ),
        )
        store.save(Fixtures.uploadSession("fresh"))
        val expired = store.expireDue(Fixtures.NOW)
        expired.map { it.uploadSessionId } shouldBe listOf("stale")
        expired.single().state shouldBe UploadSessionState.EXPIRED
        store.findById(Fixtures.tenant("acme"), "stale")?.state shouldBe UploadSessionState.EXPIRED
        store.findById(Fixtures.tenant("acme"), "fresh")?.state shouldBe UploadSessionState.ACTIVE
    }
})
