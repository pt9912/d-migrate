package dev.dmigrate.connection

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ResolvedConnection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class EnvConnectionSecretResolverTest : FunSpec({

    val tenant = TenantId("acme")

    fun ref(
        connectionId: String = "pg",
        credentialRef: String? = "env:PG_PASS",
        allowedPrincipalIds: Set<PrincipalId>? = null,
        allowedScopes: Set<String>? = null,
    ): ConnectionReference = ConnectionReference(
        connectionId = connectionId,
        tenantId = tenant,
        displayName = "PG",
        dialectId = "postgresql",
        sensitivity = ConnectionSensitivity.PRODUCTION,
        resourceUri = ServerResourceUri(tenant, ResourceKind.CONNECTIONS, connectionId),
        credentialRef = credentialRef,
        allowedPrincipalIds = allowedPrincipalIds,
        allowedScopes = allowedScopes,
    )

    fun principal(
        id: String = "alice",
        scopes: Set<String> = setOf("dmigrate:data:write"),
        admin: Boolean = false,
    ): PrincipalContext = PrincipalContext(
        principalId = PrincipalId(id),
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = scopes,
        isAdmin = admin,
        auditSubject = id,
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    test("env:VAR with value present yields Success carrying the URL") {
        val sut = EnvConnectionSecretResolver(
            envLookup = { if (it == "PG_PASS") "jdbc:postgresql://localhost:5432/prod?password=s3cret" else null },
        )
        val outcome = sut.resolve(ref(), principal())
        outcome.shouldBeInstanceOf<ResolvedConnection.Success>()
        outcome.url shouldBe "jdbc:postgresql://localhost:5432/prod?password=s3cret"
    }

    test("env:VAR not set surfaces ENV_NOT_SET") {
        val sut = EnvConnectionSecretResolver(envLookup = { null })
        val outcome = sut.resolve(ref(), principal())
        outcome.shouldBeInstanceOf<ResolvedConnection.Failure>()
        outcome.reason shouldBe ResolvedConnection.REASON_ENV_NOT_SET
    }

    test("missing credentialRef surfaces NO_CREDENTIAL_REF") {
        val sut = EnvConnectionSecretResolver(envLookup = { error("must not be called") })
        val outcome = sut.resolve(ref(credentialRef = null), principal())
        outcome.shouldBeInstanceOf<ResolvedConnection.Failure>()
        outcome.reason shouldBe ResolvedConnection.REASON_NO_CREDENTIAL_REF
    }

    test("non-env: provider scheme surfaces PROVIDER_MISSING") {
        // Plan-D §10.10: a connection-backed path without a wired
        // provider MUST fail-closed.
        val sut = EnvConnectionSecretResolver(envLookup = { error("must not be called") })
        val outcome = sut.resolve(ref(credentialRef = "vault:secret/pg"), principal())
        outcome.shouldBeInstanceOf<ResolvedConnection.Failure>()
        outcome.reason shouldBe ResolvedConnection.REASON_PROVIDER_MISSING
    }

    test("principal not in allowedPrincipalIds surfaces PRINCIPAL_NOT_AUTHORISED") {
        val sut = EnvConnectionSecretResolver(envLookup = { "x" })
        val outcome = sut.resolve(
            ref(allowedPrincipalIds = setOf(PrincipalId("bob"))),
            principal(id = "alice"),
        )
        outcome.shouldBeInstanceOf<ResolvedConnection.Failure>()
        outcome.reason shouldBe ResolvedConnection.REASON_PRINCIPAL_NOT_AUTHORISED
    }

    test("principal in allowedPrincipalIds resolves successfully") {
        val sut = EnvConnectionSecretResolver(envLookup = { "x" })
        val outcome = sut.resolve(
            ref(allowedPrincipalIds = setOf(PrincipalId("alice"))),
            principal(id = "alice"),
        )
        outcome.shouldBeInstanceOf<ResolvedConnection.Success>()
    }

    test("principal with matching allowedScopes resolves successfully") {
        val sut = EnvConnectionSecretResolver(envLookup = { "x" })
        val outcome = sut.resolve(
            ref(allowedScopes = setOf("dmigrate:data:write")),
            principal(scopes = setOf("dmigrate:data:write")),
        )
        outcome.shouldBeInstanceOf<ResolvedConnection.Success>()
    }

    test("admin principal bypasses the allowlist") {
        val sut = EnvConnectionSecretResolver(envLookup = { "x" })
        val outcome = sut.resolve(
            ref(allowedPrincipalIds = setOf(PrincipalId("bob"))),
            principal(id = "alice", admin = true),
        )
        outcome.shouldBeInstanceOf<ResolvedConnection.Success>()
    }

    test("empty allowlists are treated as fully open (every principal in the tenant scope)") {
        val sut = EnvConnectionSecretResolver(envLookup = { "x" })
        val outcome = sut.resolve(ref(), principal())
        outcome.shouldBeInstanceOf<ResolvedConnection.Success>()
    }
})
