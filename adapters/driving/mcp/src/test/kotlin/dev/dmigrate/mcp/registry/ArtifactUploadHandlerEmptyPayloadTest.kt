package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.ValidationErrorException
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase F § 8.4 (F.4 2/3) — `sizeBytes=0` Single-Empty-Segment-
 * Upload fuer `job_input` mit nicht-Schema-`artifactKind` ist
 * gueltig. `schema_staging_readonly` oder `artifactKind=schema`
 * lehnen `sizeBytes=0` deterministisch mit `VALIDATION_ERROR` ab.
 */
class ArtifactUploadHandlerEmptyPayloadTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    // SHA-256 von 0 Bytes (RFC4634-Konstante).
    val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

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

    class Fixture(
        intent: String,
        artifactKind: ArtifactKind,
        sizeBytes: Long,
        sessionId: String = "ups-1",
    ) {
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val quotaStore = InMemoryQuotaStore()
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxUploadSegmentBytes = 8),
            options = ArtifactUploadHandler.Options(clock = clock),
        )

        init {
            sessionStore.save(
                UploadSession(
                    uploadSessionId = sessionId,
                    tenantId = tenant,
                    ownerPrincipalId = alice,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
                    artifactKind = artifactKind,
                    mimeType = "application/octet-stream",
                    sizeBytes = sizeBytes,
                    segmentTotal = 1,
                    checksumSha256 = emptySha,
                    uploadIntent = intent,
                    state = UploadSessionState.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                    idleTimeoutAt = now.plusSeconds(300),
                    absoluteLeaseExpiresAt = now.plusSeconds(3600),
                ),
            )
        }
    }

    fun args(s: String) = JsonParser.parseString(s).asJsonObject

    fun emptyFinalSegment(): String =
        """{"uploadSessionId":"ups-1","segmentIndex":1,"segmentOffset":0,""" +
            """"segmentTotal":1,"isFinalSegment":true,"segmentSha256":"$emptySha",""" +
            """"contentBase64":""}"""

    test("job_input + UPLOAD_INPUT + sizeBytes=0 + emptyFinalSegment -> Success") {
        val fx = Fixture(
            intent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
            artifactKind = ArtifactKind.UPLOAD_INPUT,
            sizeBytes = 0,
        )
        val outcome = fx.handler.handle(
            ToolCallContext("artifact_upload", args(emptyFinalSegment()), uploader),
        )
        val payload = JsonParser.parseString(
            outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!,
        ).asJsonObject
        payload.get("acceptedSegmentIndex").asInt shouldBe 1
        payload.get("bytesReceived").asLong shouldBe 0L

        // Plan § 8.4: das gespeicherte Segment hat 0 Bytes.
        val stored = fx.segmentStore.listSegments("ups-1").single()
        stored.sizeBytes shouldBe 0L
        stored.segmentSha256 shouldBe emptySha
    }

    test("schema_staging_readonly + sizeBytes=0 -> VALIDATION_ERROR (ohne Segmentwrite)") {
        val fx = Fixture(
            intent = ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY,
            artifactKind = ArtifactKind.SCHEMA,
            sizeBytes = 0,
        )
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ToolCallContext("artifact_upload", args(emptyFinalSegment()), uploader))
        }
        ex.violations.map { it.field } shouldContain "sizeBytes"
        // Defense: kein Segmentwrite trotz angereichertem Argumentpfad.
        fx.segmentStore.listSegments("ups-1") shouldBe emptyList()
    }

    test("job_input + SCHEMA artifactKind + sizeBytes=0 -> VALIDATION_ERROR") {
        val fx = Fixture(
            intent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
            artifactKind = ArtifactKind.SCHEMA,
            sizeBytes = 0,
        )
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ToolCallContext("artifact_upload", args(emptyFinalSegment()), uploader))
        }
        ex.violations.map { it.field } shouldContain "sizeBytes"
        fx.segmentStore.listSegments("ups-1") shouldBe emptyList()
    }
})
