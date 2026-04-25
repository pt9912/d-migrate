package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.ProfileStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

abstract class ProfileStoreContractTests(factory: () -> ProfileStore) : FunSpec({

    fun entry(
        id: String,
        tenant: String = "acme",
        createdAt: java.time.Instant = Fixtures.NOW,
        expiresAt: java.time.Instant = Fixtures.EXPIRY,
    ) = ProfileIndexEntry(
        profileId = id,
        tenantId = TenantId(tenant),
        resourceUri = ServerResourceUri(TenantId(tenant), ResourceKind.PROFILES, id),
        artifactRef = "artifact-$id",
        displayName = "profile $id",
        createdAt = createdAt,
        expiresAt = expiresAt,
    )

    test("save and findById round-trip") {
        val store = factory()
        store.save(entry("p1"))
        store.findById(Fixtures.tenant("acme"), "p1")?.profileId shouldBe "p1"
    }

    test("list is tenant-scoped and sorted by createdAt") {
        val store = factory()
        store.save(entry("p_b", createdAt = Fixtures.NOW.plusSeconds(2)))
        store.save(entry("p_a", createdAt = Fixtures.NOW))
        store.save(entry("foreign", tenant = "umbrella"))
        val page = store.list(Fixtures.tenant("acme"), PageRequest(pageSize = 10))
        page.items.map { it.profileId } shouldBe listOf("p_a", "p_b")
    }

    test("deleteExpired removes entries past expiresAt") {
        val store = factory()
        store.save(entry("keep", expiresAt = Fixtures.NOW.plusSeconds(10_000)))
        store.save(entry("drop", expiresAt = Fixtures.NOW.minusSeconds(10)))
        store.deleteExpired(Fixtures.NOW) shouldBe 1
    }
})
