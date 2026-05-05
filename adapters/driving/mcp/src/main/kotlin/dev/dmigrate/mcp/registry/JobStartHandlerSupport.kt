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
 * Phase E §7.6 Helper-Funktionen, die alle drei Start-Tool-Handler
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
     * rekursiv. Fractional Numbers werden auf Long gerundet — Phase-E-
     * Start-Tools nehmen keine Fliesskommazahlen entgegen, sodass die
     * Praezisionswarnung nicht relevant ist.
     */
    fun toJsonValue(element: JsonElement?): JsonValue = when {
        element == null || element.isJsonNull -> JsonValue.Null
        element.isJsonPrimitive -> {
            val p = element.asJsonPrimitive
            when {
                p.isBoolean -> JsonValue.Bool(p.asBoolean)
                p.isNumber -> JsonValue.Num(p.asLong)
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
            ToolCallOutcome.Error(
                envelope = ToolErrorEnvelope(
                    code = ToolErrorCode.OPERATION_TIMEOUT,
                    message = "Start operation pending",
                    details = listOf(ToolErrorDetail("leaseExpiresAt", outcome.leaseExpiresAt.toString())),
                    requestId = requestId,
                ),
            )
        is JobStartHandlerOutcome.Failed ->
            ToolCallOutcome.Error(
                envelope = ToolErrorEnvelope(
                    code = ToolErrorCode.OPERATION_TIMEOUT,
                    message = "Start failed",
                    details = listOf(
                        ToolErrorDetail("reason", outcome.reason),
                        ToolErrorDetail("expiresAt", outcome.expiresAt.toString()),
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
}
