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

class ArtifactUploadInitHandlerTest : FunSpec({

    test("happy path: session is persisted ACTIVE and the response carries sessionId/chunkSize/expiresAt") {
        val s = setup()
        val outcome = s.handler.handle(ToolCallContext("artifact_upload_init", args(VALID_INIT), PRINCIPAL))
        val json = parsePayload(outcome)
        json.get("sessionId").asString shouldBe "ups-fixed"
        json.get("chunkSizeBytes").asInt shouldBe McpLimitsConfig().maxUploadSegmentBytes
        json.get("expiresAt").asString shouldStartWith "2026-05-02T13:" // fixed-now + 60min default lease
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
        // Records lifecycle calls so we can pin the QuotaService
        // contract (reserve→commit on success, reserve→refund on
        // pre-commit rollback). InMemoryQuotaStore alone can't
        // distinguish commit from release because both no-op the
        // counter today; the recorder makes the boundary observable.
        val recorded = mutableListOf<String>()
        val recordingService = object : dev.dmigrate.server.application.quota.QuotaService {
            override fun reserve(key: QuotaKey, amount: Long): dev.dmigrate.server.ports.quota.QuotaOutcome {
                recorded += "reserve(${key.dimension},$amount)"
                return dev.dmigrate.server.ports.quota.QuotaOutcome.Granted(key, amount, amount, Long.MAX_VALUE)
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
        val handler = ArtifactUploadInitHandler(
            sessionStore = InMemoryUploadSessionStore(),
            quotaService = recordingService,
            limits = McpLimitsConfig(),
            clock = FIXED_CLOCK,
            sessionIdGenerator = { "ups-fixed" },
            requestIdProvider = { "req-x" },
        )
        handler.handle(ToolCallContext("artifact_upload_init", args(VALID_INIT), PRINCIPAL))
        recorded shouldBe listOf(
            "reserve(ACTIVE_UPLOAD_SESSIONS,1)",
            "reserve(UPLOAD_BYTES,1024)",
            "commit(ACTIVE_UPLOAD_SESSIONS,1)",
            "commit(UPLOAD_BYTES,1024)",
        )
    }

    test("byte-quota rate-limit refunds the session slot via QuotaService.refund (pre-commit)") {
        val recorded = mutableListOf<String>()
        val recordingService = object : dev.dmigrate.server.application.quota.QuotaService {
            private var calls = 0
            override fun reserve(key: QuotaKey, amount: Long): dev.dmigrate.server.ports.quota.QuotaOutcome {
                recorded += "reserve(${key.dimension},$amount)"
                calls += 1
                return if (calls == 1) {
                    dev.dmigrate.server.ports.quota.QuotaOutcome.Granted(key, amount, amount, Long.MAX_VALUE)
                } else {
                    dev.dmigrate.server.ports.quota.QuotaOutcome.RateLimited(key, amount, 0, 100)
                }
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
        val handler = ArtifactUploadInitHandler(
            sessionStore = InMemoryUploadSessionStore(),
            quotaService = recordingService,
            limits = McpLimitsConfig(),
            clock = FIXED_CLOCK,
            sessionIdGenerator = { "ups-fixed" },
            requestIdProvider = { "req-x" },
        )
        shouldThrow<RateLimitedException> {
            handler.handle(ToolCallContext("artifact_upload_init", args(VALID_INIT), PRINCIPAL))
        }
        recorded shouldBe listOf(
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

    test("session expiresAt equals fixedNow + absoluteLeaseDuration") {
        val tenMin = Duration.ofMinutes(10)
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
        parsePayload(outcome).get("expiresAt").asString shouldBe FIXED_NOW.plus(tenMin).toString()
    }
})
