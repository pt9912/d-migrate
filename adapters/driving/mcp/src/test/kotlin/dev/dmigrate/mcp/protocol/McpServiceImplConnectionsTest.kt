package dev.dmigrate.mcp.protocol

import dev.dmigrate.mcp.resources.ResourceStores
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemoryDiffStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.time.Instant
import java.util.concurrent.ExecutionException

private val TENANT = TenantId("acme")

private fun principal(scopes: Set<String>) = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = TENANT,
    effectiveTenantId = TENANT,
    allowedTenantIds = setOf(TENANT),
    scopes = scopes,
    isAdmin = scopes.contains("dmigrate:admin"),
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private fun storesWithOneConnection(): ResourceStores = ResourceStores(
    jobStore = InMemoryJobStore(),
    artifactStore = InMemoryArtifactStore(),
    schemaStore = InMemorySchemaStore(),
    profileStore = InMemoryProfileStore(),
    diffStore = InMemoryDiffStore(),
    connectionStore = InMemoryConnectionReferenceStore().apply {
        save(
            ConnectionReference(
                connectionId = "conn-1",
                tenantId = TENANT,
                displayName = "Local DB",
                dialectId = "postgresql",
                sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                resourceUri = ServerResourceUri(TENANT, ResourceKind.CONNECTIONS, "conn-1"),
            ),
        )
    },
)

/**
 * Dispatch-level tests for `connections/list`
 * (ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md Slice B) —
 * scope enforcement and the happy path through [McpServiceImpl].
 * Per-connection status/tenant-resolution logic is covered at the
 * [dev.dmigrate.mcp.registry.ConnectionsListHandler] unit level.
 */
class McpServiceImplConnectionsTest : FunSpec({

    test("connections/list without dmigrate:admin scope fails with a scope error") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(setOf("dmigrate:read")),
            resourceStores = storesWithOneConnection(),
        )
        val future = sut.connectionsList(null)
        val ex = shouldThrow<ExecutionException> { future.get() }
        ex.cause.shouldBeInstanceOf<ResponseErrorException>()
    }


    test("connections/list with dmigrate:admin returns the configured connection, no live status") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(setOf("dmigrate:admin")),
            resourceStores = storesWithOneConnection(),
        )
        val result = sut.connectionsList(ConnectionsListParams()).get()
        result.connections shouldHaveSize 1
        result.connections.single().connectionId shouldBe "conn-1"
        result.connections.single().status shouldBe null
    }
})
