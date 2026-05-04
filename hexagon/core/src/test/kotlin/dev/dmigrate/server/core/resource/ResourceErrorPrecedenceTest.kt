package dev.dmigrate.server.core.resource

import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * Golden tests for the §4.2 precedence chain. Every Phase-D
 * dispatcher (`resources/read`, `artifact_chunk_get`,
 * `job_status_get`, the `*_list` tools) plugs into
 * [ResourceErrorPrecedence] so the order — parse → tenant scope →
 * blocked kind → resolver — is identical wherever a request lands.
 */
class ResourceErrorPrecedenceTest : FunSpec({

    fun principal(allowed: Set<TenantId> = setOf(TenantId("acme"))): PrincipalContext =
        PrincipalContext(
            principalId = PrincipalId("alice"),
            homeTenantId = TenantId("acme"),
            effectiveTenantId = TenantId("acme"),
            allowedTenantIds = allowed,
            scopes = setOf("dmigrate:read"),
            isAdmin = false,
            auditSubject = "alice",
            authSource = AuthSource.SERVICE_ACCOUNT,
            expiresAt = Instant.MAX,
        )

    test("Stage 1: invalid URI grammar surfaces VALIDATION_ERROR before any tenant check") {
        val result = ResourceErrorPrecedence.classify("not-a-uri", principal())
        result.shouldBeInstanceOf<ResourceClassification.Failed>()
        val err = result.error
        err.shouldBeInstanceOf<McpResourceError.ValidationError>()
        err.dmigrateCode shouldBe McpResourceError.CODE_VALIDATION_ERROR
    }

    test("Stage 2: foreign tenant URI surfaces TENANT_SCOPE_DENIED before kind / store") {
        // The blocked-kind logic runs AFTER the tenant check, so a
        // foreign-tenant + blocked-kind URI must fail with
        // TENANT_SCOPE_DENIED, not VALIDATION_ERROR — otherwise
        // the no-oracle property leaks "this kind exists but is
        // blocked" via the error class.
        val result = ResourceErrorPrecedence.classify(
            "dmigrate://tenants/foreign/upload-sessions/u-1",
            principal(allowed = setOf(TenantId("acme"))),
        )
        result.shouldBeInstanceOf<ResourceClassification.Failed>()
        val err = result.error
        err.shouldBeInstanceOf<McpResourceError.TenantScopeDenied>()
        err.dmigrateCode shouldBe McpResourceError.CODE_TENANT_SCOPE_DENIED
        err.requestedTenant shouldBe TenantId("foreign")
    }

    test("Stage 3: allowed-tenant + blocked kind (UPLOAD_SESSIONS) surfaces VALIDATION_ERROR without store") {
        val result = ResourceErrorPrecedence.classify(
            "dmigrate://tenants/acme/upload-sessions/u-1",
            principal(),
        )
        result.shouldBeInstanceOf<ResourceClassification.Failed>()
        val err = result.error
        err.shouldBeInstanceOf<McpResourceError.ValidationError>()
        err.dmigrateCode shouldBe McpResourceError.CODE_VALIDATION_ERROR
        err.message shouldContain "upload-sessions"
    }

    test("Resolved: every readable tenant-resource kind passes through to the resolver") {
        val readableKinds = listOf(
            "jobs/job-1",
            "artifacts/art-1",
            "schemas/schema-1",
            "profiles/profile-1",
            "diffs/diff-1",
            "connections/conn-1",
        )
        for (path in readableKinds) {
            val result = ResourceErrorPrecedence.classify(
                "dmigrate://tenants/acme/$path",
                principal(),
            )
            result.shouldBeInstanceOf<ResourceClassification.Resolved>()
            result.uri.shouldBeInstanceOf<TenantResourceUri>()
        }
    }

    test("Resolved: artifact-chunk URI passes through to the chunk resolver") {
        val result = ResourceErrorPrecedence.classify(
            "dmigrate://tenants/acme/artifacts/art-1/chunks/chunk-3",
            principal(),
        )
        result.shouldBeInstanceOf<ResourceClassification.Resolved>()
        result.uri.shouldBeInstanceOf<ArtifactChunkResourceUri>()
    }

    test("Resolved: dmigrate://capabilities passes the tenant check (it is tenantless)") {
        val result = ResourceErrorPrecedence.classify("dmigrate://capabilities", principal())
        result.shouldBeInstanceOf<ResourceClassification.Resolved>()
        result.uri shouldBe GlobalCapabilitiesResourceUri
    }

    test("Resolved: artifact-chunk URI in a foreign tenant also surfaces TENANT_SCOPE_DENIED") {
        // The chunk URI carries a tenant segment too — same
        // precedence as TenantResourceUri.
        val result = ResourceErrorPrecedence.classify(
            "dmigrate://tenants/foreign/artifacts/art-1/chunks/chunk-1",
            principal(),
        )
        result.shouldBeInstanceOf<ResourceClassification.Failed>()
        result.error.shouldBeInstanceOf<McpResourceError.TenantScopeDenied>()
    }

    test("Tenant scope honours the broader allowedTenantIds, not just effectiveTenantId") {
        // §4.2 demands tenant-scope is checked against
        // allowedTenantIds. A principal with multiple allowed
        // tenants must be able to address resources in any of
        // them — the AP-6.9 stricter `effectiveTenantId` check
        // is a Phase-B holdover.
        val result = ResourceErrorPrecedence.classify(
            "dmigrate://tenants/secondary/jobs/job-1",
            principal(allowed = setOf(TenantId("acme"), TenantId("secondary"))),
        )
        result.shouldBeInstanceOf<ResourceClassification.Resolved>()
    }

    test("Error wire-data: every typed error surfaces a stable dmigrateCode string") {
        // These strings cross the wire as `error.data.dmigrateCode`
        // and clients pin against them — they MUST stay constant
        // across releases.
        McpResourceError.ValidationError("x").dmigrateCode shouldBe "VALIDATION_ERROR"
        McpResourceError.TenantScopeDenied(TenantId("x")).dmigrateCode shouldBe "TENANT_SCOPE_DENIED"
        McpResourceError.ResourceNotFound.dmigrateCode shouldBe "RESOURCE_NOT_FOUND"
    }

    test("ResourceNotFound carries no requested-resource detail") {
        // The §5.6 no-oracle assertion: the not-found error must
        // not leak the URI, tenant or id of the request. Pin the
        // canonical message string.
        McpResourceError.ResourceNotFound.message shouldBe "Resource not found"
    }
})
