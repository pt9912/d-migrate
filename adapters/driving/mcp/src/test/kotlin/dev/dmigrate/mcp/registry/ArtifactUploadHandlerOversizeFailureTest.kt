package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.FinalizationOutcomeStatus
import dev.dmigrate.server.core.upload.UploadSegment
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
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * LF-010 / LF-013 / LN-009 / LN-011 § 8.4 (F.4 3/3) — pin't den terminalen Failure-Pfad fuer
 * oversize Segmente:
 *
 * - oversize -> Session terminal `ABORTED` mit FailureOutcome
 *   (`status=FAILED`, `sanitizedErrorCode=PAYLOAD_TOO_LARGE`).
 * - Init-Quotas (ACTIVE_UPLOAD_SESSIONS, UPLOAD_BYTES) werden
 *   freigegeben.
 * - Cleanup loescht teils-staged Segmente.
 * - Retry desselben oversize Aufrufs replayed via
 *   `replayFailedOutcomeIfAvailable` einen `PayloadTooLargeException`
 *   (idempotenter Replay).
 */
class ArtifactUploadHandlerOversizeFailureTest : FunSpec({

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

    class Fixture(
        sizeBytes: Long = 32,
        segmentTotal: Int = 4,
        sessionId: String = "ups-1",
    ) {
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val quotaStore = InMemoryQuotaStore()
        // Init-Style Reservierungen: 1 Session-Slot + sizeBytes Bytes,
        // damit der Failure-Path ueberhaupt Quotas zum Freigeben hat.
        val sessionsKey = QuotaKey(tenant, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, alice)
        val bytesKey = QuotaKey(tenant, QuotaDimension.UPLOAD_BYTES, alice)
        val quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }

        init {
            // Init-Style Reservierungen pro Hand simulieren.
            quotaService.reserve(sessionsKey, amount = 1)
            quotaService.reserve(bytesKey, amount = sizeBytes)
            sessionStore.save(
                UploadSession(
                    uploadSessionId = sessionId,
                    tenantId = tenant,
                    ownerPrincipalId = alice,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
                    artifactKind = ArtifactKind.UPLOAD_INPUT,
                    mimeType = "application/octet-stream",
                    sizeBytes = sizeBytes,
                    segmentTotal = segmentTotal,
                    checksumSha256 = "deadbeef".repeat(8),
                    uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                    state = UploadSessionState.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                    idleTimeoutAt = now.plusSeconds(300),
                    absoluteLeaseExpiresAt = now.plusSeconds(3600),
                ),
            )
        }

        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = quotaService,
            limits = McpLimitsConfig(maxUploadSegmentBytes = 8),
            options = ArtifactUploadHandler.Options(clock = clock),
        )
    }

    fun args(s: String) = JsonParser.parseString(s).asJsonObject

    fun segmentArgs(
        sessionIndex: Int,
        offset: Long,
        total: Int,
        final: Boolean,
        bytes: ByteArray,
    ): String {
        val sha = sha256Hex(bytes)
        return """{"uploadSessionId":"ups-1","segmentIndex":$sessionIndex,"segmentOffset":$offset,""" +
            """"segmentTotal":$total,"isFinalSegment":$final,"segmentSha256":"$sha",""" +
            """"contentBase64":"${b64(bytes)}"}"""
    }

    test("oversize Segment markiert Session ABORTED mit FAILED-Outcome (PAYLOAD_TOO_LARGE)") {
        val fx = Fixture()
        val tooLarge = ByteArray(16) { 'A'.code.toByte() } // > maxUploadSegmentBytes=8
        val ex = shouldThrow<PayloadTooLargeException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(segmentArgs(1, 0, 4, false, tooLarge)),
                    uploader,
                ),
            )
        }
        ex.actualBytes shouldBe 16L
        ex.maxBytes shouldBe 8L

        val session = fx.sessionStore.findById(tenant, "ups-1")!!
        session.state shouldBe UploadSessionState.ABORTED
        val outcome = session.finalizationOutcome.shouldNotBeNull()
        outcome.status shouldBe FinalizationOutcomeStatus.FAILED
        outcome.sanitizedErrorCode shouldBe "PAYLOAD_TOO_LARGE"
    }

    test("oversize Segment gibt ACTIVE_UPLOAD_SESSIONS + UPLOAD_BYTES Quotas frei") {
        val fx = Fixture(sizeBytes = 32)
        // Vor dem Aufruf: 1 Session-Slot + 32 Bytes belegt.
        fx.quotaStore.current(fx.sessionsKey) shouldBe 1L
        fx.quotaStore.current(fx.bytesKey) shouldBe 32L

        val tooLarge = ByteArray(16) { 'B'.code.toByte() }
        shouldThrow<PayloadTooLargeException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(segmentArgs(1, 0, 4, false, tooLarge)),
                    uploader,
                ),
            )
        }

        // LF-012 / LN-011 / LN-017 / LN-027: beide Init-Quotas freigegeben.
        fx.quotaStore.current(fx.sessionsKey) shouldBe 0L
        fx.quotaStore.current(fx.bytesKey) shouldBe 0L
    }

    test("oversize Segment loescht zuvor gestaged Segmente (Cleanup)") {
        val fx = Fixture(sizeBytes = 24, segmentTotal = 3)
        // Pre-staging: ein Segment ist schon erfolgreich abgelegt.
        val first = ByteArray(8) { 'A'.code.toByte() }
        fx.segmentStore.writeSegment(
            UploadSegment(
                uploadSessionId = "ups-1",
                segmentIndex = 1,
                segmentOffset = 0,
                sizeBytes = first.size.toLong(),
                segmentSha256 = sha256Hex(first),
            ),
            ByteArrayInputStream(first),
        )
        fx.segmentStore.listSegments("ups-1").size shouldBe 1

        val tooLarge = ByteArray(16) { 'B'.code.toByte() }
        shouldThrow<PayloadTooLargeException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(segmentArgs(2, 8, 3, false, tooLarge)),
                    uploader,
                ),
            )
        }

        // Cleanup: alle Segmente sind weg.
        fx.segmentStore.listSegments("ups-1") shouldBe emptyList()
    }

    test("Retry desselben oversize Calls replayed PayloadTooLargeException (idempotent)") {
        val fx = Fixture()
        val tooLarge = ByteArray(16) { 'C'.code.toByte() }
        val first = shouldThrow<PayloadTooLargeException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(segmentArgs(1, 0, 4, false, tooLarge)),
                    uploader,
                ),
            )
        }
        first.actualBytes shouldBe 16L

        // Zweiter Aufruf: Session ist ABORTED + FAILED outcome.
        // `replayFailedOutcomeIfAvailable` muss denselben
        // sanitisierten Fehler erneut werfen — keine
        // UploadSessionAbortedException, kein InternalAgentError.
        shouldThrow<PayloadTooLargeException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(segmentArgs(1, 0, 4, false, tooLarge)),
                    uploader,
                ),
            )
        }
    }

    test("oversize gefolgt von kleinerem Retry liefert weiter PayloadTooLargeException") {
        // LF-012 / LN-011 / LN-017 / LN-027 "abweichende Wiederholung deterministisch
        // ablehnen": ein Retry mit anderem (kleinerem) Segment darf
        // den FAILED-Outcome nicht ueberschreiben.
        val fx = Fixture()
        shouldThrow<PayloadTooLargeException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(segmentArgs(1, 0, 4, false, ByteArray(16) { 'X'.code.toByte() })),
                    uploader,
                ),
            )
        }

        // Kleineres Segment: passt in maxUploadSegmentBytes=8 — darf
        // aber nicht annehmbar sein, weil die Session ABORTED ist.
        shouldThrow<PayloadTooLargeException> {
            fx.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(segmentArgs(1, 0, 4, false, ByteArray(8) { 'Y'.code.toByte() })),
                    uploader,
                ),
            )
        }
    }
})
