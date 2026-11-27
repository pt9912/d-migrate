package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * Ein Eintrag aus `ManagedJob.artifacts` als `dmigrate://tenants/{tenantId}/artifacts/{artifactId}`. Die
 * Job-Datensaetze tragen die vollstaendige URI; eine nackte Kennung aus aelteren Datensaetzen wird ergaenzt.
 */
internal fun jobArtifactUri(tenantId: TenantId, entry: String): String =
    if (entry.startsWith("dmigrate://")) {
        entry
    } else {
        ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, entry).render()
    }
