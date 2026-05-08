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
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Clock

/**
 * Phase E §3.1 / §7.6: `data_profile_start` — startet einen
 * Daten-Profiling-Job gegen eine tenant-scoped Connection-Ref.
 *
 * Symmetrisch zu [SchemaReverseStartHandler]; nur Tool-Name und
 * Operation unterscheiden sich. Die fachliche Profiling-Pipeline
 * (Profiler-Engine, Stats-Aggregation) bleibt AP E.7 vorbehalten.
 */
internal class DataProfileStartHandler(
    private val orchestrator: JobStartOrchestrator,
    private val clock: Clock,
    private val jobRetentionSeconds: Long = DEFAULT_JOB_RETENTION_SECONDS,
) : ToolHandler {

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = JsonArgs.requireObject(context.arguments)
        val connectionRef = args.requireString("connectionId")
        val idempotencyKey = args.optString("idempotencyKey")
        val approvalToken = args.optString("approvalToken")
        JobStartHandlerSupport.optStringArray(args, "includes")
        JobStartHandlerSupport.optStringArray(args, "excludes")

        val tenantId = context.principal.effectiveTenantId
        val now = clock.instant()
        val request = JobStartRequest(
            toolName = TOOL_NAME,
            tenantId = tenantId,
            callerId = context.principal.principalId,
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
            payload = JobStartHandlerSupport.toJsonValueObj(args),
            refs = listOf(
                RefField(
                    name = "connectionId",
                    value = connectionRef,
                    expectedKind = ResourceKind.CONNECTIONS,
                ),
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

    companion object {
        const val TOOL_NAME: String = "data_profile_start"
        const val OPERATION: String = "data_profile"
        const val DEFAULT_JOB_RETENTION_SECONDS: Long = 24 * 60 * 60
    }
}
