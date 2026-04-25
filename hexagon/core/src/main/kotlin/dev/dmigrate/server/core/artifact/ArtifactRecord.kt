package dev.dmigrate.server.core.artifact

import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri

enum class ArtifactKind {
    SCHEMA,
    PROFILE,
    DIFF,
    DATA_EXPORT,
    UPLOAD_INPUT,
    OTHER,
}

data class ArtifactRecord(
    val managedArtifact: ManagedArtifact,
    val kind: ArtifactKind,
    val tenantId: TenantId,
    val ownerPrincipalId: PrincipalId,
    val visibility: JobVisibility,
    val resourceUri: ServerResourceUri,
    val adminScope: String? = null,
    val jobRef: String? = null,
) {
    fun isReadableBy(principal: PrincipalContext): Boolean {
        if (tenantId != principal.effectiveTenantId) return false
        return when (visibility) {
            JobVisibility.OWNER -> principal.principalId == ownerPrincipalId
            JobVisibility.TENANT -> true
            JobVisibility.ADMIN -> principal.isAdmin
        }
    }
}
