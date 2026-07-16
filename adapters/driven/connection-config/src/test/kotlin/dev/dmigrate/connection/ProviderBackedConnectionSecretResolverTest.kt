package dev.dmigrate.connection

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.CredentialProviderRegistry
import dev.dmigrate.server.ports.ResolvedConnection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * Deckt den MCP-Wrapper ab: Principal-Authz + null-credentialRef + verbatim-Delegation an die
 * [CredentialProviderRegistry] (ADR 0035). Ersetzt den früheren `EnvConnectionSecretResolverTest`;
 * das `env:`-Verhalten (inkl. aller Reason-Codes) bleibt unverändert.
 */
class ProviderBackedConnectionSecretResolverTest : FunSpec({

    val tenant = TenantId("acme")

    // Ein Resolver, dessen env:-Provider einen festen Wert liefert (oder null für ENV_NOT_SET).
    fun resolverWithEnv(envValue: String? = "x") = ProviderBackedConnectionSecretResolver(
        CredentialProviderRegistry(listOf(EnvCredentialProvider(envLookup = { envValue }))),
    )

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

    test("env:VAR present yields Success carrying the URL (delegated to the registry)") {
        val outcome = resolverWithEnv("jdbc:postgresql://localhost:5432/prod?password=s3cret")
            .resolve(ref(), principal())
            .shouldBeInstanceOf<ResolvedConnection.Success>()
        outcome.url shouldBe "jdbc:postgresql://localhost:5432/prod?password=s3cret"
    }

    test("env:VAR not set surfaces ENV_NOT_SET") {
        val outcome = resolverWithEnv(envValue = null).resolve(ref(), principal())
            .shouldBeInstanceOf<ResolvedConnection.Failure>()
        outcome.reason shouldBe ResolvedConnection.REASON_ENV_NOT_SET
    }

    test("missing credentialRef surfaces NO_CREDENTIAL_REF (before touching the registry)") {
        val outcome = resolverWithEnv().resolve(ref(credentialRef = null), principal())
            .shouldBeInstanceOf<ResolvedConnection.Failure>()
        outcome.reason shouldBe ResolvedConnection.REASON_NO_CREDENTIAL_REF
    }

    test("unsupported provider scheme surfaces PROVIDER_MISSING (fail-closed)") {
        val outcome = resolverWithEnv().resolve(ref(credentialRef = "vault:secret/pg"), principal())
            .shouldBeInstanceOf<ResolvedConnection.Failure>()
        outcome.reason shouldBe ResolvedConnection.REASON_PROVIDER_MISSING
    }

    test("principal not in allowedPrincipalIds surfaces PRINCIPAL_NOT_AUTHORISED") {
        val outcome = resolverWithEnv().resolve(
            ref(allowedPrincipalIds = setOf(PrincipalId("bob"))),
            principal(id = "alice"),
        ).shouldBeInstanceOf<ResolvedConnection.Failure>()
        outcome.reason shouldBe ResolvedConnection.REASON_PRINCIPAL_NOT_AUTHORISED
    }

    test("principal in allowedPrincipalIds resolves successfully") {
        resolverWithEnv().resolve(
            ref(allowedPrincipalIds = setOf(PrincipalId("alice"))),
            principal(id = "alice"),
        ).shouldBeInstanceOf<ResolvedConnection.Success>()
    }

    test("principal with matching allowedScopes resolves successfully") {
        resolverWithEnv().resolve(
            ref(allowedScopes = setOf("dmigrate:data:write")),
            principal(scopes = setOf("dmigrate:data:write")),
        ).shouldBeInstanceOf<ResolvedConnection.Success>()
    }

    test("admin principal bypasses the allowlist") {
        resolverWithEnv().resolve(
            ref(allowedPrincipalIds = setOf(PrincipalId("bob"))),
            principal(id = "alice", admin = true),
        ).shouldBeInstanceOf<ResolvedConnection.Success>()
    }

    test("empty allowlists are treated as fully open") {
        resolverWithEnv().resolve(ref(), principal())
            .shouldBeInstanceOf<ResolvedConnection.Success>()
    }
})
