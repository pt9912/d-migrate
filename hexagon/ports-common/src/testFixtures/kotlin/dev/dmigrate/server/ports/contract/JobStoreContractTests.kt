package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.JobTransitionOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

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

    // ── Phase E §7.2: atomic status transitions ─────────────────

    test("transitionStatus applies the transformer when current status is allowed") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_a", status = JobStatus.QUEUED))
        val later = Fixtures.NOW.plusSeconds(5)
        val outcome = store.transitionStatus(
            tenantId = Fixtures.tenant("acme"),
            jobId = "job_a",
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { it.copy(status = JobStatus.RUNNING, updatedAt = later) }

        outcome.shouldBeInstanceOf<JobTransitionOutcome.Applied>()
        outcome.record.managedJob.status shouldBe JobStatus.RUNNING
        outcome.record.managedJob.updatedAt shouldBe later

        store.findById(Fixtures.tenant("acme"), "job_a")!!.managedJob.status shouldBe JobStatus.RUNNING
    }

    test("transitionStatus returns IllegalTransition when current status is not allowed") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_a", status = JobStatus.RUNNING))
        val outcome = store.transitionStatus(
            tenantId = Fixtures.tenant("acme"),
            jobId = "job_a",
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { it.copy(status = JobStatus.RUNNING) }

        outcome.shouldBeInstanceOf<JobTransitionOutcome.IllegalTransition>()
        outcome.currentStatus shouldBe JobStatus.RUNNING
        store.findById(Fixtures.tenant("acme"), "job_a")!!.managedJob.status shouldBe JobStatus.RUNNING
    }

    test("transitionStatus returns NotFound for unknown jobId") {
        val store = factory()
        val outcome = store.transitionStatus(
            tenantId = Fixtures.tenant("acme"),
            jobId = "missing",
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { it }

        outcome shouldBe JobTransitionOutcome.NotFound
    }

    test("transitionStatus is tenant-scoped — foreign tenant sees NotFound") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_a", tenant = "acme", status = JobStatus.QUEUED))
        val outcome = store.transitionStatus(
            tenantId = Fixtures.tenant("umbrella"),
            jobId = "job_a",
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { it.copy(status = JobStatus.RUNNING) }

        outcome shouldBe JobTransitionOutcome.NotFound
        store.findById(Fixtures.tenant("acme"), "job_a")!!.managedJob.status shouldBe JobStatus.QUEUED
    }

    test("terminal status stays terminal — transitionStatus rejects further changes") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_a", status = JobStatus.SUCCEEDED))
        val outcome = store.transitionStatus(
            tenantId = Fixtures.tenant("acme"),
            jobId = "job_a",
            allowedFromStatuses = setOf(JobStatus.RUNNING),
        ) { it.copy(status = JobStatus.RUNNING) }

        outcome.shouldBeInstanceOf<JobTransitionOutcome.IllegalTransition>()
        outcome.currentStatus shouldBe JobStatus.SUCCEEDED
    }

    // ── Phase E §7.2: durable cancel-request marker ─────────────

    test("markCancelRequested applies on a running job and records all metadata") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_a", status = JobStatus.RUNNING))
        val requestedAt = Fixtures.NOW.plusSeconds(10)
        val outcome = store.markCancelRequested(
            tenantId = Fixtures.tenant("acme"),
            jobId = "job_a",
            requestedAt = requestedAt,
            requestedBy = "alice",
            signalSource = "mcp:job_cancel",
            reason = "user-requested",
        )

        outcome.shouldBeInstanceOf<JobTransitionOutcome.Applied>()
        val cancel = outcome.record.managedJob.cancelRequest
        cancel.requested shouldBe true
        cancel.signalAcked shouldBe false
        cancel.requestedAt shouldBe requestedAt
        cancel.requestedBy shouldBe "alice"
        cancel.requestedReason shouldBe "user-requested"
        cancel.signalSource shouldBe "mcp:job_cancel"
        cancel.ackedAt shouldBe null
        // Job-Status is unchanged — Phase E §7.2: cancel-request is durable
        // before the worker ack flips status to CANCELLED.
        outcome.record.managedJob.status shouldBe JobStatus.RUNNING
    }

    test("markCancelRequested is idempotent — second call preserves first metadata") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_a", status = JobStatus.RUNNING))
        val firstAt = Fixtures.NOW.plusSeconds(10)
        val secondAt = Fixtures.NOW.plusSeconds(20)
        store.markCancelRequested(
            tenantId = Fixtures.tenant("acme"),
            jobId = "job_a",
            requestedAt = firstAt,
            requestedBy = "alice",
            signalSource = "mcp:first-call",
            reason = "first-reason",
        )
        val outcome = store.markCancelRequested(
            tenantId = Fixtures.tenant("acme"),
            jobId = "job_a",
            requestedAt = secondAt,
            requestedBy = "bob",
            signalSource = "mcp:second-call",
            reason = "second-reason-overwritten?",
        )

        outcome.shouldBeInstanceOf<JobTransitionOutcome.Applied>()
        val cancel = outcome.record.managedJob.cancelRequest
        cancel.requestedAt shouldBe firstAt
        cancel.requestedBy shouldBe "alice"
        cancel.requestedReason shouldBe "first-reason"
        cancel.signalSource shouldBe "mcp:first-call"
    }

    test("markCancelRequested returns IllegalTransition for terminal job") {
        val store = factory()
        store.save(Fixtures.jobRecord("job_a", status = JobStatus.SUCCEEDED))
        val outcome = store.markCancelRequested(
            tenantId = Fixtures.tenant("acme"),
            jobId = "job_a",
            requestedAt = Fixtures.NOW.plusSeconds(10),
            requestedBy = "alice",
            signalSource = "mcp:job_cancel",
        )

        outcome.shouldBeInstanceOf<JobTransitionOutcome.IllegalTransition>()
        outcome.currentStatus shouldBe JobStatus.SUCCEEDED
    }

    test("markCancelRequested returns NotFound for unknown jobId") {
        val store = factory()
        val outcome = store.markCancelRequested(
            tenantId = Fixtures.tenant("acme"),
            jobId = "missing",
            requestedAt = Fixtures.NOW,
            requestedBy = "alice",
            signalSource = "mcp:job_cancel",
        )

        outcome shouldBe JobTransitionOutcome.NotFound
    }
})
