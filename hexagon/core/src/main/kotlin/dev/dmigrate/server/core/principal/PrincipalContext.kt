package dev.dmigrate.server.core.principal

import java.time.Instant

@JvmInline
value class TenantId(val value: String)

@JvmInline
value class PrincipalId(val value: String)

enum class AuthSource { LOCAL, OIDC, SERVICE_ACCOUNT, ANONYMOUS }

data class PrincipalContext(
    val principalId: PrincipalId,
    val homeTenantId: TenantId,
    val effectiveTenantId: TenantId,
    val allowedTenantIds: Set<TenantId>,
    val scopes: Set<String> = emptySet(),
    val isAdmin: Boolean = false,
    val auditSubject: String,
    val authSource: AuthSource,
    val expiresAt: Instant,
)
