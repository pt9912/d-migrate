package dev.dmigrate.server.core.connection

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConnectionReferenceTest : FunSpec({

    fun resourceUri() = ServerResourceUri(
        tenantId = TenantId("acme"),
        kind = ResourceKind.CONNECTIONS,
        id = "c1",
    )

    test("non-secret reference holds dialect and credentialRef but no secrets") {
        val ref = ConnectionReference(
            connectionId = "c1",
            tenantId = TenantId("acme"),
            displayName = "ACME Production",
            dialectId = "postgresql",
            sensitivity = ConnectionSensitivity.PRODUCTION,
            resourceUri = resourceUri(),
            credentialRef = "vault:acme/prod-db",
        )
        ref.dialectId shouldBe "postgresql"
        ref.credentialRef shouldBe "vault:acme/prod-db"
        ref.providerRef shouldBe null
        ref.allowedPrincipalIds shouldBe null
    }

    test("supports principal allowlist when provided") {
        val ref = ConnectionReference(
            connectionId = "c2",
            tenantId = TenantId("acme"),
            displayName = "ACME Staging",
            dialectId = "mysql",
            sensitivity = ConnectionSensitivity.NON_PRODUCTION,
            resourceUri = resourceUri(),
            credentialRef = "vault:acme/staging",
            allowedPrincipalIds = setOf(PrincipalId("alice"), PrincipalId("bob")),
            allowedScopes = setOf("schema.read"),
        )
        ref.allowedPrincipalIds shouldBe setOf(PrincipalId("alice"), PrincipalId("bob"))
        ref.allowedScopes shouldBe setOf("schema.read")
    }

    test("ConnectionSensitivity has the three documented levels") {
        ConnectionSensitivity.entries.toSet() shouldBe setOf(
            ConnectionSensitivity.NON_PRODUCTION,
            ConnectionSensitivity.PRODUCTION,
            ConnectionSensitivity.SENSITIVE,
        )
    }
})
