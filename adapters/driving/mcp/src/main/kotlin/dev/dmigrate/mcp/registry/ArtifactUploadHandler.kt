package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dev.dmigrate.mcp.registry.JsonArgs.requireBool
import dev.dmigrate.mcp.registry.JsonArgs.requireInt
import dev.dmigrate.mcp.registry.JsonArgs.requireLong
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
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
import dev.dmigrate.server.core.upload.AssembledUploadPayloadFactory
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
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

/**
 * AP 6.8 + AP 6.22: `artifact_upload` per `ImpPlan-0.9.6-C.md` §6.8 +
 * §6.22 and `spec/ki-mcp.md` §5.3.
 *
 * Accepts one segment at a time for an active read-only schema-
 * staging session. Validates session ownership, sequence, offset,
 * size, and per-segment hash before persisting. The completing
 * segment delegates to [StreamingFinalizer] which atomically claims
 * `FINALIZING`, streams the assembled payload off-heap, and persists
 * a deterministic [dev.dmigrate.server.core.upload.FinalizationOutcome]
 * before any artefact / schema side effect.
 *
 * Idempotency / replay:
 * - same segment retried on `ACTIVE` → `deduplicated=true`
 * - completing-segment retry on `COMPLETED` → returns persisted
 *   `schemaRef` (AP 6.18)
 * - completing-segment retry on `ABORTED` whose outcome was `FAILED`
 *   → re-throws sanitised error class (AP 6.22)
 * - any retry against a live `FINALIZING` claim → retryable Conflict
 *   without side effects
 */
@Suppress("LongParameterList")
internal class ArtifactUploadHandler(
    private val sessionStore: UploadSessionStore,
    segmentStore: UploadSegmentStore,
    private val quotaService: QuotaService,
    private val limits: McpLimitsConfig,
    private val clock: Clock,
    private val initialTtl: Duration = ArtifactUploadInitHandler.DEFAULT_INITIAL_TTL,
    private val idleTimeout: Duration = ArtifactUploadInitHandler.DEFAULT_IDLE_TIMEOUT,
    private val finalizer: SchemaStagingFinalizer? = null,
    payloadFactory: AssembledUploadPayloadFactory = AssembledUploadPayloadFactory.inMemory(),
    finalizingLeaseTtl: Duration = DEFAULT_FINALIZING_LEASE_TTL,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val segmentStoreRef = segmentStore
    private val streamingFinalizer = StreamingFinalizer(
        sessionStore = sessionStore,
        segmentStore = segmentStore,
        limits = limits,
        payloadFactory = payloadFactory,
        finalizingLeaseTtl = finalizingLeaseTtl,
    )

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

        if (session.ownerPrincipalId != context.principal.principalId) {
            throw ForbiddenPrincipalException(
                principalId = context.principal.principalId,
                reason = "session belongs to a different principal",
            )
        }
        if (session.state == UploadSessionState.COMPLETED) {
            return handleReplayAfterCompleted(session, args, context.requestId)
        }
        if (session.state == UploadSessionState.ABORTED) {
            // AP 6.22: if a sanitised FAILED outcome was persisted,
            // re-throw the same error class so retries are
            // deterministic. Otherwise fall through to the regular
            // Aborted exception path.
            streamingFinalizer.replayFailedOutcomeIfAvailable(session)
        }
        validateSessionState(session, args)
        validateSegmentSequence(args, session)

        val bytes = decodeBase64(args.contentBase64)
        if (bytes.size > limits.maxUploadSegmentBytes) {
            throw PayloadTooLargeException(bytes.size.toLong(), limits.maxUploadSegmentBytes.toLong())
        }
        validateSegmentHash(args.segmentSha256, bytes)
        validateSegmentBudget(args, bytes.size, session)

        val deduplicated = writeWithQuota(args, bytes, session, context.principal)

        val now = clock.instant()
        val updated = saveLeaseExtension(session, now)
        val finalisable = isSessionFinalisable(args, updated)
        val schemaRef = if (finalisable) runFinalisation(updated, context.principal, bytes, now) else null
        val finalState = if (finalisable) {
            sessionStore.findById(updated.tenantId, updated.uploadSessionId) ?: updated
        } else {
            updated
        }
        return buildSegmentResponse(
            session = finalState,
            acceptedSegmentIndex = args.segmentIndex,
            deduplicated = deduplicated,
            ttlSeconds = effectiveTtlSeconds(now, finalState),
            schemaRef = schemaRef,
            requestId = context.requestId,
        )
    }

    private fun runFinalisation(
        session: UploadSession,
        principal: PrincipalContext,
        finalSegmentBytes: ByteArray,
        now: java.time.Instant,
    ): String? {
        val finalizer = this.finalizer
        return if (finalizer == null) {
            // Test-only path: no finaliser was wired (AP 6.7-6.8
            // standalone tests). Keep the legacy ACTIVE → COMPLETED
            // transition so those tests stay green.
            sessionStore.transitionOrThrow(session, UploadSessionState.COMPLETED, now)
            null
        } else {
            streamingFinalizer.finalise(
                finalizer = finalizer,
                session = session,
                principal = principal,
                finalSegmentBytes = finalSegmentBytes,
                format = "json",
                now = now,
            )
        }
    }

    private fun saveLeaseExtension(session: UploadSession, now: java.time.Instant): UploadSession {
        val cumulativeBytes = computeCumulativeBytes(session.uploadSessionId)
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
        return updated
    }

    private fun handleReplayAfterCompleted(
        session: UploadSession,
        args: UploadSegmentArgs,
        requestId: String,
    ): ToolCallOutcome {
        val schemaRef = session.finalisedSchemaRef
            ?: throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.sessionCompleted(session.uploadSessionId),
            )
        if (args.segmentTotal != session.segmentTotal) {
            throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.segmentTotalMismatch(
                    session.uploadSessionId,
                    session.segmentTotal,
                ),
            )
        }
        val storedSegment = segmentStoreRef.listSegments(session.uploadSessionId)
            .firstOrNull { it.segmentIndex == args.segmentIndex }
            ?: throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.segmentIndexUnknown(
                    session.uploadSessionId,
                    args.segmentIndex,
                ),
            )
        if (storedSegment.segmentSha256 != args.segmentSha256) {
            throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.segmentHashMismatch(
                    args.segmentIndex,
                    storedSegment.segmentSha256,
                ),
            )
        }
        return buildSegmentResponse(
            session = session,
            acceptedSegmentIndex = args.segmentIndex,
            deduplicated = true,
            ttlSeconds = 0L,
            schemaRef = schemaRef,
            requestId = requestId,
        )
    }

    private fun buildSegmentResponse(
        session: UploadSession,
        acceptedSegmentIndex: Int,
        deduplicated: Boolean,
        ttlSeconds: Long,
        schemaRef: String?,
        requestId: String,
    ): ToolCallOutcome {
        val payload = buildMap {
            put("uploadSessionId", session.uploadSessionId)
            put("acceptedSegmentIndex", acceptedSegmentIndex)
            put("deduplicated", deduplicated)
            put("bytesReceived", session.bytesReceived)
            put("uploadSessionTtlSeconds", ttlSeconds)
            put("uploadSessionState", session.state.name)
            if (schemaRef != null) put("schemaRef", schemaRef)
            put("executionMeta", mapOf("requestId" to requestId))
        }
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
                    segmentStoreRef.writeSegment(
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

    private fun validateSessionState(session: UploadSession, args: UploadSegmentArgs) {
        when (session.state) {
            UploadSessionState.ACTIVE -> Unit
            UploadSessionState.COMPLETED -> throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.sessionCompleted(session.uploadSessionId),
            )
            UploadSessionState.ABORTED -> throw UploadSessionAbortedException(session.uploadSessionId)
            UploadSessionState.EXPIRED -> throw UploadSessionExpiredException(session.uploadSessionId)
            // AP 6.22: a completing-segment retry against a FINALIZING
            // session is a possible reclaim attempt — let it fall
            // through so StreamingFinalizer.claimOrThrow can decide
            // (live lease → Conflict, expired lease → reclaim). Non-
            // final-segment retries are always a Conflict because
            // FINALIZING accepts no new segments.
            UploadSessionState.FINALIZING -> {
                if (!args.isFinalSegment) {
                    throw IdempotencyConflictException(
                        existingFingerprint = UploadFingerprint.sessionCompleted(session.uploadSessionId),
                    )
                }
            }
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
        val expectedOffset = (args.segmentIndex - 1).toLong() * limits.maxUploadSegmentBytes.toLong()
        if (!args.isFinalSegment && args.segmentOffset != expectedOffset) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "segmentOffset",
                        "non-final segment $args.segmentIndex must have offset=$expectedOffset " +
                            "(got ${args.segmentOffset})",
                    ),
                ),
            )
        }
        if (args.isFinalSegment) {
            val finalOffset = (args.segmentIndex - 1).toLong() * limits.maxUploadSegmentBytes.toLong()
            if (args.segmentOffset != finalOffset) {
                throw ValidationErrorException(
                    listOf(
                        ValidationViolation(
                            "segmentOffset",
                            "final segment must have offset=$finalOffset (got ${args.segmentOffset})",
                        ),
                    ),
                )
            }
            if (args.segmentOffset + decodedSize != session.sizeBytes) {
                throw ValidationErrorException(
                    listOf(
                        ValidationViolation(
                            "segmentOffset",
                            "final segment must close the byte range exactly: " +
                                "offset + size (${args.segmentOffset + decodedSize}) " +
                                "must equal sizeBytes (${session.sizeBytes})",
                        ),
                    ),
                )
            }
        }
    }

    private fun mapStoreOutcome(outcome: WriteSegmentOutcome, args: UploadSegmentArgs): Boolean = when (outcome) {
        is WriteSegmentOutcome.Stored -> false
        is WriteSegmentOutcome.AlreadyStored -> true
        is WriteSegmentOutcome.Conflict -> throw IdempotencyConflictException(
            existingFingerprint = UploadFingerprint.segmentHashMismatch(
                args.segmentIndex,
                outcome.existingSegmentSha256,
            ),
        )
        is WriteSegmentOutcome.SizeMismatch -> throw InternalAgentErrorException()
    }

    private fun computeCumulativeBytes(sessionId: String): Long =
        segmentStoreRef.listSegments(sessionId).sumOf { it.sizeBytes }

    private fun isSessionFinalisable(args: UploadSegmentArgs, session: UploadSession): Boolean {
        if (!args.isFinalSegment) return false
        val storedSegments = segmentStoreRef.listSegments(session.uploadSessionId)
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
        return true
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
        private val MAX_ABSOLUTE_LEASE: Duration = UploadSessionDefaults.ABSOLUTE_LEASE
        val DEFAULT_FINALIZING_LEASE_TTL: Duration = Duration.ofMinutes(5)

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
