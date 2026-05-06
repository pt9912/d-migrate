package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
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
 * Phase F § 6.2 + § 8.8 (F.8 2/4) — `data_transfer_start`-Handler.
 *
 * Pre-Idempotency-Validation (Plan § 8.8 wortlaeufig "vor
 * Idempotency ohne Store-Write"):
 *
 * - `idempotencyKey` Pflicht (vom Phase-E
 *   [dev.dmigrate.server.application.job.JobStartInputValidator]
 *   geprueft).
 * - `sourceConnectionRef` + `targetConnectionRef` als RefField mit
 *   `ResourceKind.CONNECTIONS` — der Validator weist freie
 *   JDBC-URLs, ungueltige URI-Syntax und Tenant-Mismatch ab.
 * - `chunkSize` muss in [1, 10000] liegen.
 * - `sinceColumn` und `since` paarweise: beide fehlen ODER beide
 *   gesetzt (Plan § 8.8 wortlaeufig).
 * - `sinceColumn` muss CLI-Identifier-Format folgen (alphanumerisch
 *   plus `.` und `_`; konservativ).
 * - `filter` darf nicht blank sein (Plan: "blanke oder ungueltige
 *   Filter liefern VALIDATION_ERROR").
 *
 * Phase-E Job-Pipeline:
 *
 * - [JobStartOrchestrator] uebernimmt Idempotency-Reservierung,
 *   Policy-Decision, Quota-Reservierung und durable Job-Anlage.
 *
 * **Carve-outs F.8 (2/4)**: ConnectionRef-Resolution gegen den
 * [dev.dmigrate.server.ports.ConnectionReferenceStore] und der
 * MCP-Transfer-Fingerprint folgen in F.8 (3/4). Filter-DSL-Parsing
 * (kanonische Form fuer den Fingerprint) ist Runner-side concern;
 * Phase F MVP rejects nur blanke/ungueltige Top-Level-Strings.
 * Production-Wiring + E2E-Test in F.8 (4/4).
 */
internal class DataTransferStartHandler(
    private val orchestrator: JobStartOrchestrator,
    private val clock: Clock,
    private val jobRetentionSeconds: Long = DEFAULT_JOB_RETENTION_SECONDS,
) : ToolHandler {

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = JsonArgs.requireObject(context.arguments)
        val idempotencyKey = args.optString("idempotencyKey")
        val approvalToken = args.optString("approvalToken")
        val sourceConnectionRef = args.requireString("sourceConnectionRef")
        val targetConnectionRef = args.requireString("targetConnectionRef")
        validateChunkSize(args)
        validateFilter(args)
        validateSincePair(args)

        val tenantId = context.principal.effectiveTenantId
        val now = clock.instant()

        val refs = listOf(
            RefField(
                name = "sourceConnectionRef",
                value = sourceConnectionRef,
                expectedKind = ResourceKind.CONNECTIONS,
            ),
            RefField(
                name = "targetConnectionRef",
                value = targetConnectionRef,
                expectedKind = ResourceKind.CONNECTIONS,
            ),
        )

        val request = JobStartRequest(
            toolName = TOOL_NAME,
            tenantId = tenantId,
            callerId = context.principal.principalId,
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
            payload = JobStartHandlerSupport.toJsonValueObj(args),
            refs = refs,
            now = now,
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

    /**
     * Plan § 6.2: `chunkSize` ist eine "positive Ganzzahl bis 10000".
     * Schema sichert `minimum=1`; die Obergrenze liegt im Handler
     * als Defense in Depth (analog zu F.7 (2/5)).
     */
    private fun validateChunkSize(args: JsonObject) {
        val element = args.get("chunkSize")?.takeUnless { it.isJsonNull } ?: return
        val primitive = element as? com.google.gson.JsonPrimitive
        if (primitive == null || !primitive.isNumber) {
            throw ValidationErrorException(
                listOf(ValidationViolation("chunkSize", "must be an integer")),
            )
        }
        val value = primitive.asLong
        if (value < 1L || value > MAX_CHUNK_SIZE) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "chunkSize",
                    "must be in [1, $MAX_CHUNK_SIZE]",
                )),
            )
        }
    }

    /**
     * Plan § 8.8: "blanker oder ungueltiger filter -> VALIDATION_ERROR".
     * Phase-F-MVP prueft nur "non-blank"; Filter-DSL-Parsing
     * (kanonische Form fuer den Fingerprint) ist Runner-side
     * concern und out-of-scope F.8.
     */
    private fun validateFilter(args: JsonObject) {
        val raw = args.get("filter")?.takeUnless { it.isJsonNull } ?: return
        val primitive = raw as? com.google.gson.JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw ValidationErrorException(
                listOf(ValidationViolation("filter", "must be a string")),
            )
        }
        if (primitive.asString.isBlank()) {
            throw ValidationErrorException(
                listOf(ValidationViolation("filter", "must not be blank")),
            )
        }
    }

    /**
     * Plan § 8.8: "sinceColumn und since paarweise validieren: beide
     * fehlen oder beide gesetzt." `sinceColumn` muss CLI-Identifier-
     * Format folgen — konservativ alphanumerisch plus `.` und `_`,
     * sodass `schema.column` und `column_name` zulaessig sind, aber
     * SQL-Injection (`'; DROP`-Patterns) ausgeschlossen ist.
     */
    private fun validateSincePair(args: JsonObject) {
        val sinceColumn = args.optString("sinceColumn")
        val since = args.optString("since")
        if (sinceColumn.isNullOrBlank() && since.isNullOrBlank()) return
        if (sinceColumn.isNullOrBlank()) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "sinceColumn",
                    "is required when 'since' is set",
                )),
            )
        }
        if (since.isNullOrBlank()) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "since",
                    "is required when 'sinceColumn' is set",
                )),
            )
        }
        if (!IDENTIFIER_PATTERN.matches(sinceColumn)) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "sinceColumn",
                    "must be a valid SQL identifier (alphanumeric, '.', '_')",
                )),
            )
        }
    }

    companion object {
        const val TOOL_NAME: String = "data_transfer_start"
        const val OPERATION: String = "data_transfer"
        const val DEFAULT_JOB_RETENTION_SECONDS: Long = 24 * 60 * 60
        // Plan § 6.2: chunkSize "positive Ganzzahl bis 10000".
        const val MAX_CHUNK_SIZE: Long = 10_000
        // CLI-Identifier-konsistente Validierung — alphanumerisch
        // plus `.` (qualified columns) und `_` (snake_case).
        private val IDENTIFIER_PATTERN: Regex = Regex("""^[A-Za-z_][A-Za-z0-9_.]*$""")
    }
}
