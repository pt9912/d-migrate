package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.TenantScopeDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.job.JobError
import dev.dmigrate.server.core.job.JobProgress
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.principal.TenantScopeChecker
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.JobStore
import java.util.UUID

/**
 * AP 6.12: `job_status_get` per `ImpPlan-0.9.6-C.md` §5.6 + §6.12.
 *
 * Read-only metadata fetch for a single job. Either `jobId` (in the
 * caller's tenant) or `resourceUri` (a fully-qualified
 * `dmigrate://tenants/{tenantId}/jobs/{jobId}`) is accepted; exactly
 * one must be supplied.
 *
 * Error policy follows §5.6 strictly:
 * - syntactically foreign tenant URIs surface `TENANT_SCOPE_DENIED`
 *   without a store lookup — the only path that confirms tenant
 *   identity (no concrete-job-existence is implied)
 * - everything else (unknown id, expired-but-not-yet-cleaned-up,
 *   wrong owner, wrong visibility) maps uniformly to
 *   `RESOURCE_NOT_FOUND`, so a client cannot distinguish "id you
 *   don't know about" from "id you can't see"
 */
internal class JobStatusGetHandler(
    private val jobStore: JobStore,
    private val requestIdProvider: () -> String = ::generateRequestId,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val obj = JsonArgs.requireObject(context.arguments)
        val jobId = obj.optString("jobId")
        val resourceUri = obj.optString("resourceUri")
        val (tenant, id) = resolveTarget(jobId, resourceUri, context.principal)

        val record = jobStore.findById(tenant, id)
            ?: throw notFound(context.principal, id)
        if (!record.isReadableBy(context.principal)) {
            // No-oracle: same RESOURCE_NOT_FOUND envelope as a
            // genuinely missing id, so the client can't infer
            // whether the id is unknown or just hidden.
            throw notFound(context.principal, id)
        }

        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(projectJob(record)),
                    mimeType = "application/json",
                ),
            ),
        )
    }

    /**
     * Resolves the target `(tenantId, jobId)` from the wire input.
     * Implements the §5.6 contract:
     * - exactly one of `jobId` / `resourceUri` is required; both or
     *   neither is `VALIDATION_ERROR`
     * - a `resourceUri` whose tenant is syntactically out of scope
     *   throws `TENANT_SCOPE_DENIED` *before* any store lookup, so
     *   no concrete-job existence is confirmed
     */
    private fun resolveTarget(
        jobId: String?,
        resourceUri: String?,
        principal: PrincipalContext,
    ): Pair<TenantId, String> {
        val haveId = !jobId.isNullOrBlank()
        val haveUri = !resourceUri.isNullOrBlank()
        if (haveId == haveUri) {
            throw ValidationErrorException(
                listOf(ValidationViolation("input", "exactly one of 'jobId' or 'resourceUri' is required")),
            )
        }
        return if (haveId) principal.effectiveTenantId to jobId!! else parseJobUri(resourceUri!!, principal)
    }

    private fun parseJobUri(raw: String, principal: PrincipalContext): Pair<TenantId, String> {
        val uri = when (val parsed = ServerResourceUri.parse(raw)) {
            is ResourceUriParseResult.Valid -> parsed.uri
            is ResourceUriParseResult.Invalid -> throw ValidationErrorException(
                listOf(ValidationViolation("resourceUri", "invalid URI: ${parsed.reason}")),
            )
        }
        if (uri.kind != ResourceKind.JOBS) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "resourceUri",
                        "expected jobs resource, got ${uri.kind.pathSegment}",
                    ),
                ),
            )
        }
        // §5.6: TENANT_SCOPE_DENIED is reserved for syntactically
        // out-of-scope tenant URIs. Inside-scope-but-unknown ids
        // fall through to RESOURCE_NOT_FOUND below.
        if (!TenantScopeChecker.isInScope(principal, uri.tenantId)) {
            throw TenantScopeDeniedException(uri.tenantId)
        }
        return uri.tenantId to uri.id
    }

    private fun notFound(principal: PrincipalContext, jobId: String): ResourceNotFoundException =
        ResourceNotFoundException(
            ServerResourceUri(principal.effectiveTenantId, ResourceKind.JOBS, jobId),
        )

    private fun projectJob(record: JobRecord): Map<String, Any?> {
        val managed = record.managedJob
        return mapOf(
            "jobId" to managed.jobId,
            "operation" to managed.operation,
            "status" to managed.status.name,
            "terminal" to managed.status.terminal,
            "createdAt" to managed.createdAt.toString(),
            "updatedAt" to managed.updatedAt.toString(),
            "expiresAt" to managed.expiresAt.toString(),
            "resourceUri" to record.resourceUri.render(),
            "artifacts" to managed.artifacts,
            "progress" to managed.progress?.let(::projectProgress),
            "error" to managed.error?.let(::projectError),
            "executionMeta" to mapOf("requestId" to requestIdProvider()),
        )
    }

    private fun projectProgress(progress: JobProgress): Map<String, Any?> = mapOf(
        "phase" to progress.phase,
        "numericValues" to progress.numericValues,
    )

    private fun projectError(error: JobError): Map<String, Any?> = buildMap {
        put("code", error.code)
        put("message", error.message)
        if (error.exitCode != null) put("exitCode", error.exitCode)
    }

    private companion object {
        private fun generateRequestId(): String =
            "req-${UUID.randomUUID().toString().take(8)}"
    }
}
