package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.job.JobStartHandlerOutcome
import dev.dmigrate.server.application.job.JobStartInputValidation
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail
import dev.dmigrate.server.core.error.ToolErrorEnvelope
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * LF-012 / LN-011 / LN-017 / LN-027 §7.6 Helper-Funktionen, die alle drei Start-Tool-Handler
 * (`schema_reverse_start`, `data_profile_start`, `schema_compare_start`)
 * teilen: JsonElement-zu-JsonValue-Konvertierung fuer den
 * PayloadFingerprint, Job-resourceUri-Render, Outcome-zu-ToolCallOutcome-
 * Mapping.
 */
internal object JobStartHandlerSupport {

    private val GSON = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Konvertiert die geparsten MCP-Argumente (Gson-Tree) in das
     * application-Layer-JsonValue, das [PayloadFingerprintService.fingerprint]
     * konsumiert. JSON-`null` wird zu [JsonValue.Null], Boolean/String/
     * Number-Primitives auf die jeweiligen Wrapper, Arrays/Objects
     * rekursiv. Fractional Numbers werden auf Long gerundet — LF-012 / LN-011 / LN-017 / LN-027-
     * Start-Tools nehmen keine Fliesskommazahlen entgegen, sodass die
     * Praezisionswarnung nicht relevant ist.
     */
    fun toJsonValue(element: JsonElement?): JsonValue = when {
        element == null || element.isJsonNull -> JsonValue.Null
        element.isJsonPrimitive -> {
            val p = element.asJsonPrimitive
            when {
                p.isBoolean -> JsonValue.Bool(p.asBoolean)
                p.isNumber -> {
                    // Review-Fix #7 (Fingerprint-Kollisions-Schutz):
                    // JsonValue.Num ist Long-only. Eine fraktionale Zahl
                    // (z.B. 1.5 oder 1.7) wuerde durch p.asLong auf 1
                    // truncatet — beide Inputs ergeben den gleichen
                    // Fingerprint, was Idempotenz-Replays verfaelscht
                    // (verschiedene Requests werden als gleicher
                    // Request behandelt). Fractional Numbers werden
                    // daher als String kanonisiert, damit jede
                    // Numeric-Repraesentation einen eigenen Fingerprint
                    // bekommt.
                    val n = p.asNumber
                    val asLong = n.toLong()
                    if (n.toDouble() == asLong.toDouble()) JsonValue.Num(asLong)
                    else JsonValue.Str(n.toString())
                }
                p.isString -> JsonValue.Str(p.asString)
                else -> JsonValue.Str(p.toString())
            }
        }
        element.isJsonArray -> JsonValue.Arr(element.asJsonArray.map { toJsonValue(it) })
        element.isJsonObject -> JsonValue.Obj(
            linkedMapOf<String, JsonValue>().apply {
                element.asJsonObject.entrySet().forEach { (k, v) -> put(k, toJsonValue(v)) }
            },
        )
        else -> JsonValue.Null
    }

    /** Convenience wrapper: erwartet ein Object und liefert `JsonValue.Obj`. */
    fun toJsonValueObj(obj: JsonObject): JsonValue.Obj =
        toJsonValue(obj) as JsonValue.Obj

    /**
     * Liest ein optionales String-Array aus dem Argument-Object. Akzeptiert
     * `null` (omitted), und einen Array-of-strings; alles andere wird
     * als [ValidationErrorException] gemeldet.
     */
    fun optStringArray(obj: JsonObject, field: String): List<String>? {
        val raw = obj.get(field)?.takeUnless { it.isJsonNull } ?: return null
        if (raw !is JsonArray) {
            throw ValidationErrorException(
                listOf(ValidationViolation(field, "must be an array of strings")),
            )
        }
        return raw.map {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
                throw ValidationErrorException(
                    listOf(ValidationViolation(field, "items must be strings")),
                )
            }
            it.asString
        }
    }

    /** `dmigrate://tenants/{tenant}/jobs/{jobId}` */
    fun jobResourceUri(tenant: TenantId, jobId: String): String =
        ServerResourceUri(tenant, ResourceKind.JOBS, jobId).render()

    /**
     * Mapped die orchestrator-Antwort auf die MCP-`tools/call`-Outcome.
     *
     * - Started/AlreadyStarted -> Success-Envelope mit `{jobId,
     *   resourceUri, executionMeta}` gemaess der E.6 (1/4)
     *   Schema-Migration.
     * - PolicyRequired -> Error-Envelope `POLICY_REQUIRED` mit
     *   `approvalRequestId`, `correlationKey`, `requiredScopes`, `reasons`.
     * - PolicyDenied -> [PolicyDeniedException] (DefaultErrorMapper macht
     *   das Envelope).
     * - IdempotencyConflict -> [IdempotencyConflictException].
     * - ValidationError -> [ValidationErrorException] mit feldspezifischer
     *   Violation.
     * - Pending/Failed -> Error-Envelope `OPERATION_TIMEOUT` (defensiv;
     *   AP E.9 verfeinert).
     */
    fun toToolCallOutcome(
        outcome: JobStartHandlerOutcome,
        tenantId: TenantId,
        requestId: String,
    ): ToolCallOutcome = when (outcome) {
        is JobStartHandlerOutcome.Started ->
            successResponse(outcome.jobId, tenantId, requestId)
        is JobStartHandlerOutcome.AlreadyStarted ->
            successResponse(outcome.jobId, tenantId, requestId)
        is JobStartHandlerOutcome.PolicyRequired ->
            ToolCallOutcome.Error(
                envelope = ToolErrorEnvelope(
                    code = ToolErrorCode.POLICY_REQUIRED,
                    message = "Policy approval required",
                    details = listOf(
                        ToolErrorDetail("approvalRequestId", outcome.approvalRequestId),
                        ToolErrorDetail("correlationKind", outcome.correlationKind.name),
                        ToolErrorDetail("correlationKey", outcome.correlationKey),
                        ToolErrorDetail("payloadFingerprint", outcome.payloadFingerprint),
                        ToolErrorDetail("requiredScopes", outcome.requiredScopes.sorted().joinToString(",")),
                        ToolErrorDetail("reasons", outcome.reasons.joinToString("|")),
                    ),
                    requestId = requestId,
                ),
            )
        is JobStartHandlerOutcome.PolicyDenied ->
            throw PolicyDeniedException(policyName = "policy:start", reason = outcome.reason)
        is JobStartHandlerOutcome.IdempotencyConflict ->
            throw IdempotencyConflictException(existingFingerprint = outcome.existingFingerprint)
        is JobStartHandlerOutcome.ValidationError ->
            throw ValidationErrorException(toViolations(outcome.invalid))
        is JobStartHandlerOutcome.Pending ->
            // LF-012 / LN-011 / LN-017 / LN-027: aktive PENDING ohne gespeichertes Outcome ->
            // OPERATION_TIMEOUT (retrybar). Das passt fuer den
            // Pending-Branch.
            ToolCallOutcome.Error(
                envelope = ToolErrorEnvelope(
                    code = ToolErrorCode.OPERATION_TIMEOUT,
                    message = "Start operation pending",
                    details = listOf(ToolErrorDetail("leaseExpiresAt", outcome.leaseExpiresAt.toString())),
                    requestId = requestId,
                ),
            )
        is JobStartHandlerOutcome.Failed -> {
            // Review-Fix #6 + Re-Review B1: Failed ist ein gespeicherter
            // Final-Failure-Replay (LF-012 / LN-011 / LN-017 / LN-027 line 568-569), KEIN
            // Timeout. Der urspruengliche Wire-Code wird aus dem reason-
            // Praefix abgeleitet, damit der Caller dieselbe
            // Klassifikation bekommt wie beim ersten Failed-Outcome.
            //
            // Akzeptiert mehrere Separator-Konventionen, weil der
            // Bestands-Code (z.B. IdempotencyStoreContractTests,
            // verschiedene markFailed-Caller) sowohl `policy:`,
            // `policy-`, `validation:`, `validation-` als auch
            // `validation_` verwendet. Re-Review B1 hat den
            // Mapping-Schmalspur-Fall aufgedeckt, bei dem
            // "validation-error" faelschlich auf INTERNAL_AGENT_ERROR
            // gemappt wurde.
            val code = when (reasonClassOf(outcome.reason)) {
                ReasonClass.POLICY -> ToolErrorCode.POLICY_DENIED
                ReasonClass.VALIDATION -> ToolErrorCode.VALIDATION_ERROR
                ReasonClass.OTHER -> ToolErrorCode.INTERNAL_AGENT_ERROR
            }
            ToolCallOutcome.Error(
                envelope = ToolErrorEnvelope(
                    code = code,
                    message = "Start failed (deterministic replay)",
                    details = listOf(
                        ToolErrorDetail("reason", outcome.reason),
                        ToolErrorDetail("expiresAt", outcome.expiresAt.toString()),
                    ),
                    requestId = requestId,
                ),
            )
        }
        is JobStartHandlerOutcome.RateLimited ->
            ToolCallOutcome.Error(
                envelope = ToolErrorEnvelope(
                    code = ToolErrorCode.RATE_LIMITED,
                    message = "Rate limit exceeded",
                    // LF-012 / LN-011 / LN-017 / LN-027: `reason` ist IMMER im Wire
                    // sichtbar — `ACTIVE_JOBS_QUOTA` fuer Tenant-Quota,
                    // `EXECUTOR_SATURATED` fuer Pool-Saturation.
                    details = listOf(
                        ToolErrorDetail("retryAfter", outcome.retryAfter.seconds.toString()),
                        ToolErrorDetail("current", outcome.current.toString()),
                        ToolErrorDetail("limit", outcome.limit.toString()),
                        ToolErrorDetail("reason", outcome.reason),
                    ),
                    requestId = requestId,
                ),
            )
    }


    private fun successResponse(jobId: String, tenant: TenantId, requestId: String): ToolCallOutcome.Success {
        val payload = mapOf(
            "jobId" to jobId,
            "resourceUri" to jobResourceUri(tenant, jobId),
            "executionMeta" to mapOf("requestId" to requestId),
        )
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = GSON.toJson(payload),
                    mimeType = "application/json",
                ),
            ),
        )
    }

    private fun toViolations(invalid: JobStartInputValidation.Invalid): List<ValidationViolation> =
        when (invalid) {
            is JobStartInputValidation.Invalid.IdempotencyKeyMissing ->
                listOf(ValidationViolation("idempotencyKey", "is required"))
            is JobStartInputValidation.Invalid.FreeJdbcUrl ->
                listOf(ValidationViolation(invalid.field, "free JDBC URLs are rejected — use a tenant-scoped resource URI"))
            is JobStartInputValidation.Invalid.InvalidRefSyntax ->
                listOf(ValidationViolation(invalid.field, "invalid resource URI: ${invalid.reason}"))
            is JobStartInputValidation.Invalid.WrongRefKind ->
                listOf(ValidationViolation(invalid.field, "expected ${invalid.expected.pathSegment}, got ${invalid.actual.pathSegment}"))
            is JobStartInputValidation.Invalid.TenantPrefixMismatch ->
                listOf(ValidationViolation(invalid.field, "tenant prefix mismatch: caller is ${invalid.expected.value}"))
        }

    /**
     * Re-Review B1: klassifiziere einen gespeicherten Failed-Reason in
     * eine ToolErrorCode-Familie. Akzeptiert sowohl `:`-, `-`- als auch
     * `_`-Separatoren, weil der Bestands-Code beide Konventionen
     * verwendet. Match auf den ersten Token-Block VOR dem Separator.
     */
    private fun reasonClassOf(reason: String): ReasonClass {
        val token = reason.substringBefore(':').substringBefore('-').substringBefore('_')
        return when (token) {
            "policy" -> ReasonClass.POLICY
            "validation" -> ReasonClass.VALIDATION
            else -> ReasonClass.OTHER
        }
    }

    private enum class ReasonClass { POLICY, VALIDATION, OTHER }
}
