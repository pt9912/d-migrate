package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.PolicyRequiredException
import dev.dmigrate.server.application.error.RateLimitedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val ACME = TenantId("acme")
private val ALICE = PrincipalId("alice")

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

private const val SHA256_OK: String =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private class UploadInitFixture(
    val handler: ArtifactUploadInitHandler,
    val sessionStore: InMemoryUploadSessionStore,
    val quotaStore: InMemoryQuotaStore,
)

private fun setup(
    limits: McpLimitsConfig = McpLimitsConfig(),
    sessionLimit: Long = 10,
    bytesLimit: Long = 1_000_000_000,
    sessionId: String = "ups-fixed",
    requestId: String = "req-deadbeef",
): UploadInitFixture {
    val sessionStore = InMemoryUploadSessionStore()
    val quotaStore = InMemoryQuotaStore()
    val quotaService = DefaultQuotaService(quotaStore) { key ->
        when (key.dimension) {
            QuotaDimension.ACTIVE_UPLOAD_SESSIONS -> sessionLimit
            QuotaDimension.UPLOAD_BYTES -> bytesLimit
            else -> Long.MAX_VALUE
        }
    }
    val handler = ArtifactUploadInitHandler(
        sessionStore = sessionStore,
        quotaService = quotaService,
        limits = limits,
        clock = FIXED_CLOCK,
        sessionIdGenerator = { sessionId },
        requestIdProvider = { requestId },
    )
    return UploadInitFixture(handler, sessionStore, quotaStore)
}

private fun args(content: String): JsonObject = JsonParser.parseString(content).asJsonObject

private fun parsePayload(outcome: ToolCallOutcome): JsonObject {
    val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
    return JsonParser.parseString(text).asJsonObject
}

private const val VALID_INIT: String =
    """{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":1024,"checksumSha256":"$SHA256_OK"}"""

/**
 * Records the QuotaService lifecycle calls so tests can pin the
 * `reserve→commit` (success) and `reserve→refund` (pre-commit
 * rollback) sequences. `outcomes` is consumed in order — caller
 * supplies one outcome per expected `reserve` call.
 *
 * `DefaultQuotaService.commit` is intentionally a no-op today
 * (`QuotaService.kt:43-46` documents the AP 6.8 audit hook), so the
 * recorder is the only way to observe the boundary between
 * `commit`, `release`, and `refund` without coupling to a
 * production-side regression.
 */
private class RecordingQuotaService(
    private val outcomes: ArrayDeque<dev.dmigrate.server.ports.quota.QuotaOutcome>,
    val recorded: MutableList<String> = mutableListOf(),
) : dev.dmigrate.server.application.quota.QuotaService {
    override fun reserve(
        key: QuotaKey,
        amount: Long,
    ): dev.dmigrate.server.ports.quota.QuotaOutcome {
        recorded += "reserve(${key.dimension},$amount)"
        return outcomes.removeFirst()
    }
    override fun commit(reservation: dev.dmigrate.server.application.quota.QuotaReservation) {
        recorded += "commit(${reservation.key.dimension},${reservation.amount})"
    }
    override fun release(reservation: dev.dmigrate.server.application.quota.QuotaReservation) {
        recorded += "release(${reservation.key.dimension},${reservation.amount})"
    }
    override fun refund(reservation: dev.dmigrate.server.application.quota.QuotaReservation) {
        recorded += "refund(${reservation.key.dimension},${reservation.amount})"
    }
}

private fun recordingHandler(
    service: RecordingQuotaService,
    sessionStore: InMemoryUploadSessionStore = InMemoryUploadSessionStore(),
): ArtifactUploadInitHandler = ArtifactUploadInitHandler(
    sessionStore = sessionStore,
    quotaService = service,
    limits = McpLimitsConfig(),
    clock = FIXED_CLOCK,
    sessionIdGenerator = { "ups-fixed" },
    requestIdProvider = { "req-x" },
)

private fun grant(key: QuotaKey, amount: Long): dev.dmigrate.server.ports.quota.QuotaOutcome.Granted =
    dev.dmigrate.server.ports.quota.QuotaOutcome.Granted(key, amount, amount, Long.MAX_VALUE)

private fun rateLimit(key: QuotaKey, amount: Long): dev.dmigrate.server.ports.quota.QuotaOutcome.RateLimited =
    dev.dmigrate.server.ports.quota.QuotaOutcome.RateLimited(key, amount, 0, 100)

class ArtifactUploadInitHandlerTest : FunSpec({

    test("happy path: session is persisted ACTIVE and the response matches the spec/ki-mcp.md §5.3 shape") {
        val s = setup()
        val outcome = s.handler.handle(ToolCallContext("artifact_upload_init", args(VALID_INIT), PRINCIPAL))
        val json = parsePayload(outcome)
        json.get("uploadSessionId").asString shouldBe "ups-fixed"
        // Default initial TTL = 900s, capped by 3600s absolute lease.
        json.get("uploadSessionTtlSeconds").asLong shouldBe 900L
        json.get("expectedFirstSegmentIndex").asInt shouldBe 1
        json.get("expectedFirstSegmentOffset").asLong shouldBe 0L
        json.getAsJsonObject("executionMeta").get("requestId").asString shouldBe "req-deadbeef"

        val session = s.sessionStore.findById(ACME, "ups-fixed")!!
        session.state shouldBe UploadSessionState.ACTIVE
        session.uploadIntent shouldBe ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY
        session.sizeBytes shouldBe 1024L
        session.checksumSha256 shouldBe SHA256_OK
        session.tenantId shouldBe ACME
        session.ownerPrincipalId shouldBe ALICE
    }

    test("non-schema_staging_readonly intent surfaces as POLICY_REQUIRED (not VALIDATION_ERROR)") {
        val s = setup()
        val ex = shouldThrow<PolicyRequiredException> {
            s.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    args("""{"uploadIntent":"job_input","expectedSizeBytes":1024,"checksumSha256":"$SHA256_OK"}"""),
                    PRINCIPAL,
                ),
            )
        }
        // Intent is folded into the policy name so the wire detail
        // is distinct from a real approval-policy reference.
        ex.policyName shouldBe "upload_intent.job_input"
        s.sessionStore.findById(ACME, "ups-fixed") shouldBe null
    }

    test("missing uploadIntent / expectedSizeBytes / checksumSha256 each throw VALIDATION_ERROR") {
        val s = setup()
        for (body in listOf(
            """{"expectedSizeBytes":1024,"checksumSha256":"$SHA256_OK"}""",
            """{"uploadIntent":"schema_staging_readonly","checksumSha256":"$SHA256_OK"}""",
            """{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":1024}""",
        )) {
            shouldThrow<ValidationErrorException> {
                s.handler.handle(ToolCallContext("artifact_upload_init", args(body), PRINCIPAL))
            }
        }
    }

    test("non-hex checksum throws VALIDATION_ERROR with field=checksumSha256") {
        val s = setup()
        val ex = shouldThrow<ValidationErrorException> {
            s.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    args("""{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":1024,"checksumSha256":"NOTHEX"}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "checksumSha256"
    }

    test("expectedSizeBytes ≤ 0 throws VALIDATION_ERROR") {
        val s = setup()
        shouldThrow<ValidationErrorException> {
            s.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    args("""{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":0,"checksumSha256":"$SHA256_OK"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("expectedSizeBytes over maxArtifactUploadBytes throws PAYLOAD_TOO_LARGE before any quota call") {
        val s = setup(limits = McpLimitsConfig(maxArtifactUploadBytes = 1024))
        val ex = shouldThrow<PayloadTooLargeException> {
            s.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    args("""{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":2048,"checksumSha256":"$SHA256_OK"}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.maxBytes shouldBe 1024L
        // Quota counter is untouched: this must short-circuit before
        // reserve() is called so we don't burn a session slot on an
        // oversize payload that wouldn't fit anyway.
        s.quotaStore.current(QuotaKey(ACME, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, ALICE)) shouldBe 0L
    }

    test("active-session quota exhaustion surfaces RATE_LIMITED and does not persist a session") {
        val s = setup(sessionLimit = 0) // every reserve fails
        shouldThrow<RateLimitedException> {
            s.handler.handle(ToolCallContext("artifact_upload_init", args(VALID_INIT), PRINCIPAL))
        }
        s.sessionStore.findById(ACME, "ups-fixed") shouldBe null
    }

    test("byte-quota exhaustion rolls back the session-slot reservation (no leaked counter)") {
        // The session-slot quota is generous; the byte quota is the
        // gate. If the bytes reservation fails, the slot reservation
        // must be released — otherwise repeated failures would block
        // future inits even though no session exists.
        val s = setup(sessionLimit = 5, bytesLimit = 100)
        shouldThrow<RateLimitedException> {
            s.handler.handle(
                ToolCallContext(
                    "artifact_upload_init",
                    args("""{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":1024,"checksumSha256":"$SHA256_OK"}"""),
                    PRINCIPAL,
                ),
            )
        }
        s.quotaStore.current(QuotaKey(ACME, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, ALICE)) shouldBe 0L
        s.sessionStore.findById(ACME, "ups-fixed") shouldBe null
    }

    test("segmentTotal is computed by ceiling division of size / chunkSize") {
        // 5 KiB payload with 4 KiB segments → ceil(5120/4096) = 2 segments.
        val limits = McpLimitsConfig(maxUploadSegmentBytes = 4096)
        val s = setup(limits = limits)
        s.handler.handle(
            ToolCallContext(
                "artifact_upload_init",
                args("""{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":5120,"checksumSha256":"$SHA256_OK"}"""),
                PRINCIPAL,
            ),
        )
        s.sessionStore.findById(ACME, "ups-fixed")!!.segmentTotal shouldBe 2
    }

    test("happy path commits both reservations; rate-limited byte path refunds the session slot") {
        val sessionsKey = QuotaKey(ACME, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, ALICE)
        val bytesKey = QuotaKey(ACME, QuotaDimension.UPLOAD_BYTES, ALICE)
        val service = RecordingQuotaService(
            outcomes = ArrayDeque(listOf(grant(sessionsKey, 1), grant(bytesKey, 1024))),
        )
        recordingHandler(service)
            .handle(ToolCallContext("artifact_upload_init", args(VALID_INIT), PRINCIPAL))
        service.recorded shouldBe listOf(
            "reserve(ACTIVE_UPLOAD_SESSIONS,1)",
            "reserve(UPLOAD_BYTES,1024)",
            "commit(ACTIVE_UPLOAD_SESSIONS,1)",
            "commit(UPLOAD_BYTES,1024)",
        )
    }

    test("byte-quota rate-limit refunds the session slot via QuotaService.refund (pre-commit)") {
        val sessionsKey = QuotaKey(ACME, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, ALICE)
        val bytesKey = QuotaKey(ACME, QuotaDimension.UPLOAD_BYTES, ALICE)
        val service = RecordingQuotaService(
            outcomes = ArrayDeque(listOf(grant(sessionsKey, 1), rateLimit(bytesKey, 1024))),
        )
        shouldThrow<RateLimitedException> {
            recordingHandler(service)
                .handle(ToolCallContext("artifact_upload_init", args(VALID_INIT), PRINCIPAL))
        }
        service.recorded shouldBe listOf(
            "reserve(ACTIVE_UPLOAD_SESSIONS,1)",
            "reserve(UPLOAD_BYTES,1024)",
            "refund(ACTIVE_UPLOAD_SESSIONS,1)",
        )
    }

    test("malformed expectedSizeBytes types (object/array/string) all throw VALIDATION_ERROR") {
        val s = setup()
        for (body in listOf(
            """{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":{},"checksumSha256":"$SHA256_OK"}""",
            """{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":[],"checksumSha256":"$SHA256_OK"}""",
            """{"uploadIntent":"schema_staging_readonly","expectedSizeBytes":"1024","checksumSha256":"$SHA256_OK"}""",
        )) {
            val ex = shouldThrow<ValidationErrorException> {
                s.handler.handle(ToolCallContext("artifact_upload_init", args(body), PRINCIPAL))
            }
            ex.violations.map { it.field } shouldContain "expectedSizeBytes"
        }
    }

    test("happy path: session.idleTimeoutAt = fixedNow + idleTimeout default (5 min)") {
        val s = setup()
        s.handler.handle(ToolCallContext("artifact_upload_init", args(VALID_INIT), PRINCIPAL))
        val session = s.sessionStore.findById(ACME, "ups-fixed")!!
        session.idleTimeoutAt shouldBe FIXED_NOW.plus(Duration.ofMinutes(5))
    }

    test("segmentCountFor at the boundary: size == segmentSize maps to one segment, not zero") {
        ArtifactUploadInitHandler.segmentCountFor(totalBytes = 4096, segmentSize = 4096) shouldBe 1
        ArtifactUploadInitHandler.segmentCountFor(totalBytes = 4097, segmentSize = 4096) shouldBe 2
        ArtifactUploadInitHandler.segmentCountFor(totalBytes = 1, segmentSize = 4096) shouldBe 1
    }

    test("non-object arguments throw VALIDATION_ERROR") {
        // Defensive: a malformed client could send `arguments: "stuff"`
        // — must surface as VALIDATION_ERROR with field=arguments
        // rather than crashing the dispatch path.
        val s = setup()
        val rawArgs = JsonParser.parseString("\"not an object\"")
        val ex = shouldThrow<ValidationErrorException> {
            s.handler.handle(ToolCallContext("artifact_upload_init", rawArgs, PRINCIPAL))
        }
        ex.violations.map { it.field } shouldContain "arguments"
    }

    test("uploadSessionTtlSeconds caps at the remaining absolute lease window") {
        // When the absolute-lease window is shorter than the
        // configured initial TTL, the response advertises the smaller
        // value so a client can't claim more time than the session
        // has — spec/ki-mcp.md §5.3 line 605-617.
        val tenMin = Duration.ofMinutes(10) // 600s, less than 900s default initial TTL
        val sessionStore = InMemoryUploadSessionStore()
        val quotaStore = InMemoryQuotaStore()
        val handler = ArtifactUploadInitHandler(
            sessionStore = sessionStore,
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = FIXED_CLOCK,
            absoluteLeaseDuration = tenMin,
            sessionIdGenerator = { "ups-fixed" },
            requestIdProvider = { "req-x" },
        )
        val outcome = handler.handle(ToolCallContext("artifact_upload_init", args(VALID_INIT), PRINCIPAL))
        parsePayload(outcome).get("uploadSessionTtlSeconds").asLong shouldBe 600L
    }
})
