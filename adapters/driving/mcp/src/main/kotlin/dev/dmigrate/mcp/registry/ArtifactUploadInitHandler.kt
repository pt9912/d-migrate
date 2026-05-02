package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.PolicyRequiredException
import dev.dmigrate.server.application.error.RateLimitedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.RateLimitedDetail
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.UploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * AP 6.7: `artifact_upload_init` for the read-only schema-staging
 * path per `ImpPlan-0.9.6-C.md` §5.3 + §6.7.
 *
 * Phase C accepts only `uploadIntent=schema_staging_readonly`. Every
 * other intent surfaces as `POLICY_REQUIRED` so clients understand
 * the future policy gate (AP 6.13+) is not yet open. The handler
 * does NOT consult an approval store — read-only schema staging is
 * policy-free per §4.4.
 *
 * Quota policy: reserves one slot in `ACTIVE_UPLOAD_SESSIONS` AND
 * `expectedSizeBytes` in `UPLOAD_BYTES`. The session reservation is
 * released on abort/expiry/finalisation (AP 6.9+); the byte
 * reservation likewise. If either reservation is rate-limited, the
 * session is not created and the byte reservation is rolled back via
 * [QuotaService.release] before throwing.
 *
 * The TTL pair (`idleTimeoutAt`, `absoluteLeaseExpiresAt`) is
 * configurable so tests don't have to wall-clock; production picks
 * sensible defaults (5 min idle, 60 min absolute lease).
 */
@Suppress("LongParameterList")
// LongParameterList: the trailing params are test seams (clock,
// timeouts, id generators) with sensible production defaults. AP 6.13
// will fold them into a shared `McpHandlerWiring` config when the
// bootstrap wires every Phase-C handler at once.
internal class ArtifactUploadInitHandler(
    private val sessionStore: UploadSessionStore,
    private val quotaService: QuotaService,
    private val limits: McpLimitsConfig,
    private val clock: Clock,
    private val initialTtl: Duration = DEFAULT_INITIAL_TTL,
    private val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    private val absoluteLeaseDuration: Duration = DEFAULT_ABSOLUTE_LEASE,
    private val sessionIdGenerator: () -> String = ::generateSessionId,
    private val requestIdProvider: () -> String = ::generateRequestId,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        // TODO(AP 6.13): idempotency key per ImpPlan-0.9.6-B §5.3.5
        // — read-only staging is not user-state-changing on retry,
        // but a same-checksum replay should ideally hit the existing
        // session instead of minting a new one and burning quota.
        val args = parseArguments(context.arguments)
        val tenantId = context.principal.effectiveTenantId
        val sessionsKey = QuotaKey(tenantId, QuotaDimension.ACTIVE_UPLOAD_SESSIONS, context.principal.principalId)
        val bytesKey = QuotaKey(tenantId, QuotaDimension.UPLOAD_BYTES, context.principal.principalId)
        val sessionsReservation = QuotaReservation(sessionsKey, amount = 1)
        val bytesReservation = QuotaReservation(bytesKey, amount = args.expectedSizeBytes)

        // Reserve session slot first; on byte-quota failure roll back
        // via `refund` (pre-commit semantics per QuotaService docs)
        // so a byte-limited tenant doesn't leak active-session counters.
        when (val outcome = quotaService.reserve(sessionsKey, amount = 1)) {
            is QuotaOutcome.RateLimited -> throw RateLimitedException(RateLimitedDetail.from(outcome))
            is QuotaOutcome.Granted -> Unit
        }
        when (val outcome = quotaService.reserve(bytesKey, amount = args.expectedSizeBytes)) {
            is QuotaOutcome.RateLimited -> {
                quotaService.refund(sessionsReservation)
                throw RateLimitedException(RateLimitedDetail.from(outcome))
            }
            is QuotaOutcome.Granted -> Unit
        }

        val now = clock.instant()
        val absoluteExpiresAt = now.plus(absoluteLeaseDuration)
        val session = newSession(context, args, now, absoluteExpiresAt)
        try {
            sessionStore.save(session)
        } catch (e: RuntimeException) {
            // Defence in depth: if the store throws (e.g. a future
            // unique-id collision contract), roll the reservations
            // back so the tenant isn't penalised for a server fault.
            quotaService.refund(bytesReservation)
            quotaService.refund(sessionsReservation)
            throw e
        }
        // Commit hooks run on success per QuotaService.kt:11-17 —
        // counters stay reserved, audit hook fires.
        quotaService.commit(sessionsReservation)
        quotaService.commit(bytesReservation)

        // Spec/ki-mcp.md §5.3: clients see `uploadSessionId` (matches
        // the core record), the remaining lease as
        // `uploadSessionTtlSeconds` (initial 900s, capped by absolute
        // lease), and the explicit first-segment hints so resumable
        // clients don't have to derive offsets from chunk size. The
        // chunk size itself is advertised once via
        // `capabilities_list.limits.maxUploadSegmentBytes` — no need
        // to repeat it here.
        val ttlSeconds = effectiveTtlSeconds(now, absoluteExpiresAt)
        val payload = mapOf(
            "uploadSessionId" to session.uploadSessionId,
            "uploadSessionTtlSeconds" to ttlSeconds,
            "expectedFirstSegmentIndex" to FIRST_SEGMENT_INDEX,
            "expectedFirstSegmentOffset" to FIRST_SEGMENT_OFFSET,
            "executionMeta" to mapOf("requestId" to requestIdProvider()),
        )
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(payload),
                    mimeType = "application/json",
                ),
            ),
        )
    }

    private fun newSession(
        context: ToolCallContext,
        args: UploadInitArgs,
        now: java.time.Instant,
        absoluteExpiresAt: java.time.Instant,
    ): UploadSession {
        val tenantId = context.principal.effectiveTenantId
        val sessionId = sessionIdGenerator()
        val resourceUri = ServerResourceUri(tenantId, ResourceKind.UPLOAD_SESSIONS, sessionId)
        return UploadSession(
            uploadSessionId = sessionId,
            tenantId = tenantId,
            ownerPrincipalId = context.principal.principalId,
            resourceUri = resourceUri,
            artifactKind = ArtifactKind.SCHEMA,
            mimeType = "application/octet-stream",
            sizeBytes = args.expectedSizeBytes,
            segmentTotal = segmentCountFor(args.expectedSizeBytes, limits.maxUploadSegmentBytes),
            checksumSha256 = args.checksumSha256,
            uploadIntent = INTENT_SCHEMA_STAGING_READONLY,
            state = UploadSessionState.ACTIVE,
            createdAt = now,
            updatedAt = now,
            idleTimeoutAt = now.plus(idleTimeout),
            absoluteLeaseExpiresAt = absoluteExpiresAt,
            bytesReceived = 0,
        )
    }

    private fun parseArguments(raw: JsonElement?): UploadInitArgs {
        val obj = JsonArgs.requireObject(raw)
        val intent = obj.optString("uploadIntent")
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("uploadIntent", "is required")),
            )
        if (intent != INTENT_SCHEMA_STAGING_READONLY) {
            // Phase C is the read-only window: policy-pflichtige
            // intents like job_input/data_import surface as the
            // typed POLICY_REQUIRED envelope. The policy name is
            // synthesised from the intent so the wire detail is
            // distinct from a real approval-policy reference.
            throw PolicyRequiredException("upload_intent.$intent")
        }
        val expectedSize = parseSize(obj)
        val checksum = obj.optString("checksumSha256")
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("checksumSha256", "is required")),
            )
        if (!CHECKSUM_PATTERN.matches(checksum)) {
            throw ValidationErrorException(
                listOf(ValidationViolation("checksumSha256", "must be 64 lowercase hex chars")),
            )
        }
        return UploadInitArgs(expectedSize, checksum)
    }

    private fun parseSize(obj: JsonObject): Long {
        val element = obj.get("expectedSizeBytes")
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("expectedSizeBytes", "is required")),
            )
        // Reject non-numeric primitives, objects, arrays and JsonNull
        // up-front rather than trusting Gson's `asLong` to surface a
        // typed error: an object/array makes `asLong` throw
        // IllegalStateException ("Not a JSON Primitive") which would
        // otherwise crash through the dispatch path uncategorised.
        val primitive = element as? com.google.gson.JsonPrimitive
        if (primitive == null || !primitive.isNumber) {
            throw ValidationErrorException(
                listOf(ValidationViolation("expectedSizeBytes", "must be a positive integer")),
            )
        }
        val size = primitive.asLong
        if (size <= 0) {
            throw ValidationErrorException(
                listOf(ValidationViolation("expectedSizeBytes", "must be greater than zero")),
            )
        }
        if (size > limits.maxArtifactUploadBytes) {
            throw PayloadTooLargeException(actualBytes = size, maxBytes = limits.maxArtifactUploadBytes)
        }
        return size
    }

    private data class UploadInitArgs(
        val expectedSizeBytes: Long,
        val checksumSha256: String,
    )

    /**
     * Computes the lease seconds the client should advertise as
     * `uploadSessionTtlSeconds`. Per spec/ki-mcp.md §5.3 the response
     * value is the *minimum* of the configured initial TTL and the
     * remaining absolute-lease window — the absolute cap takes
     * precedence so a session can't claim more time than it has.
     */
    private fun effectiveTtlSeconds(now: java.time.Instant, absoluteExpiresAt: java.time.Instant): Long {
        val remainingAbsolute = Duration.between(now, absoluteExpiresAt).seconds
        return minOf(initialTtl.seconds, remainingAbsolute)
    }

    companion object {
        const val INTENT_SCHEMA_STAGING_READONLY: String = "schema_staging_readonly"

        // Per spec/ki-mcp.md §5.3 line 588: segment indices start at 1.
        const val FIRST_SEGMENT_INDEX: Int = 1
        const val FIRST_SEGMENT_OFFSET: Long = 0L

        // Spec defaults — re-exported from UploadSessionDefaults so
        // existing call sites and tests don't have to re-import.
        val DEFAULT_INITIAL_TTL: Duration = UploadSessionDefaults.INITIAL_TTL
        val DEFAULT_IDLE_TIMEOUT: Duration = UploadSessionDefaults.IDLE_TIMEOUT
        val DEFAULT_ABSOLUTE_LEASE: Duration = UploadSessionDefaults.ABSOLUTE_LEASE

        private val CHECKSUM_PATTERN: Regex = UploadSessionDefaults.SHA256_HEX_PATTERN

        fun segmentCountFor(totalBytes: Long, segmentSize: Int): Int {
            require(segmentSize > 0) { "segmentSize must be positive" }
            // Integer-only ceiling divide: (a + b - 1) / b. Avoids
            // Long→Double precision loss past 2^53 that ceil(/) would
            // otherwise carry once tenant size limits grow beyond MiB.
            return ((totalBytes + segmentSize - 1) / segmentSize).toInt()
        }

        private fun generateSessionId(): String =
            "ups-${UUID.randomUUID().toString().replace("-", "").take(16)}"

        private fun generateRequestId(): String =
            "req-${UUID.randomUUID().toString().take(8)}"
    }
}
