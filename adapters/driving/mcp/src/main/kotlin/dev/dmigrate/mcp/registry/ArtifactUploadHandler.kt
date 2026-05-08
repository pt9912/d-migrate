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
import dev.dmigrate.mcp.upload.JobInputFinalizer
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
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.upload.AssembledUploadPayloadFactory
import dev.dmigrate.server.core.upload.FinalizationOutcome
import dev.dmigrate.server.core.upload.FinalizationOutcomeStatus
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
import java.time.Instant
import java.util.Base64

/**
 * LF-012 / LN-027 / LN-028 / LN-038 + LF-010 / LF-013 / LN-009 / LN-011: `artifact_upload` per LF-012 / LN-027 / LN-028 / LN-038
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
 *   `schemaRef` (LF-012 / LN-027 / LN-028 / LN-038)
 * - completing-segment retry on `ABORTED` whose outcome was `FAILED`
 *   → re-throws sanitised error class (LF-010 / LF-013 / LN-009 / LN-011)
 * - any retry against a live `FINALIZING` claim → retryable Conflict
 *   without side effects
 */
/**
 * @property finalizer production wiring MUST inject a finaliser
 *   (LF-012 / LN-038 wiring defaults to [dev.dmigrate.mcp.schema.DefaultSchemaStagingFinalizer]).
 *   The `null` default is reserved for LF-012 / LN-027 / LN-028 / LN-038 standalone tests
 *   that exercise the segment-write path without producing a
 *   `schemaRef`. With `null`, the completing segment uses the
 *   legacy `ACTIVE → COMPLETED` shortcut WITHOUT a single-writer
 *   FINALIZING claim — concurrent calls in such a setup may race
 *   into both COMPLETED, which is unsafe for production.
 */
internal class ArtifactUploadHandler(
    private val sessionStore: UploadSessionStore,
    segmentStore: UploadSegmentStore,
    private val quotaService: QuotaService,
    private val limits: McpLimitsConfig,
    private val options: Options,
) : ToolHandler {

    /**
     * LF-010 / LF-013 / LN-009 / LN-011: exposed as `internal val` so wiring-end-to-end tests
     * in `:adapters:driving:mcp` can pin that the production CLI
     * threads `FileSpoolAssembledUploadPayloadFactory` all the way to
     * the handler — a guard against the original review-#1
     * regression where the file-spool factory only landed in the
     * `McpRuntimeWiring` DTO but was substituted by the in-memory default
     * during handler construction.
     */
    internal val payloadFactory: AssembledUploadPayloadFactory = options.payloadFactory

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val segmentStoreRef = segmentStore
    private val streamingFinalizer = StreamingFinalizer(
        sessionStore = sessionStore,
        segmentStore = segmentStore,
        limits = limits,
        payloadFactory = payloadFactory,
        finalizingLeaseTtl = options.finalizingLeaseTtl,
        // LF-010 / LF-013 / LN-009 / LN-011 § 8.6 (F.6 1/3): Init-Quotas auf Validation-/Parse-
        // Failure freigeben (analog zur F.4-(3/3)-oversize-Pipeline).
        quotaService = quotaService,
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
        enforceIntentScope(session, context.principal)
        validateSessionSizeContract(session)
        // LF-010 / LF-013 / LN-009 / LN-011 § 8.9 (F.9 3/3): AuditFields-Population fuer
        // Around-/Finally-Audit (Vertrag: "Around-/Finally-Audit fuer
        // Init, Segment, Abort ... vervollstaendigen").
        context.auditFields.resourceRefs = listOf(session.resourceUri.render())
        if (session.state == UploadSessionState.COMPLETED) {
            return handleReplayAfterCompleted(session, args, context.requestId)
        }
        if (session.state == UploadSessionState.ABORTED) {
            // LF-010 / LF-013 / LN-009 / LN-011: if a sanitised FAILED outcome was persisted,
            // re-throw the same error class so retries are
            // deterministic. Otherwise fall through to the regular
            // Aborted exception path.
            streamingFinalizer.replayFailedOutcomeIfAvailable(session)
        }
        validateSessionState(session, args)
        validateSegmentSequence(args, session)

        val bytes = decodeBase64(args.contentBase64)
        if (bytes.size > limits.maxUploadSegmentBytes) {
            terminallyFailWithPayloadTooLarge(
                session = session,
                actualBytes = bytes.size.toLong(),
                principal = context.principal,
                now = options.clock.instant(),
            )
        }
        validateSegmentHash(args.segmentSha256, bytes)
        validateSegmentBudget(args, bytes.size, session)

        val deduplicated = writeWithQuota(args, bytes, session, context.principal)

        val now = options.clock.instant()
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

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.5 (F.5 2/3): dispatchet die finalisierung anhand
     * des `session.uploadIntent`. `schema_staging_readonly` geht
     * weiter durch den LF-010 / LF-013 / LN-009 / LN-011-Schema-Pfad ([SchemaStagingFinalizer]),
     * `job_input` durch den neuen [JobInputFinalizer] (Bytes-only,
     * keine Schema-Validierung). Ohne passend gewireten Finaliser
     * faellt der Pfad auf den legacy `ACTIVE → COMPLETED`-Shortcut
     * zurueck — Bestands-Tests ohne Finaliser-Wiring bleiben gruen.
     */
    private fun runFinalisation(
        session: UploadSession,
        principal: PrincipalContext,
        finalSegmentBytes: ByteArray,
        now: java.time.Instant,
    ): String? {
        return when (session.uploadIntent) {
            ArtifactUploadInitHandler.INTENT_JOB_INPUT ->
                runJobInputFinalisation(session, principal, finalSegmentBytes, now)
            else ->
                runSchemaStagingFinalisation(session, principal, finalSegmentBytes, now)
        }
    }

    private fun runSchemaStagingFinalisation(
        session: UploadSession,
        principal: PrincipalContext,
        finalSegmentBytes: ByteArray,
        now: java.time.Instant,
    ): String? {
        val finalizer = options.finalizer
        return if (finalizer == null) {
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

    private fun runJobInputFinalisation(
        session: UploadSession,
        principal: PrincipalContext,
        finalSegmentBytes: ByteArray,
        now: java.time.Instant,
    ): String? {
        val finalizer = options.jobInputFinalizer
        return if (finalizer == null) {
            // Tests ohne JobInputFinalizer-Wiring: legacy COMPLETED-
            // Transition; in Production muss F.5 (3/3) den Finaliser
            // wiren, sonst bleibt der Artefakt-Materialise-Schritt aus.
            sessionStore.transitionOrThrow(session, UploadSessionState.COMPLETED, now)
            null
        } else {
            streamingFinalizer.finaliseJobInput(
                finalizer = finalizer,
                session = session,
                principal = principal,
                finalSegmentBytes = finalSegmentBytes,
                format = formatFromMimeType(session.mimeType),
                now = now,
            )
        }
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.5 (F.5 2/3): leichter MIME-zu-Format-Mapper fuer
     * den deterministischen `artifactId`-Material-String. Werte
     * folgen [SchemaFileResolver]-/LF-010 / LF-013 / LN-009 / LN-011-Konventionen ("json",
     * "yaml") und fallen sonst auf "bin" zurueck. Der MIME-Type
     * selbst landet trotzdem 1:1 in `ArtifactRecord.contentType`,
     * sodass der Wire-Klient die volle Information sieht.
     */
    private fun formatFromMimeType(mimeType: String): String = when {
        mimeType.equals("application/json", ignoreCase = true) ||
            mimeType.endsWith("+json", ignoreCase = true) -> "json"
        // LF-010 / LF-013 / LN-009 / LN-011 § 8.10 (F.10): CSV-Import-Artefakte werden in LF-010 / LF-013 / LN-009 / LN-011
        // erlaubt; beide Allowlist-Schreibweisen mappen auf dasselbe
        // Format, damit Caller mit `application/csv` denselben
        // deterministischen `art-...`-Id erhalten wie mit `text/csv`.
        mimeType.equals("text/csv", ignoreCase = true) ||
            mimeType.equals("application/csv", ignoreCase = true) -> "csv"
        mimeType.equals("text/plain", ignoreCase = true) -> "txt"
        mimeType.equals("application/x-ndjson", ignoreCase = true) -> "ndjson"
        mimeType.equals("application/yaml", ignoreCase = true) ||
            mimeType.equals("text/yaml", ignoreCase = true) -> "yaml"
        else -> "bin"
    }

    private fun saveLeaseExtension(session: UploadSession, now: java.time.Instant): UploadSession {
        val cumulativeBytes = computeCumulativeBytes(session.uploadSessionId)
        val absoluteHardCap = session.createdAt.plus(MAX_ABSOLUTE_LEASE)
        val newAbsolute = minOf(now.plus(options.initialTtl), absoluteHardCap)
        val newIdle = minOf(now.plus(options.idleTimeout), newAbsolute)
        val updated = session.copy(
            bytesReceived = cumulativeBytes,
            updatedAt = now,
            idleTimeoutAt = newIdle,
            absoluteLeaseExpiresAt = newAbsolute,
        )
        sessionStore.save(updated)
        return updated
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.4 (F.4 1/3): intent-abhaengiger Scope-Check nach
     * dem no-oracle Session-/Owner-Lookup. Dispatch erzwingt nur das
     * lockere `dmigrate:read`-Gate; der Handler erzwingt zusaetzlich
     * `dmigrate:artifact:upload` fuer policy-pflichtige
     * `job_input`-Sessions, sodass ein read-only Caller einen
     * `job_input`-Upload nicht ueberschreiben kann. Ein
     * `schema_staging_readonly`-Caller darf mit reinem
     * `dmigrate:read` bleiben (LF-012 / LN-027 / LN-028 / LN-038 "session-scoped read-only
     * Upload-Berechtigung") — ein staerkerer Scope wie
     * `dmigrate:artifact:upload` reicht ebenfalls. Der Aufruf liegt
     * VOR jeder Segment-/Quota-/TTL-Mutation; ein gescheiterter
     * Scope-Check produziert Forbidden ohne Side Effects.
     */
    private fun enforceIntentScope(session: UploadSession, principal: PrincipalContext) {
        if (principal.isAdmin) return
        val intent = session.uploadIntent
        val acceptable = intentScopesFor(intent)
        if (principal.scopes.intersect(acceptable).isEmpty()) {
            throw ForbiddenPrincipalException(
                principalId = principal.principalId,
                reason = "missing scope(s) for uploadIntent=$intent: any of ${acceptable.sorted()}",
            )
        }
    }

    private fun intentScopesFor(intent: String): Set<String> = when (intent) {
        ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY ->
            // `dmigrate:read` reicht; ein staerkerer Caller mit
            // `dmigrate:artifact:upload` darf auch read-only stagen.
            SCOPE_READONLY_ACCEPTED
        ArtifactUploadInitHandler.INTENT_JOB_INPUT -> SCOPE_ARTIFACT_UPLOAD
        // Fail-closed fuer unbekannte Intents. LF-010 / LF-013 / LN-009 / LN-011 erlaubt nur die
        // beiden Werte; eine Session mit fremdem Intent waere ein
        // Server-Fehler in F-Tests.
        else -> SCOPE_ARTIFACT_UPLOAD
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.4 (F.4 3/3): terminale Failure-Pipeline fuer
     * oversize Segmente. Vertrag: "zu grosses Segment setzt Session
     * terminal auf FAILED, speichert ein Failure-Outcome, startet
     * Cleanup und gibt Quotas frei". Reihenfolge ist wichtig — der
     * Outcome wird VOR der Transition gespeichert, sodass ein Retry
     * gegen die ABORTED-Session via
     * `replayFailedOutcomeIfAvailable` denselben sanitisierten
     * Fehler bekommt (Vertragswortlaut "abweichende Wiederholung
     * deterministisch ablehnen").
     *
     * Cleanup laeuft best-effort: ein Fehler beim Loeschen der
     * Segmente darf den Quota-Release nicht verhindern, sonst bleibt
     * der Tenant im aktiven-Session-Counter haengen, obwohl die
     * Session ABORTED ist.
     */
    @Suppress("ThrowsCount") // einzig der finale Throw nach allen Side Effects
    private fun terminallyFailWithPayloadTooLarge(
        session: UploadSession,
        actualBytes: Long,
        principal: PrincipalContext,
        now: Instant,
    ): Nothing {
        val maxBytes = limits.maxUploadSegmentBytes.toLong()
        val outcome = FinalizationOutcome(
            // Pre-Finalisation-Failure hat keinen echten FINALIZING-
            // Claim — `pre-finalisation` ist ein deterministischer
            // sentinel-Wert, der `claimId`-CAS-Vergleiche im Replay
            // nicht trifft (replayFailedOutcomeIfAvailable schaut
            // nur status + sanitizedErrorCode an).
            claimId = "pre-finalisation",
            payloadSha256 = "n/a",
            artifactId = "n/a",
            schemaId = null,
            format = "n/a",
            status = FinalizationOutcomeStatus.FAILED,
            sanitizedErrorCode = "PAYLOAD_TOO_LARGE",
            sanitizedErrorMessage = "segment exceeded maxUploadSegmentBytes",
        )
        sessionStore.save(
            session.copy(
                finalizationOutcome = outcome,
                updatedAt = now,
            ),
        )
        sessionStore.transitionOrThrow(session, UploadSessionState.ABORTED, now)
        runCatching { segmentStoreRef.deleteAllForSession(session.uploadSessionId) }
        releaseInitQuotas(session, principal)
        throw PayloadTooLargeException(actualBytes = actualBytes, maxBytes = maxBytes)
    }

    private fun releaseInitQuotas(session: UploadSession, principal: PrincipalContext) {
        // Init reservierte ACTIVE_UPLOAD_SESSIONS=1 + UPLOAD_BYTES=
        // session.sizeBytes (siehe ArtifactUploadInitHandler.
        // QuotaService.reserve). Beide Reservierungen werden hier
        // freigegeben, sonst bleibt der Tenant nach einem Terminal-
        // Failure in seinen Limits gebunden.
        quotaService.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.ACTIVE_UPLOAD_SESSIONS,
                    principal.principalId,
                ),
                amount = 1,
            ),
        )
        quotaService.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.UPLOAD_BYTES,
                    principal.principalId,
                ),
                amount = session.sizeBytes,
            ),
        )
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.4 (F.4 2/3): defensive Pruefung gegen Session-
     * Misskonfiguration. `sizeBytes=0` ist nur fuer das Single-Empty-
     * Segment in nicht-Schema-`job_input` zulaessig (Vertrag: "Null-Byte-
     * Upload als ein finales leeres Segment modellieren"). Init blockt
     * die verbotenen Kombinationen bereits, aber Sessions koennten
     * theoretisch ueber Store-Manipulation entstehen — der Handler
     * lehnt sie deterministisch mit `VALIDATION_ERROR` ab, statt
     * unbeabsichtigt zu finalisieren.
     */
    private fun validateSessionSizeContract(session: UploadSession) {
        if (session.sizeBytes != 0L) return
        if (session.uploadIntent == ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "sizeBytes",
                    "must be > 0 for uploadIntent=schema_staging_readonly",
                )),
            )
        }
        if (session.artifactKind == ArtifactKind.SCHEMA) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "sizeBytes",
                    "must be > 0 for artifactKind=SCHEMA",
                )),
            )
        }
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
            // LF-010 / LF-013 / LN-009 / LN-011: a completing-segment retry against a FINALIZING
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
        return minOf(options.initialTtl.seconds, remainingAbsolute).coerceAtLeast(0L)
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

    internal data class Options(
        val clock: Clock,
        val initialTtl: Duration = ArtifactUploadInitHandler.DEFAULT_INITIAL_TTL,
        val idleTimeout: Duration = ArtifactUploadInitHandler.DEFAULT_IDLE_TIMEOUT,
        val finalizer: SchemaStagingFinalizer? = null,
        /**
         * LF-010 / LF-013 / LN-009 / LN-011 § 8.5 (F.5 2/3): policy-pflichtiger
         * `uploadIntent=job_input`-Pfad. Default `null` haelt
         * Bestands-Tests gruen; Production wiring muss den Finaliser
         * setzen, sonst materialisiert F.5 keine Artefaktbytes.
         */
        val jobInputFinalizer: JobInputFinalizer? = null,
        val payloadFactory: AssembledUploadPayloadFactory = AssembledUploadPayloadFactory.inMemory(),
        val finalizingLeaseTtl: Duration = Duration.ofMinutes(5),
    )

    private companion object {
        private val HEX_64: Regex = UploadSessionDefaults.SHA256_HEX_PATTERN
        private val MAX_ABSOLUTE_LEASE: Duration = UploadSessionDefaults.ABSOLUTE_LEASE
        val DEFAULT_FINALIZING_LEASE_TTL: Duration = Duration.ofMinutes(5)

        /** LF-010 / LF-013 / LN-009 / LN-011 § 8.4 (F.4 1/3): Intent-zu-Scope-Mapping. */
        private val SCOPE_ARTIFACT_UPLOAD: Set<String> = setOf("dmigrate:artifact:upload")
        private val SCOPE_READONLY_ACCEPTED: Set<String> =
            setOf("dmigrate:read", "dmigrate:artifact:upload")

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
