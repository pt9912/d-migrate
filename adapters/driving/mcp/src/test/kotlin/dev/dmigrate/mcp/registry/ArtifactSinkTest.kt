package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val ACME = TenantId("acme")

private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private val FIXED_NOW = Instant.parse("2026-05-02T12:00:00Z")
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

private fun sink(
    artifactStore: InMemoryArtifactStore = InMemoryArtifactStore(),
    contentStore: InMemoryArtifactContentStore = InMemoryArtifactContentStore(),
    ttl: Duration = Duration.ofHours(24),
): Triple<ArtifactSink, InMemoryArtifactStore, InMemoryArtifactContentStore> =
    Triple(
        ArtifactSink(artifactStore, contentStore, FIXED_CLOCK, ttl),
        artifactStore,
        contentStore,
    )

class ArtifactSinkTest : FunSpec({

    test("writeReadOnly persists bytes, registers an ArtifactRecord, and returns a tenant-scoped URI") {
        val (out, artifactStore, contentStore) = sink()
        val content = "CREATE TABLE t (id BIGINT);".toByteArray()
        val uri = out.writeReadOnly(
            principal = PRINCIPAL,
            kind = ArtifactKind.SCHEMA,
            contentType = "text/plain",
            filename = "ddl.sql",
            content = content,
            maxArtifactBytes = 1_000_000,
        )
        uri.tenantId shouldBe ACME
        uri.kind shouldBe ResourceKind.ARTIFACTS
        uri.id shouldStartWith "art-"

        contentStore.exists(uri.id) shouldBe true
        val record = artifactStore.findById(ACME, uri.id)!!
        record.kind shouldBe ArtifactKind.SCHEMA
        record.tenantId shouldBe ACME
        record.ownerPrincipalId shouldBe PRINCIPAL.principalId
        record.managedArtifact.sizeBytes shouldBe content.size.toLong()
        record.managedArtifact.contentType shouldBe "text/plain"
        record.managedArtifact.filename shouldBe "ddl.sql"
        record.managedArtifact.createdAt shouldBe FIXED_NOW
        record.managedArtifact.expiresAt shouldBe FIXED_NOW.plus(Duration.ofHours(24))
    }

    test("two writes with different content produce different artifact ids") {
        val (out, _, _) = sink()
        val a = out.writeReadOnly(
            PRINCIPAL, ArtifactKind.SCHEMA, "text/plain", "a.sql",
            "first".toByteArray(), maxArtifactBytes = 1_000,
        )
        val b = out.writeReadOnly(
            PRINCIPAL, ArtifactKind.SCHEMA, "text/plain", "b.sql",
            "second".toByteArray(), maxArtifactBytes = 1_000,
        )
        (a.id != b.id) shouldBe true
    }

    test("oversize content raises PAYLOAD_TOO_LARGE before any store call") {
        // Defensive: handlers pass `maxArtifactUploadBytes` so the
        // sink rejects payloads that would otherwise blow past Phase-A
        // upload quotas. The store must NOT see the bytes.
        val (out, artifactStore, contentStore) = sink()
        val ex = shouldThrow<PayloadTooLargeException> {
            out.writeReadOnly(
                PRINCIPAL, ArtifactKind.SCHEMA, "text/plain", "huge.sql",
                content = ByteArray(10),
                maxArtifactBytes = 4,
            )
        }
        ex.actualBytes shouldBe 10L
        ex.maxBytes shouldBe 4L
        artifactStore.list(ACME, dev.dmigrate.server.core.pagination.PageRequest(20))
            .items shouldBe emptyList()
        // No way to enumerate contentStore directly — but the lack of
        // a registered record is sufficient evidence that nothing
        // landed downstream of the size guard.
    }

    test("sha256 in the registered record matches the bytes' hash") {
        val (out, artifactStore, _) = sink()
        val content = "CREATE TABLE t (id BIGINT);".toByteArray()
        val uri = out.writeReadOnly(
            PRINCIPAL, ArtifactKind.SCHEMA, "text/plain", "ddl.sql",
            content, maxArtifactBytes = 1_000_000,
        )
        val record = artifactStore.findById(ACME, uri.id)!!
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString("") { "%02x".format(it) }
        record.managedArtifact.sha256 shouldBe expected
    }
})
