package dev.dmigrate.mcp.upload

import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.AssembledUploadPayload
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * LF-010 / LF-013 / LN-009 / LN-011 § 8.5 (F.5 1/3) — pin't den `job_input`-Finaliser in
 * Isolation: Bytes landen in [dev.dmigrate.server.ports.ArtifactContentStore],
 * der Metadaten-Record im [dev.dmigrate.server.ports.ArtifactStore],
 * Replay-Idempotenz auf `AlreadyExists` mit gleichem SHA bleibt
 * deterministisch.
 */
class JobInputFinalizerTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    val principal = PrincipalContext(
        principalId = alice,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:artifact:upload"),
        isAdmin = false,
        auditSubject = "alice",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun session(
        sessionId: String = "ups-1",
        artifactKind: ArtifactKind = ArtifactKind.UPLOAD_INPUT,
        mimeType: String = "application/octet-stream",
        sizeBytes: Long = 16,
    ) = UploadSession(
        uploadSessionId = sessionId,
        tenantId = tenant,
        ownerPrincipalId = alice,
        resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
        artifactKind = artifactKind,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        segmentTotal = 1,
        checksumSha256 = "deadbeef".repeat(8),
        uploadIntent = "job_input",
        state = UploadSessionState.FINALIZING,
        createdAt = now,
        updatedAt = now,
        idleTimeoutAt = now.plusSeconds(300),
        absoluteLeaseExpiresAt = now.plusSeconds(3600),
    )

    class Fixture {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val finalizer = DefaultJobInputFinalizer(
            artifactStore = artifactStore,
            artifactContentStore = contentStore,
            clock = clock,
        )
    }

    test("complete writes bytes to ArtifactContentStore + saves ArtifactRecord with session metadata") {
        val fx = Fixture()
        val bytes = "csv-payload-bytes".toByteArray()
        val payload = AssembledUploadPayload.fromBytes(bytes, sha256 = sha256Hex(bytes))
        val artifactId = "art-deadbeef"

        val uri = fx.finalizer.complete(
            session = session(sizeBytes = bytes.size.toLong(), mimeType = "text/csv"),
            principal = principal,
            payload = payload,
            artifactId = artifactId,
            format = "csv",
        )

        // ResourceUri zeigt auf das Artefakt im richtigen Tenant.
        uri shouldBe ServerResourceUri(tenant, ResourceKind.ARTIFACTS, artifactId)

        // Bytes landen in ArtifactContentStore.
        fx.contentStore.exists(artifactId) shouldBe true

        // Metadaten-Record traegt session-spezifische Felder.
        val record = fx.artifactStore.findById(tenant, artifactId).shouldNotBeNull()
        record.kind shouldBe ArtifactKind.UPLOAD_INPUT
        record.tenantId shouldBe tenant
        record.ownerPrincipalId shouldBe alice
        record.managedArtifact.contentType shouldBe "text/csv"
        record.managedArtifact.sizeBytes shouldBe bytes.size.toLong()
        record.managedArtifact.sha256 shouldBe sha256Hex(bytes)
        // Source-Session-Id ist in den filename eingebaut, sodass der
        // Operator ohne neue Spalte tracen kann.
        record.managedArtifact.filename shouldBe "upload-ups-1-$artifactId.bin"
    }

    test("respects session.artifactKind (e.g. SCHEMA fuer SCHEMA-Artefakte aus job_input)") {
        // Sehr selten: ein job_input mit artifactKind=SCHEMA waere
        // F.4-(2/3)-mässig bei sizeBytes=0 abgelehnt, aber bei
        // sizeBytes > 0 darf er existieren — der Finaliser uebernimmt
        // dann den Kind in den Record.
        val fx = Fixture()
        val bytes = """{"name":"S"}""".toByteArray()
        val payload = AssembledUploadPayload.fromBytes(bytes, sha256 = sha256Hex(bytes))
        fx.finalizer.complete(
            session = session(artifactKind = ArtifactKind.SCHEMA, sizeBytes = bytes.size.toLong()),
            principal = principal,
            payload = payload,
            artifactId = "art-schema-1",
            format = "json",
        )
        fx.artifactStore.findById(tenant, "art-schema-1")!!.kind shouldBe ArtifactKind.SCHEMA
    }

    test("Replay mit gleichem artifactId + gleichem SHA -> No-Op (idempotent)") {
        val fx = Fixture()
        val bytes = "stable-payload".toByteArray()
        val sha = sha256Hex(bytes)
        val payload1 = AssembledUploadPayload.fromBytes(bytes, sha)
        val payload2 = AssembledUploadPayload.fromBytes(bytes, sha)
        val artifactId = "art-replay"

        val s = session(sizeBytes = bytes.size.toLong())
        fx.finalizer.complete(s, principal, payload1, artifactId, "csv")
        // Zweiter Aufruf: identische Bytes, identischer artifactId.
        // LF-012 / LN-011 / LN-017 / LN-027: "finalisiertes Artefakt ist immutable" + LF-012 / LN-027 / LN-028 / LN-038
        // Idempotency.
        fx.finalizer.complete(s, principal, payload2, artifactId, "csv")

        // Genau ein Record (kein zweiter).
        fx.artifactStore.findById(tenant, artifactId).shouldNotBeNull()
    }

    test("Replay mit gleichem artifactId aber abweichendem SHA -> InternalAgentError (harter Konflikt)") {
        val fx = Fixture()
        val bytes1 = "first-payload".toByteArray()
        val bytes2 = "second-payload".toByteArray()
        val artifactId = "art-conflict"
        val s = session(sizeBytes = bytes1.size.toLong())

        fx.finalizer.complete(
            s, principal,
            AssembledUploadPayload.fromBytes(bytes1, sha256Hex(bytes1)),
            artifactId, "csv",
        )

        // Drift unter dem deterministischen artifactId: das darf nicht
        // unbemerkt durchgehen.
        shouldThrow<InternalAgentErrorException> {
            fx.finalizer.complete(
                s, principal,
                AssembledUploadPayload.fromBytes(bytes2, sha256Hex(bytes2)),
                artifactId, "csv",
            )
        }
    }
})
