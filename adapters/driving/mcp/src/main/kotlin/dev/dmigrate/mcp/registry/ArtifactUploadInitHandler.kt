package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.application.error.PolicyRequiredException
import dev.dmigrate.server.application.error.RateLimitedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.RateLimitedDetail
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.application.upload.UploadInitApprovalAttempt
import dev.dmigrate.server.application.upload.UploadInitOutcome
import dev.dmigrate.server.application.upload.UploadInitRequest
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail
import dev.dmigrate.server.core.error.ToolErrorEnvelope
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
import java.util.Locale
import java.util.UUID

/**
 * LF-012 / LN-027 / LN-028 / LN-038: `artifact_upload_init` for the read-only schema-staging
 * path per LF-012 / LN-027 / LN-028 / LN-038.
 *
 * LF-012 / LN-038 accepts only `uploadIntent=schema_staging_readonly`. Every
 * other intent surfaces as `POLICY_REQUIRED` so clients understand
 * the future policy gate (LF-012 / LN-027 / LN-028 / LN-038) is not yet open. The handler
 * does NOT consult an approval store — read-only schema staging is
 * policy-free per §4.4.
 *
 * Quota policy: reserves one slot in `ACTIVE_UPLOAD_SESSIONS` AND
 * `expectedSizeBytes` in `UPLOAD_BYTES`. The session reservation is
 * released on abort/expiry/finalisation (LF-012 / LN-027 / LN-028 / LN-038); the byte
 * reservation likewise. If either reservation is rate-limited, the
 * session is not created and the byte reservation is rolled back via
 * [QuotaService.release] before throwing.
 *
 * The TTL pair (`idleTimeoutAt`, `absoluteLeaseExpiresAt`) is
 * configurable so tests don't have to wall-clock; production picks
 * sensible defaults (5 min idle, 60 min absolute lease).
 */
internal class ArtifactUploadInitHandler(
    private val sessionStore: UploadSessionStore,
    private val quotaService: QuotaService,
    private val limits: McpLimitsConfig,
    private val options: Options,
    /**
     * LF-010 / LF-013 / LN-009 / LN-011: wenn gesetzt, faengt der Handler
     * `uploadIntent=job_input` und delegiert an den
     * [dev.dmigrate.server.application.upload.UploadInitOrchestrator].
     * Registry-Defaults wiren den Orchestrator; `null` bleibt nur fuer
     * isolierte Handler-Tests oder bewusstes Legacy-Wiring moeglich.
     */
    private val uploadInitOrchestrator: dev.dmigrate.server.application.upload.UploadInitOrchestrator? = null,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        // LF-010 / LF-013 / LN-009 / LN-011: policy-pflichtige Intents werden vor
        // dem LF-012 / LN-038 Read-only-Pfad abgefangen. Wenn ein
        // `UploadInitOrchestrator` gewired ist, geht `uploadIntent=
        // job_input` durch die LF-010 / LF-013 / LN-009 / LN-011-Pipeline.
        if (uploadInitOrchestrator != null) {
            policyInitOutcomeOrNull(context)?.let { return it }
        }

        // TODO(LF-012 / LN-027 / LN-028 / LN-038): idempotency key per spec/mcp-server.md
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

        val now = options.clock.instant()
        val absoluteExpiresAt = now.plus(options.absoluteLeaseDuration)
        val session = newSession(context, args, now, absoluteExpiresAt)
        // LF-010 / LF-013 / LN-009 / LN-011: durable AuditFields-Population.
        // Anforderungsakzeptanz "Audit enthaelt keine rohen Uploadbytes oder
        // Approval-Tokens" wird strukturell durch das AuditEvent-Schema
        // erfuellt; hier wird der `resourceRefs`-Slot mit der
        // tenant-scoped Session-URI gefuellt, sodass Audit-Konsumenten
        // den Upload-Context tracen koennen.
        context.auditFields.resourceRefs = listOf(session.resourceUri.render())
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
            "executionMeta" to mapOf("requestId" to context.requestId),
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
     * LF-010 / LF-013 / LN-009 / LN-011 — Policy-Init-Pfad fuer
     * `uploadIntent=job_input`. Retourniert `null` wenn der Intent
     * nicht zum Policy-Pfad gehoert (Legacy-Pfad uebernimmt) oder wenn
     * der `uploadIntent` ueberhaupt fehlt (Legacy-Pfad emittiert die
     * `VALIDATION_ERROR`).
     */
    private fun policyInitOutcomeOrNull(context: ToolCallContext): ToolCallOutcome? {
        val raw = JsonArgs.requireObject(context.arguments)
        val intent = raw.optString("uploadIntent") ?: return null
        if (intent != INTENT_JOB_INPUT) return null
        requireArtifactUploadScope(context.principal, intent)

        val approvalKey = raw.optString("approvalKey")
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("approvalKey", "is required for uploadIntent=job_input")),
            )
        val approvalToken = raw.optString("approvalToken")
        val sizeBytes = parsePolicySize(raw)
        val checksum = parsePolicyChecksum(raw)
        val mimeType = raw.optString("mimeType") ?: DEFAULT_POLICY_MIME_TYPE
        val artifactKind = parseArtifactKind(raw)
        val targetTable = raw.optString("targetTable")
        // LF-010 / LF-013 / LN-009 / LN-011: Bundle-Init-Vertrag (LF-010 / LF-013 / LN-009 / LN-011). bundleFormat ist
        // pflicht, sobald tables gesetzt ist; targetTable und tables sind
        // gegenseitig exklusiv.
        val bundleHints = parseBundleInitHints(raw, targetTable)
        // LF-010 / LF-013 / LN-009 / LN-011: `sizeBytes=0` ist nur fuer
        // nicht-Schema-`job_input` als Single-Empty-Segment gueltig
        // (Vertrag: "leeres finales Segment + Empty-SHA"). Schema-Artefakte
        // muessen Bytes haben — ein leeres Schema-Dokument ist kein
        // valider DDL-/Schema-JSON-Inhalt.
        if (sizeBytes == 0L && artifactKind == ArtifactKind.SCHEMA) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "sizeBytes",
                    "must be > 0 for artifactKind=SCHEMA",
                )),
            )
        }

        val tenantId = context.principal.effectiveTenantId
        val callerId = context.principal.principalId
        val request = UploadInitRequest(
            tenantId = tenantId,
            callerId = callerId,
            approvalKey = approvalKey,
            attempt = UploadInitApprovalAttempt(
                tenantId = tenantId,
                callerId = callerId,
                artifactKind = artifactKind,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                checksumSha256 = checksum,
                uploadIntent = intent,
                targetTable = targetTable,
                wireArtifactKind = parseWireArtifactKind(raw, artifactKind, bundleHints),
                bundleFormat = bundleHints?.bundleFormat,
                intendedTables = bundleHints?.intendedTables,
            ),
            segmentTotal = policySegmentCount(sizeBytes, limits.maxUploadSegmentBytes),
            now = options.clock.instant(),
            approvalToken = approvalToken,
        )
        // !! Safe: handle() guards on uploadInitOrchestrator != null
        // before invoking this helper. The redundant !! satisfies the
        // nullable field type without leaking the guard up here.
        val outcome = uploadInitOrchestrator!!.init(request)
        return mapPolicyInitOutcome(outcome, context.requestId)
    }

    private fun requireArtifactUploadScope(principal: PrincipalContext, intent: String) {
        if (principal.isAdmin || SCOPE_ARTIFACT_UPLOAD in principal.scopes) return
        throw ForbiddenPrincipalException(
            principal.principalId,
            reason = "missing scope for uploadIntent=$intent: $SCOPE_ARTIFACT_UPLOAD",
        )
    }

    private fun parsePolicySize(obj: JsonObject): Long {
        val canonical = obj.get("sizeBytes")?.takeUnless { it.isJsonNull }
        val alias = obj.get("expectedSizeBytes")?.takeUnless { it.isJsonNull }
        val element = canonical ?: alias
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("sizeBytes", "is required")),
            )
        val size = sizeAsLong(element, fieldName = if (canonical != null) "sizeBytes" else "expectedSizeBytes")
        if (size < 0) {
            throw ValidationErrorException(
                listOf(ValidationViolation("sizeBytes", "must be >= 0")),
            )
        }
        // LF-012 / LN-011 / LN-017 / LN-027: Legacy-Alias additiv, widersprechende Doppelwerte ->
        // VALIDATION_ERROR.
        if (canonical != null && alias != null) {
            val aliasSize = sizeAsLong(alias, fieldName = "expectedSizeBytes")
            if (aliasSize != size) {
                throw ValidationErrorException(
                    listOf(ValidationViolation(
                        "expectedSizeBytes",
                        "must equal sizeBytes when both are provided",
                    )),
                )
            }
        }
        if (size > limits.maxArtifactUploadBytes) {
            throw PayloadTooLargeException(actualBytes = size, maxBytes = limits.maxArtifactUploadBytes)
        }
        return size
    }

    private fun sizeAsLong(element: JsonElement, fieldName: String): Long {
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isNumber) {
            throw ValidationErrorException(
                listOf(ValidationViolation(fieldName, "must be a non-negative integer")),
            )
        }
        return primitive.asLong
    }

    private fun parsePolicyChecksum(obj: JsonObject): String {
        val checksum = obj.optString("checksumSha256")
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("checksumSha256", "is required")),
            )
        if (!CHECKSUM_PATTERN.matches(checksum)) {
            throw ValidationErrorException(
                listOf(ValidationViolation("checksumSha256", "must be 64 lowercase hex chars")),
            )
        }
        return checksum
    }

    private fun parseArtifactKind(obj: JsonObject): ArtifactKind {
        val raw = obj.optString("artifactKind") ?: return ArtifactKind.UPLOAD_INPUT
        return when (raw.lowercase(Locale.US)) {
            "seed-data", "generic" -> ArtifactKind.UPLOAD_INPUT
            "schema" -> ArtifactKind.SCHEMA
            "ddl", "transform-script", "rules" -> ArtifactKind.OTHER
            else -> runCatching { ArtifactKind.valueOf(raw.uppercase(Locale.US)) }.getOrElse {
                throw ValidationErrorException(
                    listOf(ValidationViolation(
                        "artifactKind",
                        "must be one of ${ArtifactKind.entries.map { it.name }.sorted()} plus " +
                            "[ddl, generic, rules, schema, seed-data, transform-script]",
                    )),
                )
            }
        }
    }

    private fun parseWireArtifactKind(
        obj: JsonObject,
        artifactKind: ArtifactKind,
        bundleHints: BundleInitHints? = null,
    ): String {
        // LF-010 / LF-013 / LN-009 / LN-011: Bundle-Uploads bekommen einen separaten
        // Wire-Marker, damit `data_import_start` Bundle- vs. Single-File-
        // Artefakte ohne metadata-Schnüffeln unterscheiden kann.
        if (bundleHints != null) return WIRE_KIND_SEED_DATA_BUNDLE
        val raw = obj.optString("artifactKind") ?: return "seed-data"
        val lower = raw.lowercase(Locale.US)
        return when (lower) {
            "seed-data", "generic", "schema", "ddl", "transform-script", "rules" -> lower
            "upload_input" -> "seed-data"
            "other" -> "generic"
            else -> artifactKind.name.lowercase(Locale.US)
        }
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 — Bundle-Init-Vertrag.
     *
     * LF-010 / LF-013 / LN-009 / LN-011 wortlaut:
     *
     * - `tables`: nicht-leere Liste von Tabellen.
     * - `bundleFormat`: Pflicht, wenn `tables` gesetzt ist.
     * - `table` und `tables` bleiben gegenseitig exklusiv.
     * - `bundleFormat` ist ein versionierter Wert; freie Strings werden
     *   nicht akzeptiert.
     */
    private fun parseBundleInitHints(raw: JsonObject, targetTable: String?): BundleInitHints? {
        val bundleFormat = raw.optString("bundleFormat")
        val tablesElement = raw.get("tables")?.takeUnless { it.isJsonNull }
        if (bundleFormat == null && tablesElement == null) return null
        validateBundlePresenceAndExclusivity(bundleFormat, tablesElement, targetTable)
        return BundleInitHints(
            bundleFormat = bundleFormat!!,
            intendedTables = parseBundleTablesArray(tablesElement!!),
        )
    }

    private fun validateBundlePresenceAndExclusivity(
        bundleFormat: String?,
        tablesElement: JsonElement?,
        targetTable: String?,
    ) {
        val violation = when {
            bundleFormat == null -> ValidationViolation(
                "bundleFormat",
                "is required when 'tables' is set",
            )
            bundleFormat !in dev.dmigrate.server.core.upload.bundle.BundleFormat.ALL ->
                ValidationViolation(
                    "bundleFormat",
                    "must be one of " +
                        dev.dmigrate.server.core.upload.bundle.BundleFormat.ALL.joinToString(","),
                )
            !targetTable.isNullOrBlank() -> ValidationViolation(
                "targetTable",
                "must not be set together with 'tables' (use either single-file or bundle)",
            )
            tablesElement == null || !tablesElement.isJsonArray -> ValidationViolation(
                "tables",
                "is required when 'bundleFormat' is set; must be an array of strings",
            )
            else -> null
        }
        if (violation != null) throw ValidationErrorException(listOf(violation))
    }

    private fun parseBundleTablesArray(tablesElement: JsonElement): List<String> {
        val arr = tablesElement.asJsonArray
        val violation = when {
            arr.isEmpty -> ValidationViolation("tables", "must not be empty")
            arr.any { entry ->
                val isString = entry.isJsonPrimitive && entry.asJsonPrimitive.isString
                !isString || entry.asString.isBlank()
            } -> ValidationViolation("tables", "items must be non-blank strings")
            else -> null
        }
        if (violation != null) throw ValidationErrorException(listOf(violation))
        val tables = arr.map { it.asString }
        if (tables.distinct().size != tables.size) {
            throw ValidationErrorException(
                listOf(ValidationViolation("tables", "must not contain duplicates")),
            )
        }
        return tables
    }

    private data class BundleInitHints(
        val bundleFormat: String,
        val intendedTables: List<String>,
    )

    /**
     * LF-010 / LF-013 / LN-009 / LN-011: `sizeBytes=0` ist fuer `job_input` ein gueltiger
     * Single-Empty-Segment-Upload. `segmentCountFor` rundet 0 / N auf 0,
     * der Orchestrator-Vertrag erwartet aber ein finales Segment.
     */
    private fun policySegmentCount(sizeBytes: Long, segmentSize: Int): Int =
        if (sizeBytes == 0L) 1 else segmentCountFor(sizeBytes, segmentSize)

    private fun mapPolicyInitOutcome(
        outcome: UploadInitOutcome,
        requestId: String,
    ): ToolCallOutcome = when (outcome) {
        is UploadInitOutcome.Initialized -> policyInitSuccess(
            sessionId = outcome.uploadSessionId,
            ttlSeconds = outcome.ttlSeconds,
            firstSegmentIndex = outcome.expectedFirstSegmentIndex,
            firstSegmentOffset = outcome.expectedFirstSegmentOffset,
            requestId = requestId,
        )
        is UploadInitOutcome.AlreadyInitialized -> policyInitSuccess(
            sessionId = outcome.uploadSessionId,
            ttlSeconds = outcome.ttlSeconds,
            firstSegmentIndex = FIRST_SEGMENT_INDEX,
            firstSegmentOffset = FIRST_SEGMENT_OFFSET,
            requestId = requestId,
        )
        is UploadInitOutcome.PolicyRequired -> ToolCallOutcome.Error(
            envelope = ToolErrorEnvelope(
                code = ToolErrorCode.POLICY_REQUIRED,
                message = "Policy approval required",
                details = listOf(
                    ToolErrorDetail("policyName", "upload_intent.$INTENT_JOB_INPUT"),
                    ToolErrorDetail("approvalRequestId", outcome.approvalRequestId),
                    ToolErrorDetail("correlationKind", outcome.correlationKind.name),
                    ToolErrorDetail("correlationKey", outcome.correlationKey),
                    ToolErrorDetail("requiredScopes", outcome.requiredScopes.sorted().joinToString(",")),
                    ToolErrorDetail("reasons", outcome.reasons.joinToString("|")),
                ),
                requestId = requestId,
            ),
        )
        is UploadInitOutcome.PolicyDenied -> throw PolicyDeniedException(
            policyName = "upload_intent.$INTENT_JOB_INPUT",
            reason = outcome.reasonCode,
        )
        is UploadInitOutcome.IdempotencyConflict ->
            throw IdempotencyConflictException(existingFingerprint = outcome.existingFingerprint)
        is UploadInitOutcome.InProgress -> ToolCallOutcome.Error(
            envelope = ToolErrorEnvelope(
                code = ToolErrorCode.OPERATION_TIMEOUT,
                message = "Upload init in progress (single-writer claim active)",
                details = listOf(
                    ToolErrorDetail("claimLeaseExpiresAt", outcome.claimLeaseExpiresAt.toString()),
                ),
                requestId = requestId,
            ),
        )
        is UploadInitOutcome.ValidationError -> throw ValidationErrorException(
            listOf(ValidationViolation("arguments", outcome.reason)),
        )
    }

    private fun policyInitSuccess(
        sessionId: String,
        ttlSeconds: Long,
        firstSegmentIndex: Int,
        firstSegmentOffset: Long,
        requestId: String,
    ): ToolCallOutcome.Success {
        val payload = mapOf(
            "uploadSessionId" to sessionId,
            "uploadSessionTtlSeconds" to ttlSeconds,
            "expectedFirstSegmentIndex" to firstSegmentIndex,
            "expectedFirstSegmentOffset" to firstSegmentOffset,
            "executionMeta" to mapOf("requestId" to requestId),
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
        val sessionId = options.sessionIdGenerator()
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
            idleTimeoutAt = now.plus(options.idleTimeout),
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
            // LF-012 / LN-038 is the read-only window: policy-pflichtige
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

    internal data class Options(
        val clock: Clock,
        val initialTtl: Duration = DEFAULT_INITIAL_TTL,
        val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
        val absoluteLeaseDuration: Duration = DEFAULT_ABSOLUTE_LEASE,
        val sessionIdGenerator: () -> String = ::generateSessionId,
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
        return minOf(options.initialTtl.seconds, remainingAbsolute)
    }

    companion object {
        const val TOOL_NAME: String = "artifact_upload_init"

        const val INTENT_SCHEMA_STAGING_READONLY: String = "schema_staging_readonly"

        /** LF-010 / LF-013 / LN-009 / LN-011: policy-pflichtiger Init-Intent. */
        const val INTENT_JOB_INPUT: String = "job_input"

        private const val SCOPE_ARTIFACT_UPLOAD: String = "dmigrate:artifact:upload"

        /**
         * LF-010 / LF-013 / LN-009 / LN-011 — Wire-Marker für Bundle-/Mehrtabellen-Uploads.
         * `data_import_start` akzeptiert dieses Wire-Kind als Bundle-
         * Quelle; Single-File-Uploads behalten `seed-data`.
         */
        const val WIRE_KIND_SEED_DATA_BUNDLE: String = "seed-data-bundle"

        /**
         * LF-010 / LF-013 / LN-009 / LN-011: Default-MIME-Type fuer den
         * Policy-Init-Pfad, wenn der Caller keinen `mimeType` angibt.
         * Schliesst die Luecke zwischen Approval-Fingerprint
         * (`mimeType` floss in den LF-010 / LF-013 / LN-009 / LN-011-Fingerprint ein) und
         * Wire-Optionalitaet — der Default ist eine deterministische,
         * dem Spec entsprechende Wahl, sodass abweichende
         * `mimeType`-Folgeretries via Idempotency-Conflict abgewiesen
         * werden.
         */
        const val DEFAULT_POLICY_MIME_TYPE: String = "application/octet-stream"

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
    }
}
