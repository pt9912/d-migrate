package dev.dmigrate.mcp.auth

import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.StdioTokenGrant
import dev.dmigrate.server.ports.StdioTokenStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class FixedStore(private val grants: Map<String, StdioTokenGrant>) : StdioTokenStore {
    override fun lookup(tokenFingerprint: String) = grants[tokenFingerprint]
}

private val NOW: Instant = Instant.parse("2026-05-01T00:00:00Z")
private val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

private fun grantFor(
    fingerprint: String,
    expiresAt: Instant = NOW.plusSeconds(3600),
    isAdmin: Boolean = false,
    scopes: Set<String> = setOf("dmigrate:read"),
): Pair<String, StdioTokenGrant> = fingerprint to StdioTokenGrant(
    principalId = PrincipalId("alice"),
    tenantId = TenantId("acme"),
    scopes = scopes,
    isAdmin = isAdmin,
    auditSubject = "alice@acme",
    expiresAt = expiresAt,
)

class StdioPrincipalResolverTest : FunSpec({

    test("missing env returns AuthRequired") {
        val resolver = StdioPrincipalResolver(
            tokenSupplier = { null },
            store = FixedStore(emptyMap()),
            clock = FIXED_CLOCK,
        )
        val result = resolver.resolve()
        result.shouldBeInstanceOf<StdioPrincipalResolution.AuthRequired>()
        result.reason shouldBe "DMIGRATE_MCP_STDIO_TOKEN not set"
    }

    test("blank env returns AuthRequired") {
        val resolver = StdioPrincipalResolver(
            tokenSupplier = { "" },
            store = FixedStore(emptyMap()),
            clock = FIXED_CLOCK,
        )
        resolver.resolve().shouldBeInstanceOf<StdioPrincipalResolution.AuthRequired>()
    }

    test("env present but no store returns AuthRequired") {
        val resolver = StdioPrincipalResolver(
            tokenSupplier = { "tok_alice" },
            store = null,
            clock = FIXED_CLOCK,
        )
        val result = resolver.resolve()
        result.shouldBeInstanceOf<StdioPrincipalResolution.AuthRequired>()
        result.reason shouldBe "stdio token registry not configured"
    }

    test("unknown fingerprint returns AuthRequired") {
        val resolver = StdioPrincipalResolver(
            tokenSupplier = { "tok_unknown" },
            store = FixedStore(mapOf(grantFor(StdioTokenFingerprint.of("tok_alice")))),
            clock = FIXED_CLOCK,
        )
        val result = resolver.resolve()
        result.shouldBeInstanceOf<StdioPrincipalResolution.AuthRequired>()
        result.reason shouldBe "stdio token unknown"
    }

    test("expired token returns AuthRequired") {
        val fp = StdioTokenFingerprint.of("tok_alice")
        val resolver = StdioPrincipalResolver(
            tokenSupplier = { "tok_alice" },
            store = FixedStore(mapOf(grantFor(fp, expiresAt = NOW.minusSeconds(1)))),
            clock = FIXED_CLOCK,
        )
        val result = resolver.resolve()
        result.shouldBeInstanceOf<StdioPrincipalResolution.AuthRequired>()
        result.reason shouldBe "stdio token expired"
    }

    test("token expiring at exactly now is treated as expired") {
        val fp = StdioTokenFingerprint.of("tok_alice")
        val resolver = StdioPrincipalResolver(
            tokenSupplier = { "tok_alice" },
            store = FixedStore(mapOf(grantFor(fp, expiresAt = NOW))),
            clock = FIXED_CLOCK,
        )
        val result = resolver.resolve()
        result.shouldBeInstanceOf<StdioPrincipalResolution.AuthRequired>()
    }

    test("valid token produces a Resolved principal context") {
        val fp = StdioTokenFingerprint.of("tok_alice")
        val expiry = NOW.plusSeconds(7200)
        val resolver = StdioPrincipalResolver(
            tokenSupplier = { "tok_alice" },
            store = FixedStore(mapOf(grantFor(fp, expiresAt = expiry, scopes = setOf("dmigrate:read", "dmigrate:job:start")))),
            clock = FIXED_CLOCK,
        )
        val result = resolver.resolve()
        result.shouldBeInstanceOf<StdioPrincipalResolution.Resolved>()
        val p = result.principal
        p.principalId shouldBe PrincipalId("alice")
        p.homeTenantId shouldBe TenantId("acme")
        p.effectiveTenantId shouldBe TenantId("acme")
        p.allowedTenantIds shouldBe setOf(TenantId("acme"))
        p.scopes shouldBe setOf("dmigrate:read", "dmigrate:job:start")
        p.isAdmin shouldBe false
        p.auditSubject shouldBe "alice@acme"
        p.authSource shouldBe AuthSource.SERVICE_ACCOUNT
        p.expiresAt shouldBe expiry
    }

    test("admin grant projects isAdmin=true into the principal") {
        val fp = StdioTokenFingerprint.of("tok_root")
        val resolver = StdioPrincipalResolver(
            tokenSupplier = { "tok_root" },
            store = FixedStore(mapOf(grantFor(fp, isAdmin = true, scopes = setOf("dmigrate:admin")))),
            clock = FIXED_CLOCK,
        )
        val result = resolver.resolve()
        result.shouldBeInstanceOf<StdioPrincipalResolution.Resolved>()
        result.principal.isAdmin shouldBe true
        result.principal.scopes shouldBe setOf("dmigrate:admin")
    }

    test("OS user / process data is never used as fallback principal") {
        // The resolver does not even read `System.getProperty("user.name")`
        // or any process attribute — verified by giving it a token-supplier
        // that yields null AND a configured store; there must be no path
        // back to a Resolved principal.
        val resolver = StdioPrincipalResolver(
            tokenSupplier = { null },
            store = FixedStore(mapOf(grantFor(StdioTokenFingerprint.of("anything")))),
            clock = FIXED_CLOCK,
        )
        resolver.resolve().shouldBeInstanceOf<StdioPrincipalResolution.AuthRequired>()
    }
})
