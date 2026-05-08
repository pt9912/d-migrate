package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.upload.JobInputFinalizer
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
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
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * LF-010 / LF-013 / LN-009 / LN-011 § 8.6 (F.6 1/3) — `StreamingFinalizer.persistFailedOutcomeBestEffort`
 * gibt die Init-Quotas frei, sobald der FAILED-Outcome durabel landet.
 * Dieses Pin't den Validator-Fehler-Pfad (job_input mit Stub-Finaliser,
 * der absichtlich eine Validation wirft) — analog zum F.4-(3/3)-
 * oversize-Pfad, aber spaeter in der Pipeline (nach Claim + Assembly).
 */
class ArtifactUploadHandlerFinalisationFailureQuotaTest : FunSpec({

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

    test("Validation-Failure im Finaliser gibt ACTIVE_UPLOAD_SESSIONS + UPLOAD_BYTES frei") {
        val payload = "validate-me".toByteArray()
        val sha = sha256Hex(payload)
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val quotaStore = InMemoryQuotaStore()
        val quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
        // Init-Reservierungen pro Hand simulieren.
        val sessionsKey = QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, alice)
        val bytesKey = QuotaKey(tenant, QuotaDimension.UPLOAD_BYTES, alice)
        quotaService.reserve(sessionsKey, amount = 1)
        quotaService.reserve(bytesKey, amount = payload.size.toLong())

        // Stub-Finaliser, der eine ValidationErrorException wirft —
        // simuliert z.B. Schema-Parse-Fehler oder ungueltige
        // Artefakt-Bytes.
        val failingFinalizer = JobInputFinalizer { _, _, _, _, _ ->
            throw ValidationErrorException(
                listOf(ValidationViolation("payload", "stub: invalid")),
            )
        }
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = quotaService,
            limits = McpLimitsConfig(maxUploadSegmentBytes = 64),
            options = ArtifactUploadHandler.Options(
                clock = clock,
                jobInputFinalizer = failingFinalizer,
            ),
        )

        val sessionId = "ups-fail-finalise"
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

        val body = """{"uploadSessionId":"$sessionId","segmentIndex":1,"segmentOffset":0,""" +
            """"segmentTotal":1,"isFinalSegment":true,"segmentSha256":"$sha",""" +
            """"contentBase64":"${Base64.getEncoder().encodeToString(payload)}"}"""
        // Final-Segment laeuft durch claim+assembly+IN_PROGRESS-Outcome,
        // dann wirft der Stub-Finaliser. StreamingFinalizer persistiert
        // einen FAILED-Outcome + ABORTED-Transition + Quota-Release.
        shouldThrow<ValidationErrorException> {
            handler.handle(
                ToolCallContext("artifact_upload", JsonParser.parseString(body), uploader),
            )
        }

        // LF-012 / LN-011 / LN-017 / LN-027: Init-Quotas sind nach Validation-Failure freigegeben.
        quotaStore.current(sessionsKey) shouldBe 0L
        quotaStore.current(bytesKey) shouldBe 0L

        // Session traegt FAILED-Outcome + ABORTED.
        val session = sessionStore.findById(tenant, sessionId).shouldNotBeNull()
        session.state shouldBe UploadSessionState.ABORTED
        session.finalizationOutcome.shouldNotBeNull().sanitizedErrorCode shouldBe "VALIDATION_ERROR"
    }
})
