package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.ports.JobStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

abstract class JobStoreContractTests(factory: () -> JobStore) : FunSpec({

    test("save and findById round-trip within tenant") {
        val store = factory()
        val record = Fixtures.jobRecord("job_1")
        store.save(record)
        store.findById(Fixtures.tenant("acme"), "job_1") shouldBe record
    }

    test("findById is tenant-scoped") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_1", tenant = "acme"))
        store.findById(Fixtures.tenant("umbrella"), "job_1") shouldBe null
    }

    test("findById returns null for unknown id") {
        val store = factory()
        store.findById(Fixtures.tenant("acme"), "missing") shouldBe null
    }

    test("list returns tenant-scoped page sorted by createdAt") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_b", createdAt = Fixtures.NOW.plusSeconds(2)))
        store.save(Fixtures.jobRecord("job_a", createdAt = Fixtures.NOW))
        store.save(Fixtures.jobRecord("foreign", tenant = "umbrella"))
        val page = store.list(Fixtures.tenant("acme"), PageRequest(pageSize = 10))
        page.items.map { it.managedJob.jobId } shouldBe listOf("job_a", "job_b")
    }

    test("list filters by ownerPrincipalId") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_1", owner = "alice"))
        store.save(Fixtures.jobRecord("job_2", owner = "bob"))
        val page = store.list(
            tenantId = Fixtures.tenant("acme"),
            page = PageRequest(pageSize = 10),
            ownerFilter = Fixtures.principal("alice"),
        )
        page.items.map { it.managedJob.jobId } shouldBe listOf("job_1")
    }

    test("list paginates with nextPageToken") {
        val store = factory()
        repeat(5) { i ->
            store.save(Fixtures.jobRecord("job_$i", createdAt = Fixtures.NOW.plusSeconds(i.toLong())))
        }
        val first = store.list(Fixtures.tenant("acme"), PageRequest(pageSize = 2))
        first.items.size shouldBe 2
        first.nextPageToken shouldBe "2"
        val second = store.list(Fixtures.tenant("acme"), PageRequest(pageSize = 2, pageToken = "2"))
        second.items.size shouldBe 2
        second.nextPageToken shouldBe "4"
        val third = store.list(Fixtures.tenant("acme"), PageRequest(pageSize = 2, pageToken = "4"))
        third.items.size shouldBe 1
        third.nextPageToken shouldBe null
    }

    test("deleteExpired removes records past expiresAt") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_keep", expiresAt = Fixtures.NOW.plusSeconds(10_000)))
        store.save(Fixtures.jobRecord("job_drop", expiresAt = Fixtures.NOW.minusSeconds(10)))
        val removed = store.deleteExpired(Fixtures.NOW)
        removed shouldBe 1
        store.findById(Fixtures.tenant("acme"), "job_drop") shouldBe null
        store.findById(Fixtures.tenant("acme"), "job_keep") shouldNotBe null
    }
})
