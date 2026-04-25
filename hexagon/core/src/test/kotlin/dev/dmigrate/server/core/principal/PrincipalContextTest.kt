package dev.dmigrate.server.core.principal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class PrincipalContextTest : FunSpec({

    test("constructs with all required fields") {
        val ctx = PrincipalContext(
            principalId = PrincipalId("alice"),
            homeTenantId = TenantId("acme"),
            effectiveTenantId = TenantId("acme"),
            allowedTenantIds = setOf(TenantId("acme")),
            scopes = setOf("read", "write"),
            isAdmin = false,
            auditSubject = "alice@acme",
            authSource = AuthSource.OIDC,
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        )
        ctx.principalId shouldBe PrincipalId("alice")
        ctx.homeTenantId shouldBe TenantId("acme")
        ctx.effectiveTenantId shouldBe TenantId("acme")
        ctx.scopes shouldBe setOf("read", "write")
        ctx.authSource shouldBe AuthSource.OIDC
    }

    test("default values") {
        val ctx = PrincipalContext(
            principalId = PrincipalId("bob"),
            homeTenantId = TenantId("acme"),
            effectiveTenantId = TenantId("acme"),
            allowedTenantIds = emptySet(),
            auditSubject = "bob",
            authSource = AuthSource.LOCAL,
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        )
        ctx.scopes shouldBe emptySet()
        ctx.isAdmin shouldBe false
    }

    test("value classes wrap raw strings") {
        TenantId("foo").value shouldBe "foo"
        PrincipalId("bar").value shouldBe "bar"
    }

    test("AuthSource has all required entries") {
        AuthSource.entries.toSet() shouldBe setOf(
            AuthSource.LOCAL,
            AuthSource.OIDC,
            AuthSource.SERVICE_ACCOUNT,
            AuthSource.ANONYMOUS,
        )
    }
})
