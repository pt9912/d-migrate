package dev.dmigrate.server.persistence.jdbc.artifact

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ArtifactUploadMetadata
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ArtifactRecordJsonTest : FunSpec({

    fun sample(): ArtifactRecord = ArtifactRecord(
        managedArtifact = ManagedArtifact(
            artifactId = "art_1",
            filename = "schema.yaml",
            contentType = "application/x-yaml",
            sizeBytes = 4096L,
            sha256 = "a".repeat(64),
            createdAt = Instant.parse("2026-05-06T10:00:00Z"),
            expiresAt = Instant.parse("2026-05-13T10:00:00Z"),
        ),
        kind = ArtifactKind.SCHEMA,
        tenantId = TenantId("acme"),
        ownerPrincipalId = PrincipalId("alice"),
        visibility = JobVisibility.OWNER,
        resourceUri = ServerResourceUri(
            tenantId = TenantId("acme"),
            kind = ResourceKind.ARTIFACTS,
            id = "art_1",
        ),
        adminScope = "ops:read",
        jobRef = "job_1",
        uploadMetadata = ArtifactUploadMetadata(
            artifactId = "art_1",
            resourceUri = "dmigrate://tenants/acme/artifacts/art_1",
            uploadIntent = "schema-stage",
            wireArtifactKind = "schema",
            contentType = "application/x-yaml",
            format = "yaml",
            targetTable = "users",
            targetTables = listOf("users", "orders"),
            sourceUploadSessionId = "session_1",
            policyFingerprint = "b".repeat(64),
            sizeBytes = 4096L,
            sha256 = "a".repeat(64),
            bundleFormat = "seed-bundle.v1",
            manifestPath = "manifest.json",
            manifestFingerprint = "c".repeat(64),
        ),
    )

    test("round-trip preserves all fields including nested uploadMetadata") {
        val record = sample()
        val parsed = ArtifactRecordJson.fromJson(ArtifactRecordJson.toJson(record))
        parsed shouldBe record
    }

    test("minimal record (no adminScope/jobRef/uploadMetadata) round-trips") {
        val record = ArtifactRecord(
            managedArtifact = ManagedArtifact(
                artifactId = "art_2",
                filename = "export.csv",
                contentType = "text/csv",
                sizeBytes = 128L,
                sha256 = "d".repeat(64),
                createdAt = Instant.parse("2026-05-06T11:00:00Z"),
                expiresAt = Instant.parse("2026-05-13T11:00:00Z"),
            ),
            kind = ArtifactKind.DATA_EXPORT,
            tenantId = TenantId("umbrella"),
            ownerPrincipalId = PrincipalId("carol"),
            visibility = JobVisibility.TENANT,
            resourceUri = ServerResourceUri(
                tenantId = TenantId("umbrella"),
                kind = ResourceKind.ARTIFACTS,
                id = "art_2",
            ),
        )
        val parsed = ArtifactRecordJson.fromJson(ArtifactRecordJson.toJson(record))
        parsed shouldBe record
    }
})
