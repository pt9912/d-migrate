package dev.dmigrate.server.ports

import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

interface ApprovalGrantStore {

    fun save(grant: ApprovalGrant): ApprovalGrant

    fun findByTokenFingerprint(
        tenantId: TenantId,
        approvalTokenFingerprint: String,
    ): ApprovalGrant?

    fun deleteExpired(now: Instant): Int
}
