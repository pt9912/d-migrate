package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.schema.PhaseBToolSchemas
import dev.dmigrate.server.application.audit.SecretScrubber
import org.slf4j.LoggerFactory
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
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

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
                    text = gson.toJson(projectJob(record, context.requestId)),
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

    private fun projectJob(record: JobRecord, requestId: String): Map<String, Any?> {
        val managed = record.managedJob
        return buildMap {
            put("jobId", managed.jobId)
            put("operation", managed.operation)
            put("status", managed.status.name)
            put("terminal", managed.status.terminal)
            put("createdAt", managed.createdAt.toString())
            put("updatedAt", managed.updatedAt.toString())
            put("expiresAt", managed.expiresAt.toString())
            put("resourceUri", record.resourceUri.render())
            put("artifacts", projectArtifacts(record.tenantId, managed.artifacts))
            // Optional fields: emit only when set so the wire matches
            // the JSON-Schema `"type": "object"` declaration (a null
            // value would fail strict 2020-12 validation). Same
            // omit-when-null rule as `error.exitCode` below.
            managed.progress?.let { put("progress", projectProgress(it)) }
            managed.error?.let { put("error", projectError(it)) }
            put("executionMeta", mapOf("requestId" to requestId))
        }
    }

    /**
     * AP 6.23: project `managed.artifacts` onto the canonical
     * `dmigrate://tenants/{tenantId}/artifacts/{artifactId}` URI form
     * before serialisation. Naked artefact ids accumulated by older
     * job pipelines are backfilled (single-write happens upstream;
     * this tool is read-only). Already-URI values are validated and
     * passed through verbatim — no double-rewrite.
     */
    private fun projectArtifacts(tenantId: TenantId, artifacts: List<String>): List<String> =
        artifacts.map { entry ->
            if (entry.startsWith("dmigrate://")) {
                entry
            } else {
                ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, entry).render()
            }
        }

    // AP 6.17: progress.phase and error.message/code can carry job-
    // runtime strings that originated from user-provided schemas or
    // connection refs. Scrubbing keeps accidental token /
    // connection-URL leakage out of the wire response.
    // AP 6.23: numericValues is filtered through a curated allowlist
    // (PhaseBToolSchemas.JOB_PROGRESS_NUMERIC_KEYS); unknown internal
    // counter names are dropped so the wire output validates against
    // the closed schema. Dropped keys are logged at WARN so operators
    // notice when a worker emits a counter the allowlist doesn't yet
    // know about (review W2).
    private fun projectProgress(progress: JobProgress): Map<String, Any?> {
        val allowed = PhaseBToolSchemas.JOB_PROGRESS_NUMERIC_KEYS
        val dropped = progress.numericValues.keys.filterNot { it in allowed }
        if (dropped.isNotEmpty()) {
            LOG.warn(
                "JobStatusGet dropped non-allowlisted progress key(s): {}",
                dropped.joinToString(","),
            )
        }
        return mapOf(
            "phase" to SecretScrubber.scrub(progress.phase),
            "numericValues" to progress.numericValues.filterKeys { it in allowed },
        )
    }

    private fun projectError(error: JobError): Map<String, Any?> = buildMap {
        put("code", SecretScrubber.scrub(error.code))
        put("message", SecretScrubber.scrub(error.message))
        if (error.exitCode != null) put("exitCode", error.exitCode)
    }

    private companion object {
        @JvmStatic
        private val LOG = LoggerFactory.getLogger(JobStatusGetHandler::class.java)
    }
}
