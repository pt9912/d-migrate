package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

abstract class SchemaStoreContractTests(factory: () -> SchemaStore) : FunSpec({

    fun entry(
        id: String,
        tenant: String = "acme",
        createdAt: java.time.Instant = Fixtures.NOW,
        expiresAt: java.time.Instant = Fixtures.EXPIRY,
    ) = SchemaIndexEntry(
        schemaId = id,
        tenantId = TenantId(tenant),
        resourceUri = ServerResourceUri(TenantId(tenant), ResourceKind.SCHEMAS, id),
        artifactRef = "artifact-$id",
        displayName = "schema $id",
        createdAt = createdAt,
        expiresAt = expiresAt,
    )

    test("save and findById round-trip") {
        val store = factory()
        val e = entry("s1")
        store.save(e)
        store.findById(Fixtures.tenant("acme"), "s1") shouldBe e
    }

    test("findById is tenant-scoped") {
        val store = factory()
        store.save(entry("s1"))
        store.findById(Fixtures.tenant("umbrella"), "s1") shouldBe null
    }

    test("list is tenant-scoped and sorted by createdAt") {
        val store = factory()
        store.save(entry("s_b", createdAt = Fixtures.NOW.plusSeconds(2)))
        store.save(entry("s_a", createdAt = Fixtures.NOW))
        store.save(entry("foreign", tenant = "umbrella"))
        val page = store.list(Fixtures.tenant("acme"), PageRequest(pageSize = 10))
        page.items.map { it.schemaId } shouldBe listOf("s_a", "s_b")
    }

    test("deleteExpired removes entries past expiresAt") {
        val store = factory()
        store.save(entry("keep", expiresAt = Fixtures.NOW.plusSeconds(10_000)))
        store.save(entry("drop", expiresAt = Fixtures.NOW.minusSeconds(10)))
        store.deleteExpired(Fixtures.NOW) shouldBe 1
        store.findById(Fixtures.tenant("acme"), "drop") shouldBe null
    }
})
