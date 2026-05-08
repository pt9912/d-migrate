package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * Phase F § 8.4 (F.4 1/3) — pin't den intent-abhaengigen Scope-Check
 * im `artifact_upload`-Handler:
 *
 * - `job_input`-Session ohne `dmigrate:artifact:upload` -> 403 nach
 *   no-oracle Session-/Owner-Lookup, OHNE Segmentwrite, TTL-
 *   Erneuerung oder Quota-Aenderung.
 * - `schema_staging_readonly`-Session mit reinem `dmigrate:read`
 *   bleibt zulaessig (nutzt session-scoped read-only Berechtigung).
 * - Owner-Mismatch wird als Forbidden gemeldet, ohne dass der
 *   Scope-Check als Oracle missbraucht werden kann (Forbidden-
 *   Reason gibt nur den Owner-Mismatch zurueck).
 */
class ArtifactUploadHandlerIntentScopeTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun principal(scopes: Set<String>) = PrincipalContext(
        principalId = alice,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = scopes,
        isAdmin = false,
        auditSubject = "alice",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    class Fixture(
        intent: String,
        artifactKind: ArtifactKind = ArtifactKind.UPLOAD_INPUT,
        sessionId: String = "ups-1",
        sizeBytes: Long = 8,
    ) {
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val quotaStore = InMemoryQuotaStore()
        private val quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = quotaService,
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
                    checksumSha256 = "deadbeef".repeat(8),
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

    fun segmentArgs(bytes: ByteArray): String {
        val sha = sha256Hex(bytes)
        return """{"uploadSessionId":"ups-1","segmentIndex":1,"segmentOffset":0,""" +
            """"segmentTotal":1,"isFinalSegment":true,"segmentSha256":"$sha",""" +
            """"contentBase64":"${b64(bytes)}"}"""
    }

    test("job_input ohne dmigrate:artifact:upload -> 403 ohne Segmentwrite/TTL/Quota") {
        val fx = Fixture(intent = ArtifactUploadInitHandler.INTENT_JOB_INPUT)
        val readOnly = principal(setOf("dmigrate:read"))
        val ex = shouldThrow<ForbiddenPrincipalException> {
            fx.handler.handle(
                ToolCallContext("artifact_upload", args(segmentArgs("ABCDEFGH".toByteArray())), readOnly),
            )
        }
        ex.reason!! shouldContain "uploadIntent=job_input"

        // Plan § 8.4: kein Segmentwrite, keine TTL-Erneuerung, keine
        // Quota-Aenderung — die Session bleibt unveraendert.
        fx.segmentStore.listSegments("ups-1") shouldBe emptyList()
        val session = fx.sessionStore.findById(tenant, "ups-1")!!
        session.bytesReceived shouldBe 0L
        session.idleTimeoutAt shouldBe now.plusSeconds(300)
        fx.quotaStore.current(
            dev.dmigrate.server.ports.quota.QuotaKey(
                tenant,
                dev.dmigrate.server.ports.quota.QuotaDimension.PARALLEL_SEGMENT_WRITES,
                alice,
            ),
        ) shouldBe 0L
    }

    test("job_input mit dmigrate:artifact:upload -> kein 403 (Scope-Gate offen)") {
        val fx = Fixture(intent = ArtifactUploadInitHandler.INTENT_JOB_INPUT)
        val uploader = principal(setOf("dmigrate:artifact:upload"))
        // Ein gueltiges Final-Segment durchlaeuft den Pfad bis zum
        // Finaliser. Ohne Finaliser wird die Session legacy auf
        // COMPLETED gesetzt — wichtig fuer F.4 (1/3) ist nur, dass der
        // Scope-Check nicht vorher abbricht.
        val outcome = fx.handler.handle(
            ToolCallContext("artifact_upload", args(segmentArgs("ABCDEFGH".toByteArray())), uploader),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("schema_staging_readonly mit reinem dmigrate:read -> zulaessig") {
        val fx = Fixture(intent = ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY)
        val readOnly = principal(setOf("dmigrate:read"))
        val outcome = fx.handler.handle(
            ToolCallContext("artifact_upload", args(segmentArgs("ABCDEFGH".toByteArray())), readOnly),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("schema_staging_readonly mit dmigrate:artifact:upload -> auch zulaessig (staerkerer Scope)") {
        val fx = Fixture(intent = ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY)
        val uploader = principal(setOf("dmigrate:artifact:upload"))
        val outcome = fx.handler.handle(
            ToolCallContext("artifact_upload", args(segmentArgs("ABCDEFGH".toByteArray())), uploader),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("Admin-Principal umgeht den intent-abhaengigen Scope-Check") {
        val fx = Fixture(intent = ArtifactUploadInitHandler.INTENT_JOB_INPUT)
        val adminNoUploadScope = principal(setOf("dmigrate:read")).copy(isAdmin = true)
        val outcome = fx.handler.handle(
            ToolCallContext("artifact_upload", args(segmentArgs("ABCDEFGH".toByteArray())), adminNoUploadScope),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("Owner-Mismatch produziert Forbidden ohne Scope-Oracle (auch fuer richtigen Scope)") {
        val fx = Fixture(intent = ArtifactUploadInitHandler.INTENT_JOB_INPUT)
        val foreignPrincipal = PrincipalContext(
            principalId = PrincipalId("bob"),
            homeTenantId = tenant,
            effectiveTenantId = tenant,
            allowedTenantIds = setOf(tenant),
            scopes = setOf("dmigrate:artifact:upload"),
            isAdmin = false,
            auditSubject = "bob",
            authSource = AuthSource.SERVICE_ACCOUNT,
            expiresAt = Instant.MAX,
        )
        val ex = shouldThrow<ForbiddenPrincipalException> {
            fx.handler.handle(
                ToolCallContext("artifact_upload", args(segmentArgs("ABCDEFGH".toByteArray())), foreignPrincipal),
            )
        }
        ex.reason!! shouldContain "different principal"
    }
})
