package dev.dmigrate.mcp.registry

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.ResolvedConnection
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Unit-Tests für [ConnectionsListHandler]
 * (ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md Slice B).
 */
class ConnectionsListHandlerTest : FunSpec({

    val acme = TenantId("acme")
    val other = TenantId("other")

    fun ref(tenant: TenantId, id: String) = ConnectionReference(
        connectionId = id,
        tenantId = tenant,
        displayName = "DB $id",
        dialectId = "postgresql",
        sensitivity = ConnectionSensitivity.NON_PRODUCTION,
        resourceUri = ServerResourceUri(tenant, ResourceKind.CONNECTIONS, id),
    )

    fun store(vararg refs: ConnectionReference) = InMemoryConnectionReferenceStore().apply {
        refs.forEach(::save)
    }

    fun admin(allowed: Set<TenantId> = setOf(acme, other)) = PrincipalContext(
        principalId = PrincipalId("admin"),
        homeTenantId = acme,
        effectiveTenantId = acme,
        allowedTenantIds = allowed,
        scopes = setOf("dmigrate:admin"),
        isAdmin = true,
        auditSubject = "admin",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    val failingResolver = object : ConnectionSecretResolver {
        override fun resolve(reference: ConnectionReference, principal: PrincipalContext) =
            ResolvedConnection.Failure("NOT_CONFIGURED", "unused in this test")
    }

    test("checkLive=false leaves status null") {
        val handler = ConnectionsListHandler(store(ref(acme, "c1")), failingResolver)
        val page = handler.list(admin(), acme, pageSize = 10, resumeToken = null, checkLive = false)
        page.connections shouldHaveSize 1
        page.connections.single().status shouldBe null
    }

    test("AE-B7: an admin can address a tenant other than their own via the resolved tenant") {
        val handler = ConnectionsListHandler(store(ref(acme, "c1"), ref(other, "c2")), failingResolver)
        val page = handler.list(admin(), other, pageSize = 10, resumeToken = null, checkLive = false)
        page.connections.map { it.connectionId } shouldBe listOf("c2")
    }

    test("checkLive=true with a working pool factory yields REACHABLE") {
        val successPool = object : ConnectionPool {
            override val dialect = DatabaseDialect.SQLITE
            override fun borrow(): DatabaseConnection = object : DatabaseConnection {
                override val autoCommit = true
                override fun close() {}
            }
            override fun activeConnections() = 0
            override fun close() {}
        }
        val resolver = object : ConnectionSecretResolver {
            override fun resolve(reference: ConnectionReference, principal: PrincipalContext) =
                ResolvedConnection.Success("sqlite:///tmp/x.db")
        }
        val handler = ConnectionsListHandler(
            store(ref(acme, "c1")),
            resolver,
            poolFactory = { successPool },
        )
        val page = handler.list(admin(), acme, pageSize = 10, resumeToken = null, checkLive = true)
        page.connections.single().status shouldBe ConnectionsListHandler.STATUS_REACHABLE
    }

    test("checkLive=true with a failing pool factory yields UNREACHABLE, no exception message leaks") {
        val resolver = object : ConnectionSecretResolver {
            override fun resolve(reference: ConnectionReference, principal: PrincipalContext) =
                ResolvedConnection.Success("sqlite:///tmp/x.db")
        }
        val handler = ConnectionsListHandler(
            store(ref(acme, "c1")),
            resolver,
            poolFactory = { error("connection refused: host=10.0.4.12 port=5432") },
        )
        val page = handler.list(admin(), acme, pageSize = 10, resumeToken = null, checkLive = true)
        val summary = page.connections.single()
        summary.status shouldBe ConnectionsListHandler.STATUS_UNREACHABLE
        // AE-B3: the wire-visible status must never carry the raw exception text.
        summary.status?.contains("10.0.4.12") shouldBe false
    }

    test("checkLive=true with an unresolvable credential yields CREDENTIAL_ERROR") {
        val handler = ConnectionsListHandler(store(ref(acme, "c1")), failingResolver)
        val page = handler.list(admin(), acme, pageSize = 10, resumeToken = null, checkLive = true)
        page.connections.single().status shouldBe ConnectionsListHandler.STATUS_CREDENTIAL_ERROR
    }

    test("tenant outside allowedTenantIds throws") {
        val handler = ConnectionsListHandler(store(ref(acme, "c1")), failingResolver)
        shouldThrow<dev.dmigrate.server.application.error.TenantScopeDeniedException> {
            ListToolHelpers.resolveTenant(TenantId("forbidden").value, admin(allowed = setOf(acme)))
        }
    }
})
