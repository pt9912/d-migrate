package dev.dmigrate.server.application.config

import java.time.Duration

data class IdempotencyLimits(
    val pendingLease: Duration = Duration.ofSeconds(60),
    val awaitingApproval: Duration = Duration.ofSeconds(600),
    val committedRetention: Duration = Duration.ofHours(24),
    val initResumeTtl: Duration = Duration.ofSeconds(600),
)

data class ApprovalLimits(
    val grantDefaultTtl: Duration = Duration.ofSeconds(300),
)

data class UploadLimits(
    val sessionIdleTimeout: Duration = Duration.ofSeconds(900),
    val sessionAbsoluteLease: Duration = Duration.ofSeconds(3_600),
)

/**
 * Per-tenant defaults from `ImpPlan-0.9.6-A.md` §14.2. Per-principal
 * and per-operation overrides are deliberately omitted in Phase A —
 * `QuotaKey` already supports both at the store level, but no
 * default is configured here.
 */
data class QuotaLimits(
    val activeJobsPerTenant: Long = 16,
    val activeUploadsPerTenant: Long = 4,
    val uploadBytesPerTenant: Long = 1L * 1024 * 1024 * 1024,
    val parallelSegmentWritesPerSession: Long = 4,
    val providerCallsPerMinute: Long = 60,
)

data class ServerCoreLimits(
    val idempotency: IdempotencyLimits = IdempotencyLimits(),
    val approval: ApprovalLimits = ApprovalLimits(),
    val upload: UploadLimits = UploadLimits(),
    val quota: QuotaLimits = QuotaLimits(),
)
