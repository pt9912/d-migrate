package dev.dmigrate.server.core.job

import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class JobRecordTest : FunSpec({

    val now = Instant.parse("2026-04-25T10:00:00Z")
    val expiry = Instant.parse("2030-01-01T00:00:00Z")

    fun managedJob() = ManagedJob(
        jobId = "job_1",
        operation = "data.export",
        status = JobStatus.SUCCEEDED,
        createdAt = now,
        updatedAt = now,
        expiresAt = expiry,
        createdBy = "alice",
    )

    fun resourceUri(tenant: String, id: String) = ServerResourceUri(
        tenantId = TenantId(tenant),
        kind = ResourceKind.JOBS,
        id = id,
    )

    fun principal(
        principalId: String,
        effectiveTenant: String,
        admin: Boolean = false,
    ) = PrincipalContext(
        principalId = PrincipalId(principalId),
        homeTenantId = TenantId(effectiveTenant),
        effectiveTenantId = TenantId(effectiveTenant),
        allowedTenantIds = setOf(TenantId(effectiveTenant)),
        isAdmin = admin,
        auditSubject = principalId,
        authSource = AuthSource.OIDC,
        expiresAt = expiry,
    )

    test("OWNER visibility readable only by owner in same tenant") {
        val record = JobRecord(
            managedJob = managedJob(),
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.OWNER,
            resourceUri = resourceUri("acme", "job_1"),
        )
        record.isReadableBy(principal("alice", "acme")) shouldBe true
        record.isReadableBy(principal("bob", "acme")) shouldBe false
    }

    test("TENANT visibility readable by any principal in same tenant") {
        val record = JobRecord(
            managedJob = managedJob(),
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.TENANT,
            resourceUri = resourceUri("acme", "job_1"),
        )
        record.isReadableBy(principal("alice", "acme")) shouldBe true
        record.isReadableBy(principal("bob", "acme")) shouldBe true
    }

    test("ADMIN visibility readable only by admin in same tenant") {
        val record = JobRecord(
            managedJob = managedJob(),
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.ADMIN,
            resourceUri = resourceUri("acme", "job_1"),
        )
        record.isReadableBy(principal("alice", "acme")) shouldBe false
        record.isReadableBy(principal("admin", "acme", admin = true)) shouldBe true
    }

    test("foreign tenant always denied even for admin") {
        val record = JobRecord(
            managedJob = managedJob(),
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.TENANT,
            resourceUri = resourceUri("acme", "job_1"),
        )
        record.isReadableBy(principal("admin", "umbrella", admin = true)) shouldBe false
    }

    test("adminScope persists when set") {
        val record = JobRecord(
            managedJob = managedJob(),
            tenantId = TenantId("acme"),
            ownerPrincipalId = PrincipalId("alice"),
            visibility = JobVisibility.ADMIN,
            resourceUri = resourceUri("acme", "job_1"),
            adminScope = "platform-ops",
        )
        record.adminScope shouldBe "platform-ops"
    }
})
