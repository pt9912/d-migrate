package dev.dmigrate.connection

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ConnectionReferenceConfigLoader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant

class LoaderBackedConnectionReferenceStoreTest : FunSpec({

    val tenant = TenantId("acme")

    fun ref(
        id: String,
        allowedPrincipalIds: Set<PrincipalId>? = null,
    ): ConnectionReference = ConnectionReference(
        connectionId = id,
        tenantId = tenant,
        displayName = "DN-$id",
        dialectId = "postgresql",
        sensitivity = ConnectionSensitivity.PRODUCTION,
        resourceUri = ServerResourceUri(tenant, ResourceKind.CONNECTIONS, id),
        credentialRef = "env:PASS_$id",
        allowedPrincipalIds = allowedPrincipalIds,
    )

    fun fixedLoader(refs: List<ConnectionReference>): ConnectionReferenceConfigLoader =
        object : ConnectionReferenceConfigLoader {
            override fun loadAll(): List<ConnectionReference> = refs
        }

    fun principal(
        id: String = "alice",
        scopes: Set<String> = emptySet(),
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

    test("eager-loads loader records at construction time and serves findById") {
        val store = LoaderBackedConnectionReferenceStore(
            fixedLoader(listOf(ref("pg-prod"), ref("pg-stage"))),
        )
        store.findById(tenant, "pg-prod")?.connectionId shouldBe "pg-prod"
        store.findById(tenant, "pg-stage")?.connectionId shouldBe "pg-stage"
        store.findById(tenant, "missing") shouldBe null
    }

    test("list returns refs sorted by connectionId for a principal in the tenant scope") {
        val store = LoaderBackedConnectionReferenceStore(
            fixedLoader(listOf(ref("pg-z"), ref("pg-a"))),
        )
        val items = store.list(principal(), PageRequest(pageSize = 10)).items
        items.map { it.connectionId } shouldBe listOf("pg-a", "pg-z")
    }

    test("list filters by allowedPrincipalIds when set") {
        val store = LoaderBackedConnectionReferenceStore(
            fixedLoader(listOf(
                ref("pg-shared"),
                ref("pg-private", allowedPrincipalIds = setOf(PrincipalId("bob"))),
            )),
        )
        val items = store.list(principal(id = "alice"), PageRequest(pageSize = 10)).items
        items.map { it.connectionId } shouldContain "pg-shared"
        items.map { it.connectionId } shouldNotBe listOf("pg-shared", "pg-private")
    }

    test("admin principal sees every connection regardless of allowlist") {
        val store = LoaderBackedConnectionReferenceStore(
            fixedLoader(listOf(
                ref("pg-shared"),
                ref("pg-private", allowedPrincipalIds = setOf(PrincipalId("bob"))),
            )),
        )
        val items = store.list(principal(admin = true), PageRequest(pageSize = 10)).items
        items.size shouldBe 2
    }

    test("save() updates the in-memory map for additive runtime registrations") {
        val store = LoaderBackedConnectionReferenceStore(fixedLoader(emptyList()))
        store.save(ref("pg-runtime"))
        store.findById(tenant, "pg-runtime")?.connectionId shouldBe "pg-runtime"
    }

    test("delete() removes the entry") {
        val store = LoaderBackedConnectionReferenceStore(
            fixedLoader(listOf(ref("pg-tmp"))),
        )
        store.delete(tenant, "pg-tmp") shouldBe true
        store.findById(tenant, "pg-tmp") shouldBe null
    }

    test("list pagination respects pageSize and emits a nextPageToken") {
        val store = LoaderBackedConnectionReferenceStore(
            fixedLoader((1..5).map { ref("pg-%02d".format(it)) }),
        )
        val firstPage = store.list(principal(), PageRequest(pageSize = 2))
        firstPage.items.size shouldBe 2
        firstPage.nextPageToken shouldNotBe null

        val secondPage = store.list(
            principal(),
            PageRequest(pageSize = 2, pageToken = firstPage.nextPageToken),
        )
        secondPage.items.size shouldBe 2
    }
})
