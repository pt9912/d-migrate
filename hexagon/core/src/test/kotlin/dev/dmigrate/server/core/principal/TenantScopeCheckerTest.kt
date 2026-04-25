package dev.dmigrate.server.core.principal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class TenantScopeCheckerTest : FunSpec({

    fun principal(
        home: String,
        effective: String = home,
        allowed: Set<String> = emptySet(),
        admin: Boolean = false,
    ) = PrincipalContext(
        principalId = PrincipalId("alice"),
        homeTenantId = TenantId(home),
        effectiveTenantId = TenantId(effective),
        allowedTenantIds = allowed.map { TenantId(it) }.toSet(),
        isAdmin = admin,
        auditSubject = "alice",
        authSource = AuthSource.OIDC,
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
    )

    test("granted when effective tenant equals home tenant") {
        val result = TenantScopeChecker.resolve(principal(home = "acme"))
        result.shouldBeInstanceOf<TenantScopeResolution.Granted>()
        result.effectiveTenantId shouldBe TenantId("acme")
    }

    test("granted when effective tenant is in allowed set") {
        val result = TenantScopeChecker.resolve(
            principal(home = "acme", effective = "umbrella", allowed = setOf("umbrella")),
        )
        result.shouldBeInstanceOf<TenantScopeResolution.Granted>()
        result.effectiveTenantId shouldBe TenantId("umbrella")
    }

    test("denied when effective tenant is foreign") {
        val result = TenantScopeChecker.resolve(
            principal(home = "acme", effective = "umbrella"),
        )
        result.shouldBeInstanceOf<TenantScopeResolution.Denied>()
        result.attemptedTenantId shouldBe TenantId("umbrella")
    }

    test("isAdmin without tenant grant does not lift scope") {
        val result = TenantScopeChecker.resolve(
            principal(home = "acme", effective = "umbrella", admin = true),
        )
        result.shouldBeInstanceOf<TenantScopeResolution.Denied>()
    }

    test("isInScope only true for effective tenant") {
        val ctx = principal(home = "acme", effective = "acme", allowed = setOf("umbrella"))
        TenantScopeChecker.isInScope(ctx, TenantId("acme")) shouldBe true
        TenantScopeChecker.isInScope(ctx, TenantId("umbrella")) shouldBe false
    }
})
