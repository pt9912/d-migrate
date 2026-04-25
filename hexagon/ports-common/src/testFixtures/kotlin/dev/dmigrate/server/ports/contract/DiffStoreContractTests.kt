package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.DiffStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

abstract class DiffStoreContractTests(factory: () -> DiffStore) : FunSpec({

    fun entry(
        id: String,
        tenant: String = "acme",
        createdAt: java.time.Instant = Fixtures.NOW,
        expiresAt: java.time.Instant = Fixtures.EXPIRY,
    ) = DiffIndexEntry(
        diffId = id,
        tenantId = TenantId(tenant),
        resourceUri = ServerResourceUri(TenantId(tenant), ResourceKind.DIFFS, id),
        artifactRef = "artifact-$id",
        sourceRef = "source-$id",
        targetRef = "target-$id",
        displayName = "diff $id",
        createdAt = createdAt,
        expiresAt = expiresAt,
    )

    test("save and findById round-trip") {
        val store = factory()
        store.save(entry("d1"))
        store.findById(Fixtures.tenant("acme"), "d1")?.diffId shouldBe "d1"
    }

    test("list is tenant-scoped and sorted by createdAt") {
        val store = factory()
        store.save(entry("d_b", createdAt = Fixtures.NOW.plusSeconds(2)))
        store.save(entry("d_a", createdAt = Fixtures.NOW))
        store.save(entry("foreign", tenant = "umbrella"))
        val page = store.list(Fixtures.tenant("acme"), PageRequest(pageSize = 10))
        page.items.map { it.diffId } shouldBe listOf("d_a", "d_b")
    }

    test("deleteExpired removes entries past expiresAt") {
        val store = factory()
        store.save(entry("keep", expiresAt = Fixtures.NOW.plusSeconds(10_000)))
        store.save(entry("drop", expiresAt = Fixtures.NOW.minusSeconds(10)))
        store.deleteExpired(Fixtures.NOW) shouldBe 1
    }
})
