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

    test("resources/list cursor round-trips through the wire boundary") {
        // §12.17 verbindlich: clients treat nextCursor as opaque and
        // pass it back verbatim in the next ResourcesListParams. Pin
        // that loop end-to-end through the McpService — not just
        // ResourcesListHandler — so the JSON-RPC layer can never munge
        // the cursor string.
        //
        // Setup: 3 connection refs in the same tenant; pageSize=1
        // forces multi-call iteration.
        val connStore = InMemoryConnectionReferenceStore().apply {
            (1..3).forEach { i ->
                save(
                    ConnectionReference(
                        connectionId = "c$i",
                        tenantId = TENANT,
                        displayName = "conn-$i",
                        dialectId = "postgresql",
                        sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                        resourceUri = ServerResourceUri(TENANT, ResourceKind.CONNECTIONS, "c$i"),
                    ),
                )
            }
        }
        val stores = ResourceStores(
            jobStore = InMemoryJobStore(),
            artifactStore = InMemoryArtifactStore(),
            schemaStore = InMemorySchemaStore(),
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
            connectionStore = connStore,
        )
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = PRINCIPAL,
            resourceStores = stores,
        )
        val collected = mutableListOf<String>()
        var cursor: String? = null
        var iterations = 0
        while (iterations < 10) {
            val r = sut.resourcesList(ResourcesListParams(cursor = cursor)).get()
            collected += r.resources.map { it.uri }
            if (r.nextCursor == null) break
            cursor = r.nextCursor
            iterations++
        }
        // 3 connections in alphabetical order, all reachable across calls.
        collected shouldContain "dmigrate://tenants/acme/connections/c1"
        collected shouldContain "dmigrate://tenants/acme/connections/c2"
        collected shouldContain "dmigrate://tenants/acme/connections/c3"
    }

    test("HMAC-sealed cursor round-trips through resources/list when codec is wired") {
        // AP D8: when McpServiceImpl receives a non-null cursorCodec,
        // resources/list emits HMAC-sealed cursors and accepts them
        // back. Seed enough jobs to overflow the handler's default
        // 50-row page so the first response carries a non-null
        // sealed cursor, then round-trip it through a second call.
        val keyring = dev.dmigrate.mcp.cursor.CursorKeyring(
            signing = dev.dmigrate.mcp.cursor.CursorKey(
                kid = "test-k1",
                secret = ByteArray(32) { it.toByte() },
            ),
        )
        val codec = dev.dmigrate.mcp.cursor.McpCursorCodec(keyring = keyring)
        val jobStore = InMemoryJobStore().apply {
            (1..60).forEach { i ->
                save(
                    dev.dmigrate.server.core.job.JobRecord(
                        managedJob = dev.dmigrate.server.core.job.ManagedJob(
                            jobId = "job-%03d".format(i),
                            operation = "schema_validate",
                            status = dev.dmigrate.server.core.job.JobStatus.SUCCEEDED,
                            createdAt = Instant.parse("2026-05-04T10:00:00Z"),
                            updatedAt = Instant.parse("2026-05-04T10:00:00Z"),
                            expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
                            createdBy = "alice",
                        ),
                        tenantId = TENANT,
                        ownerPrincipalId = PrincipalId("alice"),
                        visibility = dev.dmigrate.server.core.job.JobVisibility.TENANT,
                        resourceUri = ServerResourceUri(TENANT, ResourceKind.JOBS, "job-%03d".format(i)),
                    ),
                )
            }
        }
        val stores = ResourceStores(
            jobStore = jobStore,
            artifactStore = InMemoryArtifactStore(),
            schemaStore = InMemorySchemaStore(),
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
            connectionStore = InMemoryConnectionReferenceStore(),
        )
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = PRINCIPAL,
            resourceStores = stores,
            cursorCodec = codec,
        )
        val first = sut.resourcesList(ResourcesListParams(cursor = null)).get()
        // 50 jobs surface in the first page; the 60th forces a
        // non-null next cursor pointing at the JOBS family.
        first.resources.size shouldBe 50
        // Sealed wire format: <payload>.<signature>.
        first.nextCursor!!.contains('.') shouldBe true
        // Round-trip the sealed cursor — second call MUST resume
        // mid-walk and surface the remaining 10 jobs without
        // re-emitting any of the first page.
        val firstIds = first.resources.map { it.uri }.toSet()
        val second = sut.resourcesList(ResourcesListParams(cursor = first.nextCursor)).get()
        val secondJobIds = second.resources.map { it.uri }.filter { it.contains("/jobs/") }
        secondJobIds.size shouldBe 10
        secondJobIds.intersect(firstIds).size shouldBe 0
    }

    test("tampered HMAC-sealed cursor fails with VALIDATION_ERROR") {
        // AP D8: a cursor whose payload bytes have been altered must
        // fail the HMAC check and surface as VALIDATION_ERROR with
        // dmigrateCode populated, NOT a silent rebind to a different
        // listing.
        val keyring = dev.dmigrate.mcp.cursor.CursorKeyring(
            signing = dev.dmigrate.mcp.cursor.CursorKey(
                kid = "test-k1",
                secret = ByteArray(32) { it.toByte() },
            ),
        )
        val codec = dev.dmigrate.mcp.cursor.McpCursorCodec(keyring = keyring)
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = PRINCIPAL,
            cursorCodec = codec,
        )
        // Forge a cursor that LOOKS sealed but has a fake signature.
        val forged = "Zm9vYmFy.AAAAAAAA"
        val ex = io.kotest.assertions.throwables.shouldThrow<java.util.concurrent.ExecutionException> {
            sut.resourcesList(ResourcesListParams(cursor = forged)).get()
        }
        val err = (ex.cause as org.eclipse.lsp4j.jsonrpc.ResponseErrorException).responseError
        err.code shouldBe org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidParams.value
        val data = mapValue(err.data)
        data["dmigrateCode"] shouldBe "VALIDATION_ERROR"
    }

    test("forged cursor with non-listable kind fails with -32602") {
        // Belt-and-braces alongside the "malformed cursor" test: a
        // cursor that decodes cleanly but points at a non-listable
        // ResourceKind (e.g. UPLOAD_SESSIONS) MUST be rejected at the
        // protocol layer, not absorbed into an empty result.
        val forged = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"kind":"UPLOAD_SESSIONS","innerToken":null}""".toByteArray())
        val sut = McpServiceImpl(serverVersion = "0.0.0", initialPrincipal = PRINCIPAL)
        val ex = io.kotest.assertions.throwables.shouldThrow<java.util.concurrent.ExecutionException> {
            sut.resourcesList(ResourcesListParams(cursor = forged)).get()
        }
        val err = (ex.cause as org.eclipse.lsp4j.jsonrpc.ResponseErrorException).responseError
        err.code shouldBe org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidParams.value
    }
})

private fun mapValue(value: Any?): Map<*, *> =
    value as? Map<*, *> ?: error("expected map, got ${value?.let { it::class.simpleName } ?: "null"}")
