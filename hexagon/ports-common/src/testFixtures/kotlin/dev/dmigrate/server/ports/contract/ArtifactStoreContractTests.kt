package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.ports.ArtifactStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

abstract class ArtifactStoreContractTests(factory: () -> ArtifactStore) : FunSpec({

    test("save and findById round-trip within tenant") {
        val store = factory()
        val record = Fixtures.artifactRecord("art_1")
        store.save(record)
        store.findById(Fixtures.tenant("acme"), "art_1") shouldBe record
    }

    test("findById is tenant-scoped") {
        val store = factory()
        store.save(Fixtures.artifactRecord("art_1"))
        store.findById(Fixtures.tenant("umbrella"), "art_1") shouldBe null
    }

    test("list filters by ownerPrincipalId") {
        val store = factory()
        store.save(Fixtures.artifactRecord("art_a", owner = "alice"))
        store.save(Fixtures.artifactRecord("art_b", owner = "bob"))
        val page = store.list(
            tenantId = Fixtures.tenant("acme"),
            page = PageRequest(pageSize = 10),
            ownerFilter = Fixtures.principal("alice"),
        )
        page.items.map { it.managedArtifact.artifactId } shouldBe listOf("art_a")
    }

    test("list filters by ArtifactKind") {
        val store = factory()
        store.save(Fixtures.artifactRecord("schema_1", kind = ArtifactKind.SCHEMA))
        store.save(Fixtures.artifactRecord("profile_1", kind = ArtifactKind.PROFILE))
        val page = store.list(
            tenantId = Fixtures.tenant("acme"),
            page = PageRequest(pageSize = 10),
            kindFilter = ArtifactKind.SCHEMA,
        )
        page.items.map { it.managedArtifact.artifactId } shouldBe listOf("schema_1")
    }

    test("deleteExpired removes records past expiresAt") {
        val store = factory()
        store.save(Fixtures.artifactRecord("keep", expiresAt = Fixtures.NOW.plusSeconds(10_000)))
        store.save(Fixtures.artifactRecord("drop", expiresAt = Fixtures.NOW.minusSeconds(10)))
        store.deleteExpired(Fixtures.NOW) shouldBe 1
        store.findById(Fixtures.tenant("acme"), "drop") shouldBe null
    }
})
