package dev.dmigrate.mcp.auth

import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ClaimsMapperTest : FunSpec({

    val now = Instant.parse("2026-04-26T12:00:00Z")
    val expiry = now.plusSeconds(3600)

    test("tenant_id is preferred over tid") {
        val p = ClaimsMapper.map(
            subject = "user-1",
            tenantClaim = "tenant-A",
            tidClaim = "tenant-B",
            scopes = setOf("dmigrate:read"),
            expiresAt = expiry,
        )
        p.homeTenantId shouldBe TenantId("tenant-A")
    }

    test("tid is used when tenant_id is null") {
        val p = ClaimsMapper.map(
            subject = "user-1",
            tenantClaim = null,
            tidClaim = "tenant-B",
            scopes = emptySet(),
            expiresAt = expiry,
        )
        p.homeTenantId shouldBe TenantId("tenant-B")
    }

    test("default tenant when both claims are missing or blank") {
        val p = ClaimsMapper.map(
            subject = "user-1",
            tenantClaim = "",
            tidClaim = "  ",
            scopes = emptySet(),
            expiresAt = expiry,
        )
        p.homeTenantId shouldBe TenantId("default")
    }

    test("isAdmin is derived from scope membership only") {
        val admin = ClaimsMapper.map(
            "u", null, null, setOf("dmigrate:read", "dmigrate:admin"), expiry,
        )
        admin.isAdmin shouldBe true

        val nonAdmin = ClaimsMapper.map(
            "u", null, null, setOf("dmigrate:read"), expiry,
        )
        nonAdmin.isAdmin shouldBe false
    }

    test("authSource defaults to OIDC and effective tenant equals home tenant") {
        val p = ClaimsMapper.map(
            subject = "user-1",
            tenantClaim = "tenant-A",
            tidClaim = null,
            scopes = emptySet(),
            expiresAt = expiry,
        )
        p.authSource shouldBe AuthSource.OIDC
        p.effectiveTenantId shouldBe TenantId("tenant-A")
        p.allowedTenantIds shouldBe setOf(TenantId("tenant-A"))
    }

    test("auditSubject and principalId both come from sub") {
        val p = ClaimsMapper.map("user-42", null, null, emptySet(), expiry)
        p.principalId shouldBe PrincipalId("user-42")
        p.auditSubject shouldBe "user-42"
    }

    test("parseScopes prefers `scope` (space-separated) over `scp` (array)") {
        val s = ClaimsMapper.parseScopes(
            scopeClaim = "dmigrate:read dmigrate:job:start",
            scpClaim = listOf("scp-only"),
        )
        s shouldBe setOf("dmigrate:read", "dmigrate:job:start")
    }

    test("parseScopes falls back to `scp` array when `scope` is null") {
        val s = ClaimsMapper.parseScopes(
            scopeClaim = null,
            scpClaim = listOf("dmigrate:read", "dmigrate:admin"),
        )
        s shouldBe setOf("dmigrate:read", "dmigrate:admin")
    }

    test("parseScopes returns empty set when both claims are absent") {
        ClaimsMapper.parseScopes(null, null) shouldBe emptySet()
    }

    test("parseScopes drops blank entries from both shapes") {
        ClaimsMapper.parseScopes("  scope1   scope2  ", null) shouldBe setOf("scope1", "scope2")
        ClaimsMapper.parseScopes(null, listOf("scope1", "", "scope2")) shouldBe setOf("scope1", "scope2")
    }
})
