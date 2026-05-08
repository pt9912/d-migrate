package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.upload.JobInputFinalizer
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.UploadSessionAbortedException
import dev.dmigrate.server.application.error.UploadSessionExpiredException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.AssembledUploadPayload
import dev.dmigrate.server.core.upload.AssembledUploadPayloadFactory
import dev.dmigrate.server.core.upload.FinalizationOutcome
import dev.dmigrate.server.core.upload.FinalizationOutcomeStatus
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.ClaimOutcome
import dev.dmigrate.server.ports.PersistOutcome
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * LF-010 / LF-013 / LN-009 / LN-011: drives the single-writer claim → streaming-assembly →
 * deterministic-id derivation → FinalizationOutcome persistence →
 * finaliser dispatch sequence for the completing segment of an
 * `artifact_upload` call. Pulled out of [ArtifactUploadHandler] to
 * keep that class within the detekt size / function-count budget;
 * see LF-012 / LN-027 / LN-028 / LN-038 for the full sequence.
 */
internal class StreamingFinalizer(
    private val sessionStore: UploadSessionStore,
    private val segmentStore: UploadSegmentStore,
    private val limits: McpLimitsConfig,
    private val payloadFactory: AssembledUploadPayloadFactory,
    private val finalizingLeaseTtl: Duration,
    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.6 (F.6 1/3): optionaler [QuotaService] fuer den
     * Failure-Pfad. Wenn gewired, gibt der Finaliser auf Validation-/
     * Parse-Fehler die Init-Quotas (`ACTIVE_UPLOAD_SESSIONS`,
     * `UPLOAD_BYTES`) frei — analog zur F.4-(3/3)-oversize-Pipeline.
     * Default `null` haelt Bestands-Tests gruen.
     */
    private val quotaService: QuotaService? = null,
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
    ): String = finaliseWith(
        session = session,
        finalSegmentBytes = finalSegmentBytes,
        format = format,
        now = now,
        deriveSchemaId = { payloadSha -> deterministicSchemaId(session, payloadSha, format) },
        runFinalizer = { claimedSession, payload, artifactId, schemaId ->
            finalizer.complete(
                session = claimedSession,
                principal = principal,
                payload = payload,
                artifactId = artifactId,
                schemaId = schemaId!!,
                format = format,
            )
        },
    )

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.5 (F.5 2/3) — Pendant zu [finalise] fuer
     * `uploadIntent=job_input`. Faehrt dieselbe Claim-/Assembly-/
     * Outcome-/Commit-Pipeline, ueberlaesst aber die Bytes-
     * Materialisierung dem [JobInputFinalizer] (kein Schema-Parse,
     * keine `schemaRef`). Rueckgabe ist der gerenderte
     * `artifactRef`-URI; das Feld `UploadSession.finalisedSchemaRef`
     * dient hier generisch als Final-Reference (siehe LF-012 / LN-027 / LN-028 / LN-038
     * "Upload-Metadaten sind nach Finalisierung persistent ...
     * lesbar"). `FinalizationOutcome.schemaId` bleibt `null` (Plan-
     * konform per KDoc).
     */
    fun finaliseJobInput(
        finalizer: JobInputFinalizer,
        session: UploadSession,
        principal: PrincipalContext,
        finalSegmentBytes: ByteArray,
        format: String,
        now: Instant,
    ): String = finaliseWith(
        session = session,
        finalSegmentBytes = finalSegmentBytes,
        format = format,
        now = now,
        deriveSchemaId = { _ -> null },
        runFinalizer = { claimedSession, payload, artifactId, _ ->
            finalizer.complete(
                session = claimedSession,
                principal = principal,
                payload = payload,
                artifactId = artifactId,
                format = format,
            )
        },
    )

    /**
     * Gemeinsame Pipeline fuer beide Finaliser-Pfade:
     * Claim → Assembly → IN_PROGRESS-Outcome → finalizerseitige
     * Materialisierung → SUCCEEDED-Commit + COMPLETED-Transition.
     * Schema-spezifischer `schemaId`-Derivat wird per
     * [deriveSchemaId] injiziert (`null` fuer job_input).
     */
    private fun finaliseWith(
        session: UploadSession,
        finalSegmentBytes: ByteArray,
        format: String,
        now: Instant,
        deriveSchemaId: (String) -> String?,
        runFinalizer: (UploadSession, AssembledUploadPayload, String, String?) -> ServerResourceUri,
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
            val schemaId = deriveSchemaId(payload.sha256)

            val inProgress = FinalizationOutcome(
                claimId = claimId,
                payloadSha256 = payload.sha256,
                artifactId = artifactId,
                schemaId = schemaId,
                format = format,
                status = FinalizationOutcomeStatus.IN_PROGRESS,
            )
            // LF-010 / LF-013 / LN-009 / LN-011: every claim-keyed CAS is checked. If we lose
            // the claim mid-finalisation (Reclaim by another caller
            // after lease expiry), bail out before any further side
            // effect — the new owner drives the deterministic-id
            // pipeline and the persisted outcome stays consistent.
            requirePersistOrConflict(
                claimedSession.uploadSessionId,
                sessionStore.persistFinalizationOutcome(
                    tenantId = claimedSession.tenantId,
                    uploadSessionId = claimedSession.uploadSessionId,
                    claimId = claimId,
                    outcome = inProgress,
                    now = now,
                ),
            )

            val resultUri = try {
                // LF-010 / LF-013 / LN-009 / LN-011 C5: pass the payload through directly so the
                // finaliser parses + materialises via streams. The
                // file-spool keeps `artifactContentStore.write` heap
                // bounded; for schema staging the codec still loads
                // the parsed `SchemaDefinition` on-heap, which scales
                // with the schema, not the artefact size.
                runFinalizer(claimedSession, payload, artifactId, schemaId)
            } catch (failure: RuntimeException) {
                persistFailedOutcomeAndAbort(claimedSession, claimId, inProgress, failure, now)
                throw failure
            }

            val succeeded = inProgress.copy(status = FinalizationOutcomeStatus.SUCCEEDED)
            val rendered = resultUri.render()
            // LF-010 / LF-013 / LN-009 / LN-011: atomic claim-keyed CAS that flips outcome,
            // persists the final-ref AND transitions to COMPLETED in
            // one shot. The split persist + save + transition flow
            // had a Reclaim race window between the steps; this call
            // gates all three writes on `finalizingClaimId == claimId`.
            // LF-010 / LF-013 / LN-009 / LN-011 (F.5 2/3): das Feld heisst `finalisedSchemaRef`,
            // dient aber generisch als final-ref (artifactRef fuer
            // job_input).
            requirePersistOrConflict(
                claimedSession.uploadSessionId,
                sessionStore.commitFinalization(
                    tenantId = claimedSession.tenantId,
                    uploadSessionId = claimedSession.uploadSessionId,
                    claimId = claimId,
                    outcome = succeeded,
                    finalisedSchemaRef = rendered,
                    now = now,
                ),
            )
            // LF-010 / LF-013 / LN-009 / LN-011 § 8.9 (F.9 1/3): Quota-Swap nach COMPLETED.
            // Init-time reserved ACTIVE_UPLOAD_SESSIONS + UPLOAD_BYTES
            // werden freigegeben; die durabel persistierten
            // Artefaktbytes wandern in die STORED_ARTIFACT_BYTES-
            // Dimension. LF-012 / LN-011 / LN-017 / LN-027 wortlaeufig: "COMPLETED bucht
            // gespeicherte Artefaktbytes genau einmal und gibt
            // reservierte Upload-Bytes frei".
            bookSuccessfulFinalisation(claimedSession, payload.sizeBytes)
            rendered
        }
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.9 (F.9 1/3): COMPLETED-Quota-Swap.
     *
     * - Release `ACTIVE_UPLOAD_SESSIONS` (1) — die Session ist nicht
     *   mehr aktiv.
     * - Release `UPLOAD_BYTES` (`session.sizeBytes` — was zur Init-
     *   Zeit reserviert wurde).
     * - Reserve `STORED_ARTIFACT_BYTES` (`payloadSizeBytes` — die
     *   tatsaechlich materialisierten Bytes). Bei `RateLimited` wird
     *   der Counter nicht hochgezaehlt — die Anforderungsakzeptanz "buche
     *   genau einmal" laesst den Caller in Ruhe (das Artefakt ist
     *   schon durabel; ein weiterer Reserve-Versuch beim
     *   Retention-Tick ist vertragskonform). Ein Limit-Reached fuer
     *   diese Dimension ist eine Operator-Diagnose, kein
     *   Caller-Fehler — der Upload ist bereits committed.
     *
     * Idempotent: ein zweiter `commitFinalization`-Aufruf kommt nicht
     * vor (claim-keyed CAS), aber selbst wenn — die Quota-Calls sind
     * idempotent gegenueber Counter-0-Untergrenzen.
     */
    private fun bookSuccessfulFinalisation(session: UploadSession, payloadSizeBytes: Long) {
        val service = quotaService ?: return
        service.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.ACTIVE_UPLOAD_SESSIONS,
                    session.ownerPrincipalId,
                ),
                amount = 1,
            ),
        )
        service.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.UPLOAD_BYTES,
                    session.ownerPrincipalId,
                ),
                amount = session.sizeBytes,
            ),
        )
        // LF-012 / LN-011 / LN-017 / LN-027: das Plan-Limit fuer STORED_ARTIFACT_BYTES ist
        // operator-konfiguriert; ein RateLimited-Outcome wird hier
        // bewusst geschluckt — das Artefakt ist bereits durabel.
        // Das Limit-Reached ist eine Operator-Diagnose und keine
        // Caller-Reaktion mehr.
        @Suppress("UNUSED_VARIABLE")
        val outcome = service.reserve(
            QuotaKey(
                session.tenantId,
                QuotaDimension.STORED_ARTIFACT_BYTES,
                session.ownerPrincipalId,
            ),
            amount = payloadSizeBytes,
        )
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011: maps a [PersistOutcome] returned by
     * [UploadSessionStore.persistFinalizationOutcome] to the typed
     * exception that matches the failure class. A `ClaimMismatch`
     * means our lease expired and another caller reclaimed, so we
     * surface the same retryable Conflict as a `COMPLETED`-replay.
     */
    private fun requirePersistOrConflict(sessionId: String, outcome: PersistOutcome) {
        when (outcome) {
            is PersistOutcome.Persisted -> Unit
            is PersistOutcome.ClaimMismatch -> throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.sessionCompleted(sessionId),
            )
            is PersistOutcome.WrongState -> throw mapWrongState(outcome.state, sessionId)
            is PersistOutcome.NotFound -> throw InternalAgentErrorException()
        }
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011: a replay against an `ABORTED` session whose persisted
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
            // LF-010 / LF-013 / LN-009 / LN-011 § 8.9 (F.9 2/3): Upload-Finalisierungs-Timeout
            // -> OPERATION_TIMEOUT. Der Sweeper aus
            // `UploadSessionService.timeoutStaleFinalizingSessions`
            // persistiert den Outcome; ein Replay-Call (z.B. erneuter
            // `artifact_upload`) bekommt deterministisch denselben
            // Fehler.
            "OPERATION_TIMEOUT" -> throw dev.dmigrate.server.application.error.OperationTimeoutException(
                operation = "upload_finalisation",
                budget = java.time.Duration.ZERO,
            )
            else -> throw InternalAgentErrorException()
        }
    }

    private fun claimOrThrow(
        session: UploadSession,
        claimId: String,
        now: Instant,
        leaseExpires: Instant,
    ): UploadSession {
        val first = sessionStore.tryClaimFinalization(
            tenantId = session.tenantId,
            uploadSessionId = session.uploadSessionId,
            claimId = claimId,
            claimedAt = now,
            leaseExpiresAt = leaseExpires,
        )
        return when (first) {
            is ClaimOutcome.Acquired -> first.session
            is ClaimOutcome.AlreadyClaimed -> resolveAlreadyClaimed(session, claimId, now, leaseExpires, first)
            is ClaimOutcome.WrongState -> throw mapWrongState(first.state, session.uploadSessionId)
            is ClaimOutcome.NotFound -> throw ResourceNotFoundException(session.resourceUri)
        }
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011: when [ClaimOutcome.AlreadyClaimed] comes back the
     * session is already in `FINALIZING`. If the existing lease is
     * still live, the second completing call surfaces a retryable
     * Conflict without side effects. If the lease has expired,
     * [UploadSessionStore.reclaimStaleFinalization] takes the claim
     * over deterministically and the new owner drives the same
     * finalisation pipeline; the deterministic artefact / schema ids
     * keep the side effects idempotent.
     */
    private fun resolveAlreadyClaimed(
        session: UploadSession,
        claimId: String,
        now: Instant,
        leaseExpires: Instant,
        previous: ClaimOutcome.AlreadyClaimed,
    ): UploadSession {
        if (!previous.leaseExpiresAt.isBefore(now)) {
            throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.sessionCompleted(session.uploadSessionId),
            )
        }
        val reclaim = sessionStore.reclaimStaleFinalization(
            tenantId = session.tenantId,
            uploadSessionId = session.uploadSessionId,
            newClaimId = claimId,
            claimedAt = now,
            leaseExpiresAt = leaseExpires,
            now = now,
        )
        return when (reclaim) {
            is ClaimOutcome.Acquired -> reclaim.session
            is ClaimOutcome.AlreadyClaimed -> throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.sessionCompleted(session.uploadSessionId),
            )
            is ClaimOutcome.WrongState -> throw mapWrongState(reclaim.state, session.uploadSessionId)
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
                sanitizedErrorMessage = sanitizedErrorMessageOf(failure),
            )
            persistFailedOutcomeBestEffort(session, claimId, outcome, now)
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
            sanitizedErrorMessage = sanitizedErrorMessageOf(failure),
        )
        persistFailedOutcomeBestEffort(session, claimId, failed, now)
    }

    /**
     * Persists the FAILED [FinalizationOutcome] best-effort and
     * transitions the session to `ABORTED` only when the persist
     * actually landed under our claim id. Reclaim-after-failure
     * means the new owner drives the terminal state — we must not
     * transition behind their back. The caller re-throws the
     * original `failure` regardless so the structured error always
     * reaches the client.
     */
    private fun persistFailedOutcomeBestEffort(
        session: UploadSession,
        claimId: String,
        outcome: FinalizationOutcome,
        now: Instant,
    ) {
        val persisted = sessionStore.persistFinalizationOutcome(
            tenantId = session.tenantId,
            uploadSessionId = session.uploadSessionId,
            claimId = claimId,
            outcome = outcome,
            now = now,
        )
        when (persisted) {
            is PersistOutcome.Persisted -> {
                transitionToAbortedBestEffort(session, now)
                // LF-010 / LF-013 / LN-009 / LN-011 § 8.6 (F.6 1/3): nach durablem ABORTED gibt
                // der Finaliser die Init-Quotas frei, damit der Tenant
                // nach einem Validation-/Parse-Fehler nicht in seinen
                // Limits gebunden bleibt. Idempotent — nur der erste
                // Persist-Persisted-Pfad ruft release.
                releaseInitQuotas(session)
            }
            is PersistOutcome.ClaimMismatch -> LOG.debug(
                "FAILED-outcome skipped: claim {} for session {} no longer current ({})",
                claimId, session.uploadSessionId, persisted.currentClaimId,
            )
            is PersistOutcome.WrongState -> LOG.debug(
                "FAILED-outcome skipped: session {} no longer FINALIZING (state={})",
                session.uploadSessionId, persisted.state,
            )
            is PersistOutcome.NotFound -> LOG.debug(
                "FAILED-outcome skipped: session {} not found",
                session.uploadSessionId,
            )
        }
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.6 (F.6 1/3): gibt die Init-time Quotas
     * (`ACTIVE_UPLOAD_SESSIONS=1`, `UPLOAD_BYTES=session.sizeBytes`)
     * fuer den Session-Owner frei. Idempotent (`QuotaService.release`
     * ist no-op bei nicht-positivem aktuellem Counter). No-op wenn
     * kein QuotaService gewired ist (Bestands-Tests).
     */
    private fun releaseInitQuotas(session: UploadSession) {
        val service = quotaService ?: return
        service.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.ACTIVE_UPLOAD_SESSIONS,
                    session.ownerPrincipalId,
                ),
                amount = 1,
            ),
        )
        service.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.UPLOAD_BYTES,
                    session.ownerPrincipalId,
                ),
                amount = session.sizeBytes,
            ),
        )
    }

    /**
     * Transition our claimed session to `ABORTED`. If the CAS races
     * with a Reclaim (state may already be FINALIZING under another
     * claim, or COMPLETED if the new owner finished), do nothing —
     * the persisted FAILED outcome under our claimId still drives
     * the replay diagnostic, and the new owner / completed state
     * is authoritative.
     */
    private fun transitionToAbortedBestEffort(session: UploadSession, now: Instant) {
        when (sessionStore.transition(session.tenantId, session.uploadSessionId, UploadSessionState.ABORTED, now)) {
            is TransitionOutcome.Applied,
            is TransitionOutcome.IllegalTransition,
            is TransitionOutcome.NotFound -> Unit
        }
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

    /**
     * Allowlist for the human-readable sanitised message persisted in
     * [FinalizationOutcome.sanitizedErrorMessage]. Only domain-error
     * messages (`ValidationErrorException`) are passed through — they
     * are constructed from validator findings without local paths or
     * raw exception traces. Everything else (IO failures, internal
     * errors, third-party stack messages) is dropped to `null` so
     * spool paths or codec-internal diagnostics do NOT leak into
     * tool responses on a replay.
     */
    private fun sanitizedErrorMessageOf(failure: Throwable): String? = when (failure) {
        is ValidationErrorException -> failure.message?.take(SANITIZED_MESSAGE_MAX_CHARS)
        else -> null
    }

    private fun hexOf(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER_BYTES: Int = 64 * 1024
        const val DETERMINISTIC_ID_BYTES: Int = 24
        const val SANITIZED_MESSAGE_MAX_CHARS: Int = 200

        @JvmStatic
        private val LOG = LoggerFactory.getLogger(StreamingFinalizer::class.java)
    }
}
