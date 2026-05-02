package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.UploadSessionAbortedException
import dev.dmigrate.server.application.error.UploadSessionExpiredException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val ACME = TenantId("acme")
private val ALICE = PrincipalId("alice")
private val BOB = PrincipalId("bob")

private val PRINCIPAL = PrincipalContext(
    principalId = ALICE,
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:artifact:upload"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private val FIXED_NOW = Instant.parse("2026-05-02T12:00:00Z")
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

private class AbortFixture(
    val handler: ArtifactUploadAbortHandler,
    val sessionStore: InMemoryUploadSessionStore,
    val segmentStore: InMemoryUploadSegmentStore,
    val quotaStore: InMemoryQuotaStore,
)

private fun fixture(): AbortFixture {
    val sessionStore = InMemoryUploadSessionStore()
    val segmentStore = InMemoryUploadSegmentStore()
    val quotaStore = InMemoryQuotaStore()
    val handler = ArtifactUploadAbortHandler(
        sessionStore = sessionStore,
        segmentStore = segmentStore,
        quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
        clock = FIXED_CLOCK,
        requestIdProvider = { "req-deadbeef" },
    )
    return AbortFixture(handler, sessionStore, segmentStore, quotaStore)
}

private fun stageSession(
    f: AbortFixture,
    sessionId: String = "ups-1",
    sizeBytes: Long = 1024,
    state: UploadSessionState = UploadSessionState.ACTIVE,
    owner: PrincipalId = ALICE,
) {
    f.sessionStore.save(
        UploadSession(
            uploadSessionId = sessionId,
            tenantId = ACME,
            ownerPrincipalId = owner,
            resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, sessionId),
            artifactKind = ArtifactKind.SCHEMA,
            mimeType = "application/octet-stream",
            sizeBytes = sizeBytes,
            segmentTotal = 1,
            checksumSha256 = "0".repeat(64),
            uploadIntent = ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY,
            state = state,
            createdAt = FIXED_NOW,
            updatedAt = FIXED_NOW,
            idleTimeoutAt = FIXED_NOW.plusSeconds(300),
            absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(3600),
        ),
    )
}

private fun stageSegment(f: AbortFixture, sessionId: String, index: Int, bytes: ByteArray) {
    f.segmentStore.writeSegment(
        UploadSegment(
            uploadSessionId = sessionId,
            segmentIndex = index,
            segmentOffset = 0L,
            sizeBytes = bytes.size.toLong(),
            segmentSha256 = "0".repeat(64),
        ),
        ByteArrayInputStream(bytes),
    )
}

private fun args(content: String): JsonObject = JsonParser.parseString(content).asJsonObject

private fun parsePayload(outcome: ToolCallOutcome): JsonObject {
    val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
    return JsonParser.parseString(text).asJsonObject
}

/**
 * Stages an ACTIVE session, wraps the store with the racing helper,
 * and runs the abort handler. Used by the four `transition race`
 * tests to keep the per-branch body to one assertion line.
 */
private fun invokeAbortWithRacingTransition(illegalFrom: UploadSessionState) {
    val f = fixture()
    stageSession(f, "ups-1")
    val racing = ArtifactUploadAbortHandler(
        sessionStore = racingTransitionStore(f.sessionStore, illegalFrom = illegalFrom),
        segmentStore = f.segmentStore,
        quotaService = DefaultQuotaService(f.quotaStore) { Long.MAX_VALUE },
        clock = FIXED_CLOCK,
        requestIdProvider = { "req-x" },
    )
    racing.handle(
        ToolCallContext(
            "artifact_upload_abort",
            args("""{"uploadSessionId":"ups-1"}"""),
            PRINCIPAL,
        ),
    )
}

class ArtifactUploadAbortHandlerTest : FunSpec({

    test("happy path: own ACTIVE session is transitioned to ABORTED, segments deleted, response shape matches spec") {
        val f = fixture()
        stageSession(f, "ups-1")
        stageSegment(f, "ups-1", index = 1, bytes = "abcd".toByteArray())
        stageSegment(f, "ups-1", index = 2, bytes = "efgh".toByteArray())
        val outcome = f.handler.handle(
            ToolCallContext("artifact_upload_abort", args("""{"uploadSessionId":"ups-1"}"""), PRINCIPAL),
        )
        val json = parsePayload(outcome)
        json.get("uploadSessionId").asString shouldBe "ups-1"
        json.get("uploadSessionState").asString shouldBe "ABORTED"
        json.get("segmentsDeleted").asInt shouldBe 2
        json.getAsJsonObject("executionMeta").get("requestId").asString shouldBe "req-deadbeef"
        f.sessionStore.findById(ACME, "ups-1")!!.state shouldBe UploadSessionState.ABORTED
        f.segmentStore.listSegments("ups-1") shouldBe emptyList()
    }

    test("missing uploadSessionId throws VALIDATION_ERROR") {
        val f = fixture()
        shouldThrow<ValidationErrorException> {
            f.handler.handle(ToolCallContext("artifact_upload_abort", args("""{}"""), PRINCIPAL))
        }
    }

    test("unknown sessionId in own tenant throws RESOURCE_NOT_FOUND (no-oracle)") {
        val f = fixture()
        shouldThrow<ResourceNotFoundException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-missing"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("foreign-principal session in same tenant throws FORBIDDEN_PRINCIPAL") {
        val f = fixture()
        stageSession(f, "ups-bob", owner = BOB)
        val ex = shouldThrow<ForbiddenPrincipalException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-bob"}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.principalId shouldBe ALICE
        // Session must remain ACTIVE — a foreign-abort attempt
        // can't transition someone else's session.
        f.sessionStore.findById(ACME, "ups-bob")!!.state shouldBe UploadSessionState.ACTIVE
    }

    test("already-ABORTED session throws UPLOAD_SESSION_ABORTED") {
        val f = fixture()
        stageSession(f, "ups-1", state = UploadSessionState.ABORTED)
        shouldThrow<UploadSessionAbortedException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("EXPIRED session throws UPLOAD_SESSION_EXPIRED") {
        val f = fixture()
        stageSession(f, "ups-1", state = UploadSessionState.EXPIRED)
        shouldThrow<UploadSessionExpiredException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("COMPLETED session can't be aborted — IDEMPOTENCY_CONFLICT") {
        val f = fixture()
        stageSession(f, "ups-1", state = UploadSessionState.COMPLETED)
        shouldThrow<IdempotencyConflictException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload_abort",
                    args("""{"uploadSessionId":"ups-1"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("abort releases ACTIVE_UPLOAD_SESSIONS and UPLOAD_BYTES quotas (AP 6.7 commitments are reversed)") {
        // Pre-charge the quota counters as if AP-6.7 had committed
        // them for this session, then verify the abort releases
        // both back to zero.
        val f = fixture()
        stageSession(f, "ups-1", sizeBytes = 4096)
        val sessionsKey = QuotaKey(ACME, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, ALICE)
        val bytesKey = QuotaKey(ACME, QuotaDimension.UPLOAD_BYTES, ALICE)
        f.quotaStore.reserve(sessionsKey, amount = 1, limit = Long.MAX_VALUE)
        f.quotaStore.reserve(bytesKey, amount = 4096, limit = Long.MAX_VALUE)
        f.quotaStore.current(sessionsKey) shouldBe 1L
        f.quotaStore.current(bytesKey) shouldBe 4096L

        f.handler.handle(
            ToolCallContext(
                "artifact_upload_abort",
                args("""{"uploadSessionId":"ups-1"}"""),
                PRINCIPAL,
            ),
        )

        f.quotaStore.current(sessionsKey) shouldBe 0L
        f.quotaStore.current(bytesKey) shouldBe 0L
    }

    test("response carries application/json mime type") {
        val f = fixture()
        stageSession(f, "ups-1")
        val outcome = f.handler.handle(
            ToolCallContext("artifact_upload_abort", args("""{"uploadSessionId":"ups-1"}"""), PRINCIPAL),
        )
        val content = (outcome as ToolCallOutcome.Success).content.single()
        content.mimeType shouldBe "application/json"
    }

    test("transition race: concurrent ABORTED surfaces UPLOAD_SESSION_ABORTED") {
        shouldThrow<UploadSessionAbortedException> {
            invokeAbortWithRacingTransition(illegalFrom = UploadSessionState.ABORTED)
        }
    }

    test("transition race: concurrent EXPIRED surfaces UPLOAD_SESSION_EXPIRED") {
        shouldThrow<UploadSessionExpiredException> {
            invokeAbortWithRacingTransition(illegalFrom = UploadSessionState.EXPIRED)
        }
    }

    test("transition race: concurrent COMPLETED surfaces IDEMPOTENCY_CONFLICT") {
        shouldThrow<IdempotencyConflictException> {
            invokeAbortWithRacingTransition(illegalFrom = UploadSessionState.COMPLETED)
        }
    }

    test("transition race: ACTIVE→ACTIVE rejection (broken store contract) surfaces InternalAgentError") {
        // Defensive: ACTIVE→COMPLETED/ABORTED/EXPIRED is allowed by
        // UploadSessionTransitions. A racing store that reports
        // IllegalTransition with from=ACTIVE is itself broken; the
        // helper maps that to InternalAgentErrorException so no
        // fragment of the lifeline is uncovered.
        shouldThrow<dev.dmigrate.server.application.error.InternalAgentErrorException> {
            invokeAbortWithRacingTransition(illegalFrom = UploadSessionState.ACTIVE)
        }
    }

    test("non-object arguments throw VALIDATION_ERROR") {
        val f = fixture()
        val rawArgs = JsonParser.parseString("\"not an object\"")
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(ToolCallContext("artifact_upload_abort", rawArgs, PRINCIPAL))
        }
        ex.violations.map { it.field } shouldContain "arguments"
    }
})
