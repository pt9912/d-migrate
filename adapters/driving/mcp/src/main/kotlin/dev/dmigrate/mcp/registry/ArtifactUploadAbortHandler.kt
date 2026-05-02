package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.UploadSessionAbortedException
import dev.dmigrate.server.application.error.UploadSessionExpiredException
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import java.time.Clock
import java.util.UUID

/**
 * AP 6.10: `artifact_upload_abort` per `ImpPlan-0.9.6-C.md` §6.10
 * and `spec/ki-mcp.md` §5.3 line 620-623.
 *
 * Phase C only allows the **session owner** to abort their own
 * active staging session — administrative or cross-principal aborts
 * are policy-pflichtig and out of scope until later phases.
 *
 * Lifecycle:
 * - lookup is tenant-scoped via [UploadSessionStore.findById]; an
 *   unknown id (across tenant or within tenant) surfaces uniformly
 *   as `RESOURCE_NOT_FOUND`, preserving the no-oracle pattern.
 * - same-tenant, wrong-principal → `FORBIDDEN_PRINCIPAL`.
 * - already-terminal state surfaces the typed lifecycle exception
 *   (`UPLOAD_SESSION_ABORTED` / `UPLOAD_SESSION_EXPIRED` /
 *   `IDEMPOTENCY_CONFLICT` for COMPLETED).
 *
 * On a clean abort the handler:
 * 1. transitions the session to `ABORTED` via the store
 * 2. deletes every staged segment via [UploadSegmentStore.deleteAllForSession]
 * 3. releases the AP-6.7 quota reservations (`UPLOAD_BYTES` for
 *    `session.sizeBytes` and `ACTIVE_UPLOAD_SESSIONS` for 1) so
 *    a tenant who aborts mid-upload doesn't keep the slot
 */
internal class ArtifactUploadAbortHandler(
    private val sessionStore: UploadSessionStore,
    private val segmentStore: UploadSegmentStore,
    private val quotaService: QuotaService,
    private val clock: Clock,
    private val requestIdProvider: () -> String = ::generateRequestId,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = JsonArgs.requireObject(context.arguments)
        val uploadSessionId = args.requireString("uploadSessionId")
        val tenant = context.principal.effectiveTenantId
        val session = sessionStore.findById(tenant, uploadSessionId)
            ?: throw ResourceNotFoundException(
                ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, uploadSessionId),
            )
        if (session.ownerPrincipalId != context.principal.principalId) {
            throw ForbiddenPrincipalException(
                principalId = context.principal.principalId,
                reason = "session belongs to a different principal",
            )
        }
        rejectTerminal(session)

        val now = clock.instant()
        val aborted = sessionStore.transitionOrThrow(session, UploadSessionState.ABORTED, now)
        // The session is already terminal — quota release is correct
        // even if segment cleanup fails. Wrap in try/finally so a
        // backing-store IO failure on `deleteAllForSession` doesn't
        // leave the tenant locked out of new uploads.
        val segmentsDeleted = try {
            segmentStore.deleteAllForSession(session.uploadSessionId)
        } finally {
            releaseQuotas(session, context.principal)
        }

        val payload = mapOf(
            "uploadSessionId" to aborted.uploadSessionId,
            "uploadSessionState" to aborted.state.name,
            "segmentsDeleted" to segmentsDeleted,
            "executionMeta" to mapOf("requestId" to requestIdProvider()),
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

    private fun rejectTerminal(session: UploadSession) {
        when (session.state) {
            UploadSessionState.ACTIVE -> Unit
            UploadSessionState.ABORTED -> throw UploadSessionAbortedException(session.uploadSessionId)
            UploadSessionState.EXPIRED -> throw UploadSessionExpiredException(session.uploadSessionId)
            UploadSessionState.COMPLETED -> throw IdempotencyConflictException(
                existingFingerprint = UploadFingerprint.sessionCompleted(session.uploadSessionId),
            )
        }
    }

    private fun releaseQuotas(session: UploadSession, principal: PrincipalContext) {
        // §6.7 reserved one slot in ACTIVE_UPLOAD_SESSIONS and
        // session.sizeBytes in UPLOAD_BYTES. Both are released here
        // so the tenant doesn't keep paying quota for an aborted
        // session.
        quotaService.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.ACTIVE_UPLOAD_SESSIONS,
                    principal.principalId,
                ),
                amount = 1,
            ),
        )
        quotaService.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.UPLOAD_BYTES,
                    principal.principalId,
                ),
                amount = session.sizeBytes,
            ),
        )
    }

    private companion object {
        private fun generateRequestId(): String =
            "req-${UUID.randomUUID().toString().take(8)}"
    }
}
