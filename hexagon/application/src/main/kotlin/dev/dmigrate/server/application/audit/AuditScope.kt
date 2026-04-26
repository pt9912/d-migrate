package dev.dmigrate.server.application.audit

import dev.dmigrate.server.application.error.ApplicationException
import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.ports.AuditSink
import java.time.Clock
import java.time.Instant

/**
 * Around/finally wrapper that records an [AuditEvent] for every tool
 * invocation, including early failures (`AUTH_REQUIRED`,
 * `VALIDATION_ERROR`, etc.). The block receives a mutable
 * [AuditFields] so it can populate late-bound fields once they are
 * known.
 *
 * Outcome mapping (`§6.8`):
 *   - block returns -> `SUCCESS`, `errorCode = null`
 *   - [ApplicationException] -> `FAILURE`, `errorCode = ex.code`
 *   - any other [Throwable] -> `FAILURE`,
 *     `errorCode = INTERNAL_AGENT_ERROR`
 *
 * In every failure branch the original exception is rethrown after
 * `sink.emit` so callers see the same throwable they would without the
 * audit wrapper.
 */
class AuditScope(
    private val sink: AuditSink,
    private val clock: Clock,
) {

    fun <T> around(context: AuditContext, block: (AuditFields) -> T): T {
        val startedAt: Instant = Instant.now(clock)
        val fields = AuditFields()
        var errorCode: ToolErrorCode? = null
        try {
            return block(fields)
        } catch (ex: ApplicationException) {
            errorCode = ex.code
            throw ex
        } catch (ex: Throwable) {
            errorCode = ToolErrorCode.INTERNAL_AGENT_ERROR
            throw ex
        } finally {
            sink.emit(buildEvent(context, fields, errorCode, startedAt))
        }
    }

    private fun buildEvent(
        context: AuditContext,
        fields: AuditFields,
        errorCode: ToolErrorCode?,
        startedAt: Instant,
    ): AuditEvent {
        val durationMs = Instant.now(clock).toEpochMilli() - startedAt.toEpochMilli()
        val outcome = if (errorCode == null) AuditOutcome.SUCCESS else AuditOutcome.FAILURE
        return AuditEvent(
            requestId = context.requestId,
            outcome = outcome,
            startedAt = startedAt,
            toolName = context.toolName,
            tenantId = context.tenantId,
            principalId = context.principalId,
            errorCode = errorCode,
            payloadFingerprint = fields.payloadFingerprint,
            resourceRefs = fields.resourceRefs.map(SecretScrubber::scrub),
            durationMs = durationMs,
        )
    }
}
