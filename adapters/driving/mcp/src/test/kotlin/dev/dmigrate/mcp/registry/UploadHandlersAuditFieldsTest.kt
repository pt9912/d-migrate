package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.audit.AuditFields
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — pin't, dass die Upload-Handler die
 * `AuditFields` fuer Around-/Finally-Audit befuellen, ohne rohe
 * Uploadbytes oder Approval-Tokens zu durchschleifen.
 *
 * Strukturelle Garantie: das `AuditEvent`-Record-Schema kennt nur
 * `payloadFingerprint` (Hex) und `resourceRefs` (URI-Strings) — es
 * gibt keinen Slot fuer rohe Uploadbytes oder Tokens, sodass Audit-
 * Konsumenten typsicher keine sensiblen Daten sehen koennen.
 *
 * Diese Tests pruefen, dass die Handler den `resourceRefs`-Slot mit
 * der Session-URI fuellen (vertragskonformes "tracable" ohne Bytes-
 * Leak) und nicht versehentlich `contentBase64` oder `approvalToken`
 * in die Felder schreiben.
 */
class UploadHandlersAuditFieldsTest : FunSpec({

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

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    test("artifact_upload_init (LF-012 / LN-038 readonly) populates resourceRefs mit Session-URI") {
        val sessionStore = InMemoryUploadSessionStore()
        val quotaStore = InMemoryQuotaStore()
        val handler = ArtifactUploadInitHandler(
            sessionStore = sessionStore,
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            options = ArtifactUploadInitHandler.Options(
                clock = clock,
                sessionIdGenerator = { "ups-audit-1" },
            ),
        )
        val auditFields = AuditFields()
        val args = JsonParser.parseString(
            """{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":1024,""" +
                """"checksumSha256":"${"a".repeat(64)}"}""",
        )
        handler.handle(
            ToolCallContext(
                name = "artifact_upload_init",
                arguments = args,
                principal = principal,
                auditFields = auditFields,
            ),
        )
        // LF-010 / LF-013 / LN-009 / LN-011 Akzeptanz: tracable URI im Audit, kein
        // contentBase64/approvalToken durch das Audit-Schema
        // ueberhaupt erreichbar.
        auditFields.resourceRefs shouldContain "dmigrate://tenants/acme/upload-sessions/ups-audit-1"
    }

    test("artifact_upload (Segment) populates resourceRefs mit Session-URI") {
        val payload = "ABCDEFGH".toByteArray()
        val sha = sha256Hex(payload)
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxUploadSegmentBytes = 8),
            options = ArtifactUploadHandler.Options(clock = clock),
        )
        val sessionId = "ups-audit-2"
        sessionStore.save(
            UploadSession(
                uploadSessionId = sessionId,
                tenantId = tenant,
                ownerPrincipalId = alice,
                resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
                artifactKind = ArtifactKind.UPLOAD_INPUT,
                mimeType = "application/octet-stream",
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
        val auditFields = AuditFields()
        val args = JsonParser.parseString(
            """{"uploadSessionId":"$sessionId","segmentIndex":1,"segmentOffset":0,""" +
                """"segmentTotal":1,"isFinalSegment":true,"segmentSha256":"$sha",""" +
                """"contentBase64":"${b64(payload)}"}""",
        )
        handler.handle(
            ToolCallContext(
                name = "artifact_upload",
                arguments = args,
                principal = principal,
                auditFields = auditFields,
            ),
        )
        auditFields.resourceRefs shouldContain "dmigrate://tenants/acme/upload-sessions/$sessionId"
    }

    test("artifact_upload_abort (Owner-Self) populates resourceRefs mit Session-URI") {
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val handler = ArtifactUploadAbortHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            clock = clock,
        )
        val sessionId = "ups-audit-3"
        sessionStore.save(
            UploadSession(
                uploadSessionId = sessionId,
                tenantId = tenant,
                ownerPrincipalId = alice,
                resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
                artifactKind = ArtifactKind.UPLOAD_INPUT,
                mimeType = "application/octet-stream",
                sizeBytes = 64,
                segmentTotal = 1,
                checksumSha256 = "deadbeef".repeat(8),
                uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                state = UploadSessionState.ACTIVE,
                createdAt = now,
                updatedAt = now,
                idleTimeoutAt = now.plusSeconds(300),
                absoluteLeaseExpiresAt = now.plusSeconds(3600),
            ),
        )
        val auditFields = AuditFields()
        handler.handle(
            ToolCallContext(
                name = "artifact_upload_abort",
                arguments = JsonParser.parseString("""{"uploadSessionId":"$sessionId"}"""),
                principal = principal,
                auditFields = auditFields,
            ),
        )
        auditFields.resourceRefs shouldContain "dmigrate://tenants/acme/upload-sessions/$sessionId"
    }

    test("AuditFields traegt KEINE rohen Uploadbytes oder ApprovalToken (strukturelle Garantie via AuditEvent-Schema)") {
        // LF-012 / LN-011 / LN-017 / LN-027 wortlaeufig: "Audit enthaelt keine rohen
        // Uploadbytes oder Approval-Tokens".
        //
        // Strukturelle Garantie: AuditEvent kennt nur die Felder
        // requestId, outcome, startedAt, toolName, tenantId,
        // principalId, errorCode, payloadFingerprint, resourceRefs,
        // durationMs. Es gibt keine Slot fuer rohe Bytes oder
        // Tokens.
        val fields = dev.dmigrate.server.core.audit.AuditEvent::class.java.declaredFields.map { it.name }
        // Defensive: rohe Slot-Namen, die NIEMALS auftauchen duerfen.
        val forbidden = setOf("contentBase64", "approvalToken", "rawBytes", "secret")
        for (forbiddenField in forbidden) {
            (forbiddenField in fields) shouldBe false
        }
        // Sanity: erwartete Felder sind vorhanden.
        fields shouldContain "payloadFingerprint"
        fields shouldContain "resourceRefs"
    }
})
