package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.upload.DefaultJobInputFinalizer
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — pin't den COMPLETED-Quota-Swap:
 *
 * - Init-time reservierte `ACTIVE_UPLOAD_SESSIONS` (1) und
 *   `UPLOAD_BYTES` (session.sizeBytes) werden auf erfolgreicher
 *   Finalisierung freigegeben.
 * - Die durabel materialisierten Bytes wandern in die neue
 *   `STORED_ARTIFACT_BYTES`-Dimension (LF-012 / LN-011 / LN-017 / LN-027 wortlaeufig:
 *   "COMPLETED bucht gespeicherte Artefaktbytes genau einmal und
 *   gibt reservierte Upload-Bytes frei").
 */
class ArtifactUploadHandlerCompletedQuotaSwapTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    val uploader = PrincipalContext(
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

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    test("Erfolgreiche Finalisierung: ACTIVE_UPLOAD_SESSIONS + UPLOAD_BYTES freigegeben, STORED_ARTIFACT_BYTES gebucht") {
        val payload = "id,name\n1,Alice\n2,Bob\n".toByteArray()
        val sha = sha256Hex(payload)

        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val quotaStore = InMemoryQuotaStore()
        val quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }

        // Init-Reservierungen pro Hand simulieren.
        val sessionsKey = QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, alice)
        val bytesKey = QuotaKey(tenant, QuotaDimension.UPLOAD_BYTES, alice)
        val storedKey = QuotaKey(tenant, QuotaDimension.STORED_ARTIFACT_BYTES, alice)
        quotaService.reserve(sessionsKey, amount = 1)
        quotaService.reserve(bytesKey, amount = payload.size.toLong())

        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = quotaService,
            limits = McpLimitsConfig(maxUploadSegmentBytes = 64),
            options = ArtifactUploadHandler.Options(
                clock = clock,
                jobInputFinalizer = DefaultJobInputFinalizer(
                    artifactStore = artifactStore,
                    artifactContentStore = contentStore,
                    clock = clock,
                ),
            ),
        )

        val sessionId = "ups-quota-swap"
        sessionStore.save(
            UploadSession(
                uploadSessionId = sessionId,
                tenantId = tenant,
                ownerPrincipalId = alice,
                resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
                artifactKind = ArtifactKind.UPLOAD_INPUT,
                mimeType = "text/csv",
                sizeBytes = payload.size.toLong(),
                segmentTotal = 1,
                checksumSha256 = sha,
                uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                state = UploadSessionState.ACTIVE,
                createdAt = now,
                updatedAt = now,
                idleTimeoutAt = now.plusSeconds(300),
                absoluteLeaseExpiresAt = now.plusSeconds(3600),
            ),
        )

        // Pre-Check: Init-Quotas reserviert; STORED_ARTIFACT_BYTES = 0.
        quotaStore.current(sessionsKey) shouldBe 1L
        quotaStore.current(bytesKey) shouldBe payload.size.toLong()
        quotaStore.current(storedKey) shouldBe 0L

        // Final-Segment durchlaeuft Finalisierung.
        val body = """{"uploadSessionId":"$sessionId","segmentIndex":1,"segmentOffset":0,""" +
            """"segmentTotal":1,"isFinalSegment":true,"segmentSha256":"$sha",""" +
            """"contentBase64":"${b64(payload)}"}"""
        val outcome = handler.handle(
            ToolCallContext("artifact_upload", JsonParser.parseString(body), uploader),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()

        // LF-012 / LN-011 / LN-017 / LN-027 wortlaeufig:
        // - ACTIVE_UPLOAD_SESSIONS / UPLOAD_BYTES freigegeben
        quotaStore.current(sessionsKey) shouldBe 0L
        quotaStore.current(bytesKey) shouldBe 0L
        // - STORED_ARTIFACT_BYTES = payload.sizeBytes (genau einmal gebucht)
        quotaStore.current(storedKey) shouldBe payload.size.toLong()

        // Sanity: Session ist COMPLETED, Artefakt durabel.
        val session = sessionStore.findById(tenant, sessionId)!!
        session.state shouldBe UploadSessionState.COMPLETED
    }
})
