package dev.dmigrate.server.application.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * Pins the §14.2 defaults so a future widening (e.g. raising the
 * upload-bytes ceiling) surfaces here as a deliberate change, not a
 * silent drift.
 */
class ServerCoreLimitsDefaultsTest : FunSpec({

    test("default ServerCoreLimits matches §14.2 table") {
        val limits = ServerCoreLimits()
        limits.idempotency.pendingLease shouldBe Duration.ofSeconds(60)
        limits.idempotency.awaitingApproval shouldBe Duration.ofSeconds(600)
        limits.idempotency.committedRetention shouldBe Duration.ofHours(24)
        limits.idempotency.initResumeTtl shouldBe Duration.ofSeconds(600)
        limits.approval.grantDefaultTtl shouldBe Duration.ofSeconds(300)
        limits.upload.sessionIdleTimeout shouldBe Duration.ofSeconds(900)
        limits.upload.sessionAbsoluteLease shouldBe Duration.ofSeconds(3_600)
        limits.quota.activeJobsPerTenant shouldBe 16L
        limits.quota.activeUploadsPerTenant shouldBe 4L
        limits.quota.uploadBytesPerTenant shouldBe 1L * 1024 * 1024 * 1024
        limits.quota.providerCallsPerMinute shouldBe 60L
    }

    test("subgroups are independently overridable via copy") {
        val limits = ServerCoreLimits()
        val tightened = limits.copy(
            quota = limits.quota.copy(activeJobsPerTenant = 4),
        )
        tightened.quota.activeJobsPerTenant shouldBe 4L
        // Other subgroups stay at defaults.
        tightened.upload.sessionIdleTimeout shouldBe Duration.ofSeconds(900)
        tightened.idempotency.pendingLease shouldBe Duration.ofSeconds(60)
    }
})
