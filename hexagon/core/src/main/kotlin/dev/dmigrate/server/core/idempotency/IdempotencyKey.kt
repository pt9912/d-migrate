package dev.dmigrate.server.core.idempotency

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

@JvmInline
value class IdempotencyKey(val value: String)

enum class IdempotencyState { PENDING, AWAITING_APPROVAL, COMMITTED, DENIED }

data class IdempotencyScope(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val idempotencyKey: IdempotencyKey,
)

data class SyncEffectScope(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val approvalKey: String,
)

data class InitResumeScope(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val clientRequestId: String,
)
