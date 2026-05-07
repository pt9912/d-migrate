package dev.dmigrate.server.application.artifact

import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.time.Instant

class ArtifactRetentionServiceTest : FunSpec({

    val tenant = TenantId("acme")
    val owner = PrincipalId("alice")
    val now = Instant.parse("2026-05-07T12:00:00Z")

    fun record(id: String, expiresAt: Instant): ArtifactRecord =
        ArtifactRecord(
            managedArtifact = ManagedArtifact(
                artifactId = id,
                filename = "$id.bin",
                contentType = "text/csv",
                sizeBytes = 4,
                sha256 = "0".repeat(64),
                createdAt = now.minusSeconds(3_600),
                expiresAt = expiresAt,
            ),
            kind = ArtifactKind.UPLOAD_INPUT,
            tenantId = tenant,
            ownerPrincipalId = owner,
            visibility = JobVisibility.TENANT,
            resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, id),
        )

    test("deleteExpired loescht Metadaten und Bytes und released STORED_ARTIFACT_BYTES") {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val quotaStore = InMemoryQuotaStore()
        val quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
        val key = QuotaKey(tenant, QuotaDimension.STORED_ARTIFACT_BYTES, owner)

        val reservation = (quotaService.reserve(key, 4) as QuotaOutcome.Granted).let(QuotaReservation::of)
        quotaService.commit(reservation)
        artifactStore.save(record("expired", now.minusSeconds(1)))
        contentStore.write("expired", ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 4)

        ArtifactRetentionService(artifactStore, contentStore, quotaService).deleteExpired(now) shouldBe 1

        artifactStore.findById(tenant, "expired") shouldBe null
        contentStore.exists("expired") shouldBe false
        quotaStore.current(key) shouldBe 0L
    }
})
