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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.time.Instant
import java.util.concurrent.ExecutionException

private val TENANT = TenantId("acme")
private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = TENANT,
    effectiveTenantId = TENANT,
    allowedTenantIds = setOf(TENANT),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
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

class McpServiceImplResourcesTest : FunSpec({

    test("resources/templates/list returns the static 7-template list") {
        val sut = McpServiceImpl(serverVersion = "0.0.0", initialPrincipal = PRINCIPAL)
        val result = sut.resourcesTemplatesList(null).get()
        result.resourceTemplates.size shouldBe 7
        result.resourceTemplates.map { it.uriTemplate } shouldContain
            "dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}"
        result.nextCursor shouldBe null
    }

    test("resources/list with seeded stores returns the principal's resources") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = PRINCIPAL,
            resourceStores = storesWithOneConnection(),
        )
        val result = sut.resourcesList(null).get()
        result.resources.map { it.uri } shouldContain "dmigrate://tenants/acme/connections/conn-1"
        result.nextCursor shouldBe null
    }

    test("resources/list without bound principal fails with InvalidRequest") {
        val sut = McpServiceImpl(serverVersion = "0.0.0", initialPrincipal = null)
        val ex = shouldThrow<ExecutionException> { sut.resourcesList(null).get() }
        val err = (ex.cause as ResponseErrorException).responseError
        err.code shouldBe ResponseErrorCode.InvalidRequest.value
    }

    test("resources/list with malformed cursor fails with InvalidParams (-32602)") {
        val sut = McpServiceImpl(serverVersion = "0.0.0", initialPrincipal = PRINCIPAL)
        val ex = shouldThrow<ExecutionException> {
            sut.resourcesList(ResourcesListParams(cursor = "not-a-real-cursor!?")).get()
        }
        val err = (ex.cause as ResponseErrorException).responseError
        err.code shouldBe ResponseErrorCode.InvalidParams.value
    }

    test("ServerCapabilities advertises resources after AP 6.9") {
        val sut = McpServiceImpl(serverVersion = "0.0.0", initialPrincipal = PRINCIPAL)
        val init = sut.initialize(InitializeParams(McpProtocol.MCP_PROTOCOL_VERSION)).get()
        init.capabilities.resources shouldNotBe null
        init.capabilities.resources!!["listChanged"] shouldBe false
        init.capabilities.resources!!["subscribe"] shouldBe false
    }
})
