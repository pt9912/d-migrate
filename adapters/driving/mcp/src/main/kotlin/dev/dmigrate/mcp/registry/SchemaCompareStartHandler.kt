package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.server.application.job.JobStartOrchestrator
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.job.RefField
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Clock

/**
 * Phase E §3.1 / §7.6: `schema_compare_start` — startet einen Job, der
 * zwei tenant-scoped Schema-Refs vergleicht (`sourceUri`, `targetUri`).
 *
 * Plan §7.7 erlaubt sowohl `connectionRef`- als auch `schemaRef`-
 * Eingaben fuer dieses Tool; die unterscheidbare Materialisierung
 * (Reader-vs-Schema-Content) lebt im Runner (AP E.7). Hier akzeptiert
 * der Validator beide Kinds — wir erzwingen nur, dass die Refs ein
 * gueltiges tenant-scoped `dmigrate://`-Format haben (Connection ODER
 * Schema), nicht-`dmigrate`-URIs werden abgelehnt.
 */
internal class SchemaCompareStartHandler(
    private val orchestrator: JobStartOrchestrator,
    private val clock: Clock,
    private val jobRetentionSeconds: Long = DEFAULT_JOB_RETENTION_SECONDS,
) : ToolHandler {

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = JsonArgs.requireObject(context.arguments)
        val sourceUri = args.requireString("sourceUri")
        val targetUri = args.requireString("targetUri")
        val idempotencyKey = args.optString("idempotencyKey")
        val approvalToken = args.optString("approvalToken")

        val tenantId = context.principal.effectiveTenantId
        val now = clock.instant()
        val sourceKind = allowedCompareKind(sourceUri)
        val targetKind = allowedCompareKind(targetUri)
        val request = JobStartRequest(
            toolName = TOOL_NAME,
            tenantId = tenantId,
            callerId = context.principal.principalId,
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
            payload = JobStartHandlerSupport.toJsonValueObj(args),
            refs = listOf(
                RefField(name = "sourceUri", value = sourceUri, expectedKind = sourceKind),
                RefField(name = "targetUri", value = targetUri, expectedKind = targetKind),
            ),
            now = now,
            principalContext = context.principal,
            auditFields = context.auditFields,
            jobBuilder = { jobId, createdAt ->
                JobRecord(
                    managedJob = ManagedJob(
                        jobId = jobId,
                        operation = OPERATION,
                        status = JobStatus.QUEUED,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                        expiresAt = createdAt.plusSeconds(jobRetentionSeconds),
                        createdBy = context.principal.principalId.value,
                    ),
                    tenantId = tenantId,
                    ownerPrincipalId = context.principal.principalId,
                    visibility = JobVisibility.OWNER,
                    resourceUri = ServerResourceUri(
                        tenantId = tenantId,
                        kind = ResourceKind.JOBS,
                        id = jobId,
                    ),
                )
            },
        )

        val outcome = orchestrator.start(request)
        return JobStartHandlerSupport.toToolCallOutcome(outcome, tenantId, context.requestId)
    }

    private fun allowedCompareKind(value: String): ResourceKind =
        when (val parsed = ServerResourceUri.parse(value)) {
            is ResourceUriParseResult.Valid -> when (parsed.uri.kind) {
                ResourceKind.SCHEMAS,
                ResourceKind.CONNECTIONS,
                -> parsed.uri.kind
                else -> ResourceKind.SCHEMAS
            }
            is ResourceUriParseResult.Invalid -> ResourceKind.SCHEMAS
        }

    companion object {
        const val TOOL_NAME: String = "schema_compare_start"
        const val OPERATION: String = "schema_compare"
        const val DEFAULT_JOB_RETENTION_SECONDS: Long = 24 * 60 * 60
    }
}
