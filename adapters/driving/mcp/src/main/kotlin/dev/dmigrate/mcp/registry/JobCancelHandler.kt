package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.application.job.JobCancelOutcome
import dev.dmigrate.server.application.job.JobCancelService
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail
import dev.dmigrate.server.core.error.ToolErrorEnvelope
import dev.dmigrate.server.core.job.JobRecord
import java.time.Clock

/**
 * Phase E §7.8 `job_cancel`-Tool-Handler.
 *
 * Plan-konforme Pflichten:
 *
 * - Input-Validierung: genau eines von `jobId` oder `resourceUri` ist
 *   Pflicht (Plan §5.6 line 641); sonst `VALIDATION_ERROR`. `reason`
 *   ist optional.
 * - Service-Aufruf via [JobCancelService]; alle Tenant-/Principal-/
 *   Status-Regeln liegen dort (E.8 (1/3)).
 * - Outcome-Mapping auf das `job_cancel`-Output-Schema (E.6 (1/4)):
 *   `{jobId, operation, status, terminal, resourceUri, executionMeta}`.
 *   `executionMeta` projiziert die Cancel-Felder gemaess
 *   `executionMetaJobField` aus `McpToolSchemas` einheitlich
 *   mit `job_status_get`.
 * - Fehlerpfade: NotFound -> `RESOURCE_NOT_FOUND` (no-oracle, ohne
 *   resourceUri-Echo); ForbiddenPrincipal -> `FORBIDDEN_PRINCIPAL`;
 *   TenantScopeDenied -> `TENANT_SCOPE_DENIED` mit Ziel-Tenant im
 *   Detail.
 */
internal class JobCancelHandler(
    private val service: JobCancelService,
    private val clock: Clock,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = JsonArgs.requireObject(context.arguments)
        val jobId = args.optString("jobId")
        val resourceUri = args.optString("resourceUri")
        val reason = args.optString("reason")

        val jobIdOrUri = when {
            jobId != null && resourceUri != null -> throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "arguments",
                        "exactly one of jobId or resourceUri must be provided",
                    ),
                ),
            )
            jobId != null -> jobId
            resourceUri != null -> resourceUri
            else -> throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "arguments",
                        "one of jobId or resourceUri is required",
                    ),
                ),
            )
        }

        val outcome = service.cancel(
            jobIdOrUri = jobIdOrUri,
            principal = context.principal,
            reason = reason,
            now = clock.instant(),
        )
        return mapOutcome(outcome, context.requestId)
    }

    private fun mapOutcome(outcome: JobCancelOutcome, requestId: String): ToolCallOutcome = when (outcome) {
        is JobCancelOutcome.Cancelled ->
            success(outcome.record, requestId, ackPending = false, retryAfter = null)
        is JobCancelOutcome.AlreadyTerminal ->
            success(outcome.record, requestId, ackPending = false, retryAfter = null)
        is JobCancelOutcome.AckPending ->
            success(outcome.record, requestId, ackPending = true, retryAfter = outcome.retryAfter.seconds)
        is JobCancelOutcome.NotFound ->
            ToolCallOutcome.Error(
                envelope = ToolErrorEnvelope(
                    code = ToolErrorCode.RESOURCE_NOT_FOUND,
                    message = "Resource not found",
                    details = emptyList(),
                    requestId = requestId,
                ),
            )
        is JobCancelOutcome.ForbiddenPrincipal ->
            ToolCallOutcome.Error(
                envelope = ToolErrorEnvelope(
                    code = ToolErrorCode.FORBIDDEN_PRINCIPAL,
                    message = "Forbidden",
                    details = emptyList(),
                    requestId = requestId,
                ),
            )
        is JobCancelOutcome.TenantScopeDenied ->
            ToolCallOutcome.Error(
                envelope = ToolErrorEnvelope(
                    code = ToolErrorCode.TENANT_SCOPE_DENIED,
                    message = "Tenant scope denied",
                    details = listOf(ToolErrorDetail("targetTenant", outcome.targetTenant.value)),
                    requestId = requestId,
                ),
            )
    }

    private fun success(
        record: JobRecord,
        requestId: String,
        ackPending: Boolean,
        retryAfter: Long?,
    ): ToolCallOutcome.Success {
        val mj = record.managedJob
        val cr = mj.cancelRequest
        val executionMeta = linkedMapOf<String, Any?>()
        executionMeta["requestId"] = requestId
        if (cr.requested) executionMeta["cancelRequested"] = true
        if (ackPending) {
            executionMeta["cancelAckPending"] = true
            if (retryAfter != null) executionMeta["retryAfter"] = retryAfter
        }
        cr.requestedAt?.let { executionMeta["cancelRequestedAt"] = it.toString() }
        cr.requestedBy?.let { executionMeta["cancelRequestedBy"] = it }
        cr.requestedReason?.let { executionMeta["cancelRequestedReason"] = it }
        cr.signalSource?.let { executionMeta["cancelSignalSource"] = it }

        val payload = linkedMapOf<String, Any?>(
            "jobId" to mj.jobId,
            "operation" to mj.operation,
            "status" to mj.status.name,
            "terminal" to mj.status.terminal,
            "resourceUri" to record.resourceUri.render(),
            "executionMeta" to executionMeta,
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

    companion object {
        const val TOOL_NAME: String = "job_cancel"
    }
}
