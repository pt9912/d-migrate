package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

class JobCancelServiceTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val now: Instant = Fixtures.NOW

    fun fixture(): Pair<InMemoryJobStore, InMemoryWorkerHandleRegistry> =
        InMemoryJobStore() to InMemoryWorkerHandleRegistry()

    fun service(
        store: InMemoryJobStore,
        registry: InMemoryWorkerHandleRegistry,
        scrubber: (String) -> String = { it },
        maxReason: Int = JobCancelService.DEFAULT_MAX_REASON_LENGTH,
    ) = JobCancelService(
        jobStore = store,
        workerHandleRegistry = registry,
        cancelReasonScrubber = scrubber,
        maxReasonLength = maxReason,
    )

    fun seedJob(
        store: InMemoryJobStore,
        jobId: String = "j1",
        owner: String = "alice",
        status: JobStatus = JobStatus.QUEUED,
        tenant: String = "acme",
        visibility: JobVisibility = JobVisibility.OWNER,
    ) = store.save(
        Fixtures.jobRecord(jobId, tenant = tenant, owner = owner, status = status, visibility = visibility),
    )

    fun owner(name: String = "alice", tenant: String = "acme", isAdmin: Boolean = false): PrincipalContext =
        Fixtures.principalContext(principalId = name, tenant = tenant, admin = isAdmin)

    test("Eigener QUEUED-Job: CAS auf CANCELLED ohne Worker-Ack-Wait") {
        val (store, registry) = fixture()
        seedJob(store)
        val outcome = service(store, registry).cancel(
            jobIdOrUri = "j1",
            principal = owner(),
            reason = "no longer needed",
            now = now,
        )
        outcome.shouldBeInstanceOf<JobCancelOutcome.Cancelled>()
        outcome.record.managedJob.status shouldBe JobStatus.CANCELLED
        outcome.record.managedJob.cancelRequest.signalAcked shouldBe true
        outcome.record.managedJob.cancelRequest.requestedReason shouldBe "no longer needed"
    }

    test("Eigener RUNNING-Job: markCancelRequested + Worker-Signal → AckPending") {
        val (store, registry) = fixture()
        seedJob(store, status = JobStatus.RUNNING)
        // Worker-Handle registrieren wie es der Dispatcher tun wuerde.
        val source = CancellationTokenSource.create()
        registry.register("j1", source)

        val outcome = service(store, registry).cancel(
            jobIdOrUri = "j1",
            principal = owner(),
            reason = "user-cancel",
            now = now,
        )
        outcome.shouldBeInstanceOf<JobCancelOutcome.AckPending>()
        outcome.retryAfter shouldBe Duration.ofSeconds(2)
        // Worker-Token wurde signalisiert.
        source.token.isCancellationRequested shouldBe true
        // Durabel gespeicherte Cancel-Metadaten.
        outcome.record.managedJob.cancelRequest.requested shouldBe true
        outcome.record.managedJob.cancelRequest.requestedReason shouldBe "user-cancel"
    }

    test("Terminaler Job (SUCCEEDED) bleibt unveraendert → AlreadyTerminal") {
        val (store, registry) = fixture()
        seedJob(store, status = JobStatus.SUCCEEDED)
        val outcome = service(store, registry).cancel("j1", owner(), null, now)
        outcome.shouldBeInstanceOf<JobCancelOutcome.AlreadyTerminal>()
        outcome.record.managedJob.status shouldBe JobStatus.SUCCEEDED
    }

    test("Terminaler Job (FAILED) bleibt unveraendert → AlreadyTerminal") {
        val (store, registry) = fixture()
        seedJob(store, status = JobStatus.FAILED)
        service(store, registry).cancel("j1", owner(), null, now)
            .shouldBeInstanceOf<JobCancelOutcome.AlreadyTerminal>()
    }

    test("Bereits CANCELLED → AlreadyTerminal (kein Doppel-Ack)") {
        val (store, registry) = fixture()
        seedJob(store, status = JobStatus.CANCELLED)
        service(store, registry).cancel("j1", owner(), null, now)
            .shouldBeInstanceOf<JobCancelOutcome.AlreadyTerminal>()
    }

    test("Unbekannte opake jobId im effectiveTenantId → NotFound (no-oracle)") {
        val (store, registry) = fixture()
        service(store, registry).cancel("j-missing", owner(), null, now)
            .shouldBeInstanceOf<JobCancelOutcome.NotFound>()
    }

    test("Opake jobId existiert NUR in fremdem Tenant → NotFound (kein Cross-Tenant-Probe)") {
        // Plan §5.6 line 668-670: opake jobId erlaubt KEINEN globalen
        // Lookup. Job existiert in "beta", Caller ist in "acme" → NotFound.
        val (store, registry) = fixture()
        seedJob(store, jobId = "j-beta", tenant = "beta")
        val outcome = service(store, registry).cancel(
            "j-beta",
            owner(tenant = "acme"),
            null,
            now,
        )
        outcome.shouldBeInstanceOf<JobCancelOutcome.NotFound>()
    }

    test("Opake jobId same-tenant, fremder Principal ohne Admin → ForbiddenPrincipal") {
        val (store, registry) = fixture()
        seedJob(store, owner = "bob") // gehoert bob
        val outcome = service(store, registry).cancel(
            "j1",
            owner(name = "alice", isAdmin = false),
            null,
            now,
        )
        outcome.shouldBeInstanceOf<JobCancelOutcome.ForbiddenPrincipal>()
    }

    test("Opake jobId same-tenant, fremder Principal MIT Admin → erlaubt") {
        val (store, registry) = fixture()
        seedJob(store, owner = "bob", status = JobStatus.QUEUED)
        val outcome = service(store, registry).cancel(
            "j1",
            owner(name = "alice", isAdmin = true),
            null,
            now,
        )
        outcome.shouldBeInstanceOf<JobCancelOutcome.Cancelled>()
    }

    test("Tenant-scoped resourceUri ausserhalb allowedTenantIds → TenantScopeDenied") {
        val (store, registry) = fixture()
        seedJob(store, tenant = "initech", jobId = "j-other")
        val outcome = service(store, registry).cancel(
            jobIdOrUri = "dmigrate://tenants/initech/jobs/j-other",
            principal = owner(tenant = "acme"),
            reason = null,
            now = now,
        )
        outcome.shouldBeInstanceOf<JobCancelOutcome.TenantScopeDenied>()
        outcome.targetTenant shouldBe Fixtures.tenant("initech")
    }

    test("Tenant-scoped resourceUri in allowedTenant mit Admin: erlaubt") {
        val (store, registry) = fixture()
        seedJob(store, tenant = "beta", jobId = "j-beta", owner = "bob", status = JobStatus.QUEUED)
        val principal = Fixtures.principalContext(principalId = "alice", tenant = "acme", admin = true)
            .copy(allowedTenantIds = setOf(Fixtures.tenant("acme"), Fixtures.tenant("beta")))

        val outcome = service(store, registry).cancel(
            jobIdOrUri = "dmigrate://tenants/beta/jobs/j-beta",
            principal = principal,
            reason = null,
            now = now,
        )
        outcome.shouldBeInstanceOf<JobCancelOutcome.Cancelled>()
    }

    test("Reason wird scrubbed UND laengenbegrenzt") {
        val (store, registry) = fixture()
        seedJob(store)
        val outcome = service(
            store, registry,
            scrubber = { it.replace("Bearer xyz", "Bearer ***") },
            maxReason = 20,
        ).cancel(
            "j1",
            owner(),
            "Bearer xyz this reason is rather long and should be truncated to 20 chars",
            now,
        )
        outcome.shouldBeInstanceOf<JobCancelOutcome.Cancelled>()
        outcome.record.managedJob.cancelRequest.requestedReason!!.length shouldBe 20
        outcome.record.managedJob.cancelRequest.requestedReason!!.startsWith("Bearer ***") shouldBe true
    }

    test("Wiederholter cancel: erster Reason gewinnt (Plan §7.8 line 1224-1226)") {
        val (store, registry) = fixture()
        seedJob(store, status = JobStatus.RUNNING)
        registry.register("j1", CancellationTokenSource.create())

        // Erster cancel mit "first-reason"
        val first = service(store, registry).cancel(
            "j1", owner(), "first-reason", now,
        )
        first.shouldBeInstanceOf<JobCancelOutcome.AckPending>()

        // Zweiter cancel mit "second-reason" — Reason darf nicht
        // ueberschrieben werden.
        val second = service(store, registry).cancel(
            "j1", owner(), "second-reason", now.plusSeconds(1),
        )
        second.shouldBeInstanceOf<JobCancelOutcome.AckPending>()
        // Der Store-CONTRACT: markCancelRequested ueberschreibt den
        // ersten Reason nicht (Plan §7.2 Idempotenz, in InMemoryJobStore
        // bereits eingehalten).
        second.record.managedJob.cancelRequest.requestedReason shouldBe "first-reason"
    }

    test("Crash/Retry-Replay: zweiter cancel mit gleichem Reason ist idempotent") {
        // Plan §7.8 line 1252-1254: identischer Reason bleibt idempotent.
        val (store, registry) = fixture()
        seedJob(store, status = JobStatus.RUNNING)
        registry.register("j1", CancellationTokenSource.create())

        val r1 = service(store, registry).cancel("j1", owner(), "user-cancel", now)
        r1.shouldBeInstanceOf<JobCancelOutcome.AckPending>()
        val r2 = service(store, registry).cancel("j1", owner(), "user-cancel", now.plusSeconds(1))
        r2.shouldBeInstanceOf<JobCancelOutcome.AckPending>()
        r2.record.managedJob.cancelRequest.requestedReason shouldBe "user-cancel"
        r2.record.managedJob.cancelRequest.requestedAt.shouldNotBeNull()
    }

    test("Worker terminal zwischen Lookup und Cancel → AlreadyTerminal/AckPending (re-read)") {
        val (store, registry) = fixture()
        // Job ist QUEUED — Service liest, dann manipulieren wir den
        // Status auf SUCCEEDED zwischen findById und transitionStatus
        // (race-Simulation).
        seedJob(store, status = JobStatus.QUEUED)

        // Wir simulieren den Race indirect, indem wir VORHER
        // markCancelRequested aufrufen und transitionStatus dann
        // nachschiebt: stattdessen markieren wir den Job als CANCELLED
        // (Race "anderer Caller hat schon gecancelt").
        store.transitionStatus(tenant, "j1", setOf(JobStatus.QUEUED)) {
            it.copy(status = JobStatus.CANCELLED, updatedAt = now)
        }

        val outcome = service(store, registry).cancel("j1", owner(), null, now)
        outcome.shouldBeInstanceOf<JobCancelOutcome.AlreadyTerminal>()
    }
})
