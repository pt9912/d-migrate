package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.UploadSessionAbortedException
import dev.dmigrate.server.application.error.UploadSessionExpiredException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.upload.AssembledUploadPayload
import dev.dmigrate.server.core.upload.AssembledUploadPayloadFactory
import dev.dmigrate.server.core.upload.FinalizationOutcome
import dev.dmigrate.server.core.upload.FinalizationOutcomeStatus
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.ClaimOutcome
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * AP 6.22: drives the single-writer claim → streaming-assembly →
 * deterministic-id derivation → FinalizationOutcome persistence →
 * finaliser dispatch sequence for the completing segment of an
 * `artifact_upload` call. Pulled out of [ArtifactUploadHandler] to
 * keep that class within the detekt size / function-count budget;
 * see `ImpPlan-0.9.6-C.md` §6.22 for the full sequence.
 */
internal class StreamingFinalizer(
    private val sessionStore: UploadSessionStore,
    private val segmentStore: UploadSegmentStore,
    private val limits: McpLimitsConfig,
    private val payloadFactory: AssembledUploadPayloadFactory,
    private val finalizingLeaseTtl: Duration,
    private val claimIdGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Runs the §6.22 finalisation pipeline on a session that has just
     * received its completing segment. Returns the registered
     * `schemaRef` URI on success; throws the typed exception that
     * matches the failure class on failure (after persisting a
     * sanitised [FinalizationOutcome] and rolling the session to
     * `ABORTED`).
     */
    fun finalise(
        finalizer: SchemaStagingFinalizer,
        session: UploadSession,
        principal: PrincipalContext,
        finalSegmentBytes: ByteArray,
        format: String,
        now: Instant,
    ): String {
        val claimId = claimIdGenerator()
        val leaseExpires = now.plus(finalizingLeaseTtl)
        val claimedSession = claimOrThrow(session, claimId, now, leaseExpires)

        val payload = assembleSessionPayloadOrAbort(
            session = claimedSession,
            finalSegmentBytes = finalSegmentBytes,
            claimId = claimId,
            format = format,
            now = now,
        )

        return payload.use {
            val artifactId = deterministicArtifactId(claimedSession, payload.sha256, format)
            val schemaId = deterministicSchemaId(claimedSession, payload.sha256, format)

            val inProgress = FinalizationOutcome(
                claimId = claimId,
                payloadSha256 = payload.sha256,
                artifactId = artifactId,
                schemaId = schemaId,
                format = format,
                status = FinalizationOutcomeStatus.IN_PROGRESS,
            )
            sessionStore.persistFinalizationOutcome(
                tenantId = claimedSession.tenantId,
                uploadSessionId = claimedSession.uploadSessionId,
                claimId = claimId,
                outcome = inProgress,
                now = now,
            )

            val schemaUri = try {
                // C5 will replace this readAllBytes() bridge with a
                // streaming finaliser API; the spool already keeps
                // the on-wire heap deterministic so only the local
                // finaliser copy still scales with payload size.
                finalizer.complete(
                    session = claimedSession,
                    principal = principal,
                    assembledBytes = payload.openStream().use { it.readAllBytes() },
                    format = format,
                )
            } catch (failure: RuntimeException) {
                persistFailedOutcomeAndAbort(claimedSession, claimId, inProgress, failure, now)
                throw failure
            }

            val succeeded = inProgress.copy(status = FinalizationOutcomeStatus.SUCCEEDED)
            sessionStore.persistFinalizationOutcome(
                tenantId = claimedSession.tenantId,
                uploadSessionId = claimedSession.uploadSessionId,
                claimId = claimId,
                outcome = succeeded,
                now = now,
            )
            val rendered = schemaUri.render()
            // AP 6.18: persist the schemaRef onto the session BEFORE
            // the COMPLETED transition so a replay reads it back.
            val current = sessionStore.findById(claimedSession.tenantId, claimedSession.uploadSessionId)
                ?: throw InternalAgentErrorException()
            sessionStore.save(current.copy(finalisedSchemaRef = rendered))
            sessionStore.transitionOrThrow(current, UploadSessionState.COMPLETED, now)
            rendered
        }
    }

    /**
     * AP 6.22: a replay against an `ABORTED` session whose persisted
     * [FinalizationOutcome] is `FAILED` re-throws the same sanitised
     * error class. Returns silently when no failed outcome was
     * persisted (the caller falls through to the normal Aborted
     * exception path).
     */
    fun replayFailedOutcomeIfAvailable(session: UploadSession) {
        val outcome = session.finalizationOutcome ?: return
        if (outcome.status != FinalizationOutcomeStatus.FAILED) return
        when (outcome.sanitizedErrorCode) {
            "VALIDATION_ERROR" -> throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "finalisation",
                        outcome.sanitizedErrorMessage ?: "validation failed",
                    ),
                ),
            )
            "PAYLOAD_TOO_LARGE" -> throw PayloadTooLargeException(0, 0)
            else -> throw InternalAgentErrorException()
        }
    }

    private fun claimOrThrow(
        session: UploadSession,
        claimId: String,
        now: Instant,
        leaseExpires: Instant,
    ): UploadSession {
        val outcome = sessionStore.tryClaimFinalization(
            tenantId = session.tenantId,
            uploadSessionId = session.uploadSessionId,
            claimId = claimId,
            claimedAt = now,
            leaseExpiresAt = leaseExpires,
        )
        return when (outcome) {
            is ClaimOutcome.Acquired -> outcome.session
            is ClaimOutcome.AlreadyClaimed -> throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.sessionCompleted(session.uploadSessionId),
            )
            is ClaimOutcome.WrongState -> throw mapWrongState(outcome.state, session.uploadSessionId)
            is ClaimOutcome.NotFound -> throw ResourceNotFoundException(session.resourceUri)
        }
    }

    private fun mapWrongState(state: UploadSessionState, sessionId: String): RuntimeException = when (state) {
        UploadSessionState.COMPLETED -> IdempotencyConflictException(
            existingFingerprint = UploadFingerprint.sessionCompleted(sessionId),
        )
        UploadSessionState.ABORTED -> UploadSessionAbortedException(sessionId)
        UploadSessionState.EXPIRED -> UploadSessionExpiredException(sessionId)
        else -> InternalAgentErrorException()
    }

    private fun assembleSessionPayloadOrAbort(
        session: UploadSession,
        finalSegmentBytes: ByteArray,
        claimId: String,
        format: String,
        now: Instant,
    ): AssembledUploadPayload {
        return try {
            assembleSessionPayload(session, finalSegmentBytes)
        } catch (failure: RuntimeException) {
            // Spool was cleaned by assembleSessionPayload itself in
            // its try/finally. Persist a sanitised FAILED outcome
            // under the active claim so a replay returns the same
            // error class.
            val outcome = FinalizationOutcome(
                claimId = claimId,
                payloadSha256 = "",
                artifactId = "",
                schemaId = null,
                format = format,
                status = FinalizationOutcomeStatus.FAILED,
                sanitizedErrorCode = sanitizedErrorCodeOf(failure),
                sanitizedErrorMessage = failure.message?.take(SANITIZED_MESSAGE_MAX_CHARS),
            )
            sessionStore.persistFinalizationOutcome(
                tenantId = session.tenantId,
                uploadSessionId = session.uploadSessionId,
                claimId = claimId,
                outcome = outcome,
                now = now,
            )
            sessionStore.transition(session.tenantId, session.uploadSessionId, UploadSessionState.ABORTED, now)
            throw failure
        }
    }

    private fun persistFailedOutcomeAndAbort(
        session: UploadSession,
        claimId: String,
        inProgress: FinalizationOutcome,
        failure: RuntimeException,
        now: Instant,
    ) {
        val failed = inProgress.copy(
            status = FinalizationOutcomeStatus.FAILED,
            sanitizedErrorCode = sanitizedErrorCodeOf(failure),
            sanitizedErrorMessage = failure.message?.take(SANITIZED_MESSAGE_MAX_CHARS),
        )
        sessionStore.persistFinalizationOutcome(
            tenantId = session.tenantId,
            uploadSessionId = session.uploadSessionId,
            claimId = claimId,
            outcome = failed,
            now = now,
        )
        sessionStore.transition(session.tenantId, session.uploadSessionId, UploadSessionState.ABORTED, now)
    }

    private fun assembleSessionPayload(
        session: UploadSession,
        finalSegmentBytes: ByteArray,
    ): AssembledUploadPayload {
        val segments = segmentStore.listSegments(session.uploadSessionId).sortedBy { it.segmentIndex }
        validateAssemblyInvariants(session, segments)

        val spool = payloadFactory.allocate(session.uploadSessionId)
        val cap = limits.maxArtifactUploadBytes.toLong()
        val digest = MessageDigest.getInstance("SHA-256")
        var written: Long = 0

        try {
            for (segment in segments) {
                written = streamSegmentInto(spool.output, digest, written, cap, session, segment, finalSegmentBytes)
            }
            verifyAssemblyTotals(session, written, digest)
            return spool.publish(written, hexOf(digest.digest()))
        } catch (failure: Throwable) {
            spool.close()
            throw failure
        }
    }

    private fun streamSegmentInto(
        spoolOutput: java.io.OutputStream,
        digest: MessageDigest,
        startWritten: Long,
        cap: Long,
        session: UploadSession,
        segment: UploadSegment,
        finalSegmentBytes: ByteArray,
    ): Long {
        val source: InputStream = if (segment.segmentIndex == session.segmentTotal) {
            ByteArrayInputStream(finalSegmentBytes)
        } else {
            segmentStore.openSegmentRangeRead(
                session.uploadSessionId,
                segment.segmentIndex,
                offset = 0L,
                length = segment.sizeBytes,
            )
        }
        val buffer = ByteArray(BUFFER_BYTES)
        var written = startWritten
        source.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (written > Long.MAX_VALUE - read) throw InternalAgentErrorException()
                if (written + read > cap) throw PayloadTooLargeException(written + read, cap)
                spoolOutput.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                written += read
            }
        }
        return written
    }

    private fun verifyAssemblyTotals(session: UploadSession, written: Long, digest: MessageDigest) {
        // We need the digest output for the size check too — clone
        // to avoid double-finalising the MessageDigest.
        val totalSha = hexOf(digest.clone().let { (it as MessageDigest).digest() })
        if (written != session.sizeBytes) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "isFinalSegment",
                        "assembled byte count ($written) does not match session.sizeBytes (${session.sizeBytes})",
                    ),
                ),
            )
        }
        if (totalSha != session.checksumSha256) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "checksumSha256",
                        "rebuilt total hash does not match init checksumSha256",
                    ),
                ),
            )
        }
    }

    private fun validateAssemblyInvariants(session: UploadSession, segments: List<UploadSegment>) {
        if (segments.size != session.segmentTotal) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "isFinalSegment",
                        "expected ${session.segmentTotal} segments, found ${segments.size}",
                    ),
                ),
            )
        }
        var runningOffset = 0L
        for (segment in segments) {
            if (segment.segmentOffset != runningOffset) {
                throw ValidationErrorException(
                    listOf(
                        ValidationViolation(
                            "segmentOffset",
                            "segment ${segment.segmentIndex} offset (${segment.segmentOffset}) " +
                                "does not match running offset ($runningOffset)",
                        ),
                    ),
                )
            }
            runningOffset += segment.sizeBytes
        }
    }

    private fun deterministicArtifactId(
        session: UploadSession,
        payloadSha: String,
        format: String,
    ): String = "art-" + hexOf(
        MessageDigest.getInstance("SHA-256").digest(idMaterial(session, payloadSha, format)),
    ).take(DETERMINISTIC_ID_BYTES)

    private fun deterministicSchemaId(
        session: UploadSession,
        payloadSha: String,
        format: String,
    ): String = "sch-" + hexOf(
        MessageDigest.getInstance("SHA-256").digest(idMaterial(session, payloadSha, format)),
    ).take(DETERMINISTIC_ID_BYTES)

    private fun idMaterial(session: UploadSession, payloadSha: String, format: String): ByteArray =
        "${session.tenantId.value}|${session.uploadSessionId}|$payloadSha|$format".toByteArray(Charsets.UTF_8)

    private fun sanitizedErrorCodeOf(failure: Throwable): String = when (failure) {
        is ValidationErrorException -> "VALIDATION_ERROR"
        is PayloadTooLargeException -> "PAYLOAD_TOO_LARGE"
        is InternalAgentErrorException -> "INTERNAL_ERROR"
        else -> "FINALIZATION_FAILED"
    }

    private fun hexOf(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER_BYTES: Int = 64 * 1024
        const val DETERMINISTIC_ID_BYTES: Int = 24
        const val SANITIZED_MESSAGE_MAX_CHARS: Int = 200
    }
}
