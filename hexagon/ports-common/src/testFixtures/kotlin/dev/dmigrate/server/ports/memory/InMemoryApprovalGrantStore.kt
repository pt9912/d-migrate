package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ApprovalGrantStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryApprovalGrantStore : ApprovalGrantStore {

    private data class Key(val tenantId: TenantId, val tokenFingerprint: String)

    private val grants = ConcurrentHashMap<Key, ApprovalGrant>()

    override fun save(grant: ApprovalGrant): ApprovalGrant {
        grants[Key(grant.tenantId, grant.approvalTokenFingerprint)] = grant
        return grant
    }

    override fun findByTokenFingerprint(
        tenantId: TenantId,
        approvalTokenFingerprint: String,
    ): ApprovalGrant? = grants[Key(tenantId, approvalTokenFingerprint)]

    override fun deleteExpired(now: Instant): Int {
        val expired = grants.entries
            .filter { it.value.expiresAt.isBefore(now) }
            .map { it.key }
        expired.forEach { grants.remove(it) }
        return expired.size
    }
}
