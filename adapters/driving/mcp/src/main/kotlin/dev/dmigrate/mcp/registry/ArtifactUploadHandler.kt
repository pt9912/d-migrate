package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dev.dmigrate.mcp.registry.JsonArgs.requireBool
import dev.dmigrate.mcp.registry.JsonArgs.requireInt
import dev.dmigrate.mcp.registry.JsonArgs.requireLong
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.RateLimitedException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.UploadSessionAbortedException
import dev.dmigrate.server.application.error.UploadSessionExpiredException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.application.quota.RateLimitedDetail
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import dev.dmigrate.server.ports.WriteSegmentOutcome
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * AP 6.8: `artifact_upload` per `ImpPlan-0.9.6-C.md` §6.8 and
 * `spec/ki-mcp.md` §5.3.
 *
 * Accepts one segment at a time for an active read-only schema-
 * staging session. Validates session ownership, sequence, offset,
 * size, and per-segment hash before persisting; the session is
 * advanced to `COMPLETED` only when `isFinalSegment=true`, all
 * preceding segments are present, the cumulative byte count matches
 * `session.sizeBytes`, and the rebuilt total hash matches
 * `session.checksumSha256`. Schema parsing and `schemaRef`
 * registration land in AP 6.9 — this handler stops at the
 * `COMPLETED` transition.
 *
 * Idempotency: identical retries (same index + same hash) surface as
 * `deduplicated=true` per the spec's resumability contract; same
 * index with a different hash → `IDEMPOTENCY_CONFLICT` so the client
 * sees a structured error rather than a silent overwrite.
 *
 * Quotas: each accepted segment reserves one slot in
 * `PARALLEL_SEGMENT_WRITES`, released after the store call regardless
 * of outcome so a Conflict/SizeMismatch doesn't leak the slot.
 */
@Suppress("LongParameterList")
// LongParameterList: trailing params are test seams (clock, lease
// extension, request-id provider). AP 6.13 will fold them into a
// shared wiring config.
internal class ArtifactUploadHandler(
    private val sessionStore: UploadSessionStore,
    private val segmentStore: UploadSegmentStore,
    private val quotaService: QuotaService,
    private val limits: McpLimitsConfig,
    private val clock: Clock,
    private val initialTtl: Duration = ArtifactUploadInitHandler.DEFAULT_INITIAL_TTL,
    private val idleTimeout: Duration = ArtifactUploadInitHandler.DEFAULT_IDLE_TIMEOUT,
    private val requestIdProvider: () -> String = ::generateRequestId,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = parseArguments(context.arguments)
        val tenant = context.principal.effectiveTenantId
        val session = sessionStore.findById(tenant, args.uploadSessionId)
            ?: throw ResourceNotFoundException(
                dev.dmigrate.server.core.resource.ServerResourceUri(
                    tenant,
                    dev.dmigrate.server.core.resource.ResourceKind.UPLOAD_SESSIONS,
                    args.uploadSessionId,
                ),
            )

        // §5.3 line 562-564: session is bound to its owner.
        if (session.ownerPrincipalId != context.principal.principalId) {
            throw ForbiddenPrincipalException(
                principalId = context.principal.principalId,
                reason = "session belongs to a different principal",
            )
        }
        validateSessionState(session)
        validateSegmentSequence(args, session)

        val bytes = decodeBase64(args.contentBase64)
        if (bytes.size > limits.maxUploadSegmentBytes) {
            throw PayloadTooLargeException(bytes.size.toLong(), limits.maxUploadSegmentBytes.toLong())
        }
        validateSegmentHash(args.segmentSha256, bytes)
        validateSegmentBudget(args, bytes.size, session)

        val deduplicated = writeWithQuota(args, bytes, session, context.principal)

        val now = clock.instant()
        val cumulativeBytes = computeCumulativeBytes(session.uploadSessionId)
        // Lease extension per spec/ki-mcp.md §5.3:614 — each accepted
        // segment may extend the absolute lease, capped at
        // (createdAt + 3600s). Idle window resets to (now + idle).
        // Both extensions are bounded by the original absolute hard
        // cap so a stalled-then-resumed upload can't outlive the
        // session-creation window.
        val absoluteHardCap = session.createdAt.plus(MAX_ABSOLUTE_LEASE)
        val newAbsolute = minOf(now.plus(initialTtl), absoluteHardCap)
        val newIdle = minOf(now.plus(idleTimeout), newAbsolute)
        val updated = session.copy(
            bytesReceived = cumulativeBytes,
            updatedAt = now,
            idleTimeoutAt = newIdle,
            absoluteLeaseExpiresAt = newAbsolute,
        )
        sessionStore.save(updated)

        val finalState = maybeComplete(args, updated, bytes, now)
        val ttlSeconds = effectiveTtlSeconds(now, finalState)
        val payload = mapOf(
            "uploadSessionId" to finalState.uploadSessionId,
            "acceptedSegmentIndex" to args.segmentIndex,
            "deduplicated" to deduplicated,
            "bytesReceived" to finalState.bytesReceived,
            "uploadSessionTtlSeconds" to ttlSeconds,
            "uploadSessionState" to finalState.state.name,
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

    /**
     * Reserve→write→release walk per the QuotaService contract: every
     * outcome (Stored / AlreadyStored / Conflict / SizeMismatch)
     * passes through `finally` so the per-write slot can't leak.
     */
    private fun writeWithQuota(
        args: UploadSegmentArgs,
        bytes: ByteArray,
        session: UploadSession,
        principal: PrincipalContext,
    ): Boolean {
        val parallelKey = QuotaKey(
            session.tenantId,
            QuotaDimension.PARALLEL_SEGMENT_WRITES,
            principal.principalId,
        )
        val reservation = QuotaReservation(parallelKey, amount = 1)
        val outcome = when (val res = quotaService.reserve(parallelKey, amount = 1)) {
            is QuotaOutcome.RateLimited -> throw RateLimitedException(RateLimitedDetail.from(res))
            is QuotaOutcome.Granted -> {
                try {
                    segmentStore.writeSegment(
                        UploadSegment(
                            uploadSessionId = session.uploadSessionId,
                            segmentIndex = args.segmentIndex,
                            segmentOffset = args.segmentOffset,
                            sizeBytes = bytes.size.toLong(),
                            segmentSha256 = args.segmentSha256,
                        ),
                        ByteArrayInputStream(bytes),
                    )
                } finally {
                    quotaService.release(reservation)
                }
            }
        }
        return mapStoreOutcome(outcome, args)
    }

    private fun parseArguments(raw: JsonElement?): UploadSegmentArgs {
        val obj = JsonArgs.requireObject(raw)
        return UploadSegmentArgs(
            uploadSessionId = obj.requireString("uploadSessionId"),
            segmentIndex = obj.requireInt("segmentIndex", min = 1),
            segmentOffset = obj.requireLong("segmentOffset", min = 0),
            segmentTotal = obj.requireInt("segmentTotal", min = 1),
            isFinalSegment = obj.requireBool("isFinalSegment"),
            segmentSha256 = obj.requireString("segmentSha256"),
            contentBase64 = obj.requireString("contentBase64"),
            clientRequestId = obj.optString("clientRequestId"),
        )
    }

    private fun validateSessionState(session: UploadSession) {
        when (session.state) {
            UploadSessionState.ACTIVE -> Unit
            UploadSessionState.COMPLETED -> throw IdempotencyConflictException(
                existingFingerprint = "session=${session.uploadSessionId},state=COMPLETED",
            )
            UploadSessionState.ABORTED -> throw UploadSessionAbortedException(session.uploadSessionId)
            UploadSessionState.EXPIRED -> throw UploadSessionExpiredException(session.uploadSessionId)
        }
    }

    private fun validateSegmentSequence(args: UploadSegmentArgs, session: UploadSession) {
        if (args.segmentTotal != session.segmentTotal) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "segmentTotal",
                        "expected ${session.segmentTotal}, got ${args.segmentTotal}",
                    ),
                ),
            )
        }
        if (args.segmentIndex > session.segmentTotal) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "segmentIndex",
                        "must be <= segmentTotal (${session.segmentTotal})",
                    ),
                ),
            )
        }
        val expectedFinal = args.segmentIndex == session.segmentTotal
        if (args.isFinalSegment != expectedFinal) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "isFinalSegment",
                        "must be $expectedFinal for segmentIndex ${args.segmentIndex} of ${session.segmentTotal}",
                    ),
                ),
            )
        }
    }

    private fun validateSegmentHash(claimed: String, bytes: ByteArray) {
        if (!HEX_64.matches(claimed)) {
            throw ValidationErrorException(
                listOf(ValidationViolation("segmentSha256", "must be 64 lowercase hex chars")),
            )
        }
        val computed = sha256Hex(bytes)
        if (computed != claimed) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "segmentSha256",
                        "claimed hash does not match decoded bytes",
                    ),
                ),
            )
        }
    }

    private fun validateSegmentBudget(args: UploadSegmentArgs, decodedSize: Int, session: UploadSession) {
        // Offset + size must fit inside the declared total.
        if (args.segmentOffset + decodedSize > session.sizeBytes) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "segmentOffset",
                        "offset + size (${args.segmentOffset + decodedSize}) " +
                            "exceeds declared sizeBytes (${session.sizeBytes})",
                    ),
                ),
            )
        }
        // Non-final segments must run at the maximum segment size so
        // the client and server agree on offsets without round-trips.
        if (!args.isFinalSegment && decodedSize.toLong() != limits.maxUploadSegmentBytes.toLong()) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "contentBase64",
                        "non-final segment must be exactly ${limits.maxUploadSegmentBytes} bytes",
                    ),
                ),
            )
        }
    }

    private fun mapStoreOutcome(outcome: WriteSegmentOutcome, args: UploadSegmentArgs): Boolean = when (outcome) {
        is WriteSegmentOutcome.Stored -> false
        is WriteSegmentOutcome.AlreadyStored -> true
        is WriteSegmentOutcome.Conflict -> throw IdempotencyConflictException(
            existingFingerprint = "segment=${args.segmentIndex},sha256=${outcome.existingSegmentSha256}",
        )
        is WriteSegmentOutcome.SizeMismatch -> throw InternalAgentErrorException()
    }

    private fun computeCumulativeBytes(sessionId: String): Long =
        segmentStore.listSegments(sessionId).sumOf { it.sizeBytes }

    private fun maybeComplete(
        args: UploadSegmentArgs,
        session: UploadSession,
        finalBytes: ByteArray,
        now: java.time.Instant,
    ): UploadSession {
        if (!args.isFinalSegment) return session
        val storedSegments = segmentStore.listSegments(session.uploadSessionId)
        if (storedSegments.size != session.segmentTotal) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "isFinalSegment",
                        "expected ${session.segmentTotal} segments, found ${storedSegments.size}",
                    ),
                ),
            )
        }
        val totalSize = storedSegments.sumOf { it.sizeBytes }
        if (totalSize != session.sizeBytes) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "isFinalSegment",
                        "cumulative segment bytes ($totalSize) do not match session.sizeBytes (${session.sizeBytes})",
                    ),
                ),
            )
        }
        val totalHash = computeTotalHash(session, storedSegments, finalBytes)
        if (totalHash != session.checksumSha256) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "checksumSha256",
                        "rebuilt total hash does not match init checksumSha256",
                    ),
                ),
            )
        }
        return when (val transition = sessionStore.transition(
            session.tenantId,
            session.uploadSessionId,
            UploadSessionState.COMPLETED,
            now,
        )) {
            is TransitionOutcome.Applied -> transition.session
            // A concurrent abort/expire raced our ACTIVE check —
            // surface the typed lifecycle exception so the client
            // sees the real cause instead of an internal error.
            is TransitionOutcome.IllegalTransition -> throw when (transition.from) {
                UploadSessionState.ABORTED -> UploadSessionAbortedException(session.uploadSessionId)
                UploadSessionState.EXPIRED -> UploadSessionExpiredException(session.uploadSessionId)
                UploadSessionState.COMPLETED -> IdempotencyConflictException(
                    existingFingerprint = "session=${session.uploadSessionId},state=COMPLETED",
                )
                UploadSessionState.ACTIVE -> InternalAgentErrorException()
            }
            // The session vanished between our findById and the
            // transition — also a legitimate concurrent outcome.
            is TransitionOutcome.NotFound -> throw ResourceNotFoundException(session.resourceUri)
        }
    }

    private fun computeTotalHash(
        session: UploadSession,
        storedSegments: List<UploadSegment>,
        finalBytes: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val ordered = storedSegments.sortedBy { it.segmentIndex }
        for (segment in ordered) {
            val bytes = if (segment.segmentIndex == session.segmentTotal) {
                // The completing segment is in hand; reuse the bytes
                // we already decoded instead of round-tripping through
                // the store.
                finalBytes
            } else {
                segmentStore.openSegmentRangeRead(
                    uploadSessionId = session.uploadSessionId,
                    segmentIndex = segment.segmentIndex,
                    offset = 0L,
                    length = segment.sizeBytes,
                ).use { it.readAllBytes() }
            }
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun effectiveTtlSeconds(now: java.time.Instant, session: UploadSession): Long {
        if (session.state.terminal) return 0L
        val remainingAbsolute = Duration.between(now, session.absoluteLeaseExpiresAt).seconds
        return minOf(initialTtl.seconds, remainingAbsolute).coerceAtLeast(0L)
    }

    @Suppress("SwallowedException")
    private fun decodeBase64(payload: String): ByteArray = try {
        Base64.getDecoder().decode(payload)
    } catch (e: IllegalArgumentException) {
        // SwallowedException: the JDK's IllegalArgumentException
        // carries position-of-bad-byte detail; surfacing the cause
        // would leak unbounded base64 chunks across the trust
        // boundary. The sanitised message is enough for the client
        // to fix their encoding.
        throw ValidationErrorException(
            listOf(ValidationViolation("contentBase64", e.message ?: "not valid base64")),
        )
    }

    private data class UploadSegmentArgs(
        val uploadSessionId: String,
        val segmentIndex: Int,
        val segmentOffset: Long,
        val segmentTotal: Int,
        val isFinalSegment: Boolean,
        val segmentSha256: String,
        val contentBase64: String,
        val clientRequestId: String?,
    )

    private companion object {
        private val HEX_64: Regex = UploadSessionDefaults.SHA256_HEX_PATTERN

        // Spec/ki-mcp.md §5.3:610 — absolute hard cap from session
        // creation. Same value as the init-handler default.
        private val MAX_ABSOLUTE_LEASE: Duration = UploadSessionDefaults.ABSOLUTE_LEASE

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun generateRequestId(): String =
            "req-${UUID.randomUUID().toString().take(8)}"
    }
}
