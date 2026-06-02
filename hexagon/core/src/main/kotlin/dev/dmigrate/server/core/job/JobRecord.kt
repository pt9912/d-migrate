package dev.dmigrate.server.core.job

import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri

enum class JobVisibility { OWNER, TENANT, ADMIN }

data class JobRecord(
    val managedJob: ManagedJob,
    val tenantId: TenantId,
    val ownerPrincipalId: PrincipalId,
    val visibility: JobVisibility,
    val resourceUri: ServerResourceUri,
    val adminScope: String? = null,
    /**
     * LF-012 / LN-011 / LN-017 / LN-027     * JobStartOrchestrator vor der Job-Commit eine Quota-Reservierung
     * registriert hat, traegt dieses Feld die zugehoerige
     * `ownerId` (typischerweise aus dem IdempotencyScope abgeleitet),
     * sodass JobDispatcher und JobCancelService den Slot beim
     * Terminal-/Cancel-Pfad ueber `OwnerAwareQuotaService.releaseForOwner`
     * freigeben koennen.
     *
     * `null` bedeutet "kein Owner-Tracking" (z.B. fuer Bestands-Tests
     * oder Pfade, die nicht durch den server-state orchestrator laufen).
     */
    val quotaReservationOwnerId: String? = null,
) {
    fun isReadableBy(
        principal: PrincipalContext,
        addressedTenantId: TenantId = principal.effectiveTenantId,
    ): Boolean {
        if (tenantId != addressedTenantId) return false
        return when (visibility) {
            JobVisibility.OWNER -> principal.principalId == ownerPrincipalId
            JobVisibility.TENANT -> true
            JobVisibility.ADMIN -> principal.isAdmin
        }
    }
}
