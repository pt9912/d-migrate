package dev.dmigrate.server.core.connection

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri

enum class ConnectionSensitivity { NON_PRODUCTION, PRODUCTION, SENSITIVE }

data class ConnectionReference(
    val connectionId: String,
    val tenantId: TenantId,
    val displayName: String,
    val dialectId: String,
    val sensitivity: ConnectionSensitivity,
    val resourceUri: ServerResourceUri,
    val credentialRef: String? = null,
    val providerRef: String? = null,
    val allowedPrincipalIds: Set<PrincipalId>? = null,
    val allowedScopes: Set<String>? = null,
)
