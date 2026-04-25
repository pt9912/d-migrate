package dev.dmigrate.server.core.artifact

import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ArtifactRecordTest : FunSpec({

    val now = Instant.parse("2026-04-25T10:00:00Z")
    val expiry = Instant.parse("2030-01-01T00:00:00Z")

    fun managedArtifact() = ManagedArtifact(
        artifactId = "art_1",
        filename = "schema.sql",
        contentType = "application/sql",
        sizeBytes = 1024L,
        sha256 = "abc",
        createdAt = now,
        expiresAt = expiry,
    )

    fun resourceUri() = ServerResourceUri(
        tenantId = TenantId("acme"),
        kind = ResourceKind.ARTIFACTS,
        id = "art_1",
    )

    fun principal(
        principalId: String,
        effectiveTenant: String,
        admin: Boolean = false,
    ) = PrincipalContext(
        principalId = PrincipalId(principalId),
        homeTenantId = TenantId(effectiveTenant),
        effectiveTenantId = TenantId(effectiveTenant),
        allowedTenantIds = setOf(TenantId(effectiveTenant)),
        isAdmin = admin,
        auditSubject = principalId,
        authSource = AuthSource.OIDC,
        expiresAt = expiry,
    )

    test("OWNER visibility limits read to owner") {
        val record = ArtifactRecord(
            managedArtifact = managedArtifact(),
            kind = ArtifactKind.SCHEMA,
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.OWNER,
            resourceUri = resourceUri(),
        )
        record.isReadableBy(principal("alice", "acme")) shouldBe true
        record.isReadableBy(principal("bob", "acme")) shouldBe false
    }

    test("TENANT visibility allows any in-tenant principal") {
        val record = ArtifactRecord(
            managedArtifact = managedArtifact(),
            kind = ArtifactKind.PROFILE,
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.TENANT,
            resourceUri = resourceUri(),
        )
        record.isReadableBy(principal("bob", "acme")) shouldBe true
    }

    test("ADMIN visibility requires isAdmin in same tenant") {
        val record = ArtifactRecord(
            managedArtifact = managedArtifact(),
            kind = ArtifactKind.DIFF,
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.ADMIN,
            resourceUri = resourceUri(),
        )
        record.isReadableBy(principal("ops", "acme", admin = true)) shouldBe true
        record.isReadableBy(principal("ops", "acme")) shouldBe false
    }

    test("foreign tenant always denied") {
        val record = ArtifactRecord(
            managedArtifact = managedArtifact(),
            kind = ArtifactKind.DATA_EXPORT,
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.TENANT,
            resourceUri = resourceUri(),
        )
        record.isReadableBy(principal("alice", "umbrella")) shouldBe false
    }

    test("ArtifactKind has expected values") {
        ArtifactKind.entries.toSet() shouldBe setOf(
            ArtifactKind.SCHEMA,
            ArtifactKind.PROFILE,
            ArtifactKind.DIFF,
            ArtifactKind.DATA_EXPORT,
            ArtifactKind.UPLOAD_INPUT,
            ArtifactKind.OTHER,
        )
    }

    test("jobRef is optional and persists when set") {
        val record = ArtifactRecord(
            managedArtifact = managedArtifact(),
            kind = ArtifactKind.SCHEMA,
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.OWNER,
            resourceUri = resourceUri(),
            jobRef = "job_42",
        )
        record.jobRef shouldBe "job_42"
    }
})
