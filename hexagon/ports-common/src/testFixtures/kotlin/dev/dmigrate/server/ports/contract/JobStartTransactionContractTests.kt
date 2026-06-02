package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.idempotency.IdempotencyKey
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.ports.IdempotencyStore
import dev.dmigrate.server.ports.JobStartTransaction
import dev.dmigrate.server.ports.JobStartTransactionOutcome
import dev.dmigrate.server.ports.JobStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-012 / LN-011 / LN-017 / LN-027 / `spec/phase-e-port-atomicity.md` (3) Atomicity-
 * Vertraege fuer [JobStartTransaction]-Implementoren.
 *
 * Jede Implementation muss diese Suite durchlaufen, sonst ist der
 * Job-Lifecycle nicht wie in LF-012 / LN-011 / LN-017 / LN-027 spezifiziert sichtbar:
 * `IdempotencyStore.commit` und `JobStore.save` MUESSEN gemeinsam
 * sichtbar werden.
 *
 * Implementoren leiten ab und liefern ein `setup`-Lambda, das pro
 * Test-Aufruf ein frisches Tripel `(IdempotencyStore, JobStore,
 * JobStartTransaction)` liefert. Stores und Transaction muessen
 * konsistent verkabelt sein — fuer Production-Adapter heisst das
 * typischerweise: alle drei teilen sich denselben DB-Konnektor.
 *
 * ```
 * class MyDbBackedJobStartTransactionContractTest :
 *     JobStartTransactionContractTests({
 *         val ipd = MyDbBackedIdempotencyStore(...)
 *         val jobs = MyDbBackedJobStore(...)
 *         JobStartTransactionFixture(
 *             idempotencyStore = ipd,
 *             jobStore = jobs,
 *             transaction = MyDbBackedJobStartTransaction(ipd, jobs, ...),
 *         )
 *     })
 * ```
 */
abstract class JobStartTransactionContractTests(
    fixtureFactory: () -> JobStartTransactionFixture,
) : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val now: Instant = Fixtures.NOW

    fun scope(key: String) = IdempotencyScope(
        tenantId = tenant,
        callerId = Fixtures.principal("alice"),
        toolName = "schema_reverse_start",
        idempotencyKey = IdempotencyKey(key),
    )

    fun jobRecord(jobId: String) = Fixtures.jobRecord(jobId).copy(
        managedJob = Fixtures.jobRecord(jobId).managedJob.copy(
            status = JobStatus.QUEUED,
            createdAt = now,
            updatedAt = now,
        ),
    )

    fun setup(): Triple<IdempotencyStore, JobStore, JobStartTransaction> {
        val fixture = fixtureFactory()
        return Triple(fixture.idempotencyStore, fixture.jobStore, fixture.transaction)
    }

    test("Committed: IdempotencyStore + JobStore werden GEMEINSAM SICHTBAR") {
        val (idempotencyStore, jobStore, transaction) = setup()
        val s = scope("k1")
        idempotencyStore.reserve(s, "fp", now)

        val outcome = transaction.commit(jobRecord("j-1"), s, now)
        outcome.shouldBeInstanceOf<JobStartTransactionOutcome.Committed>()

        // Beide Stores reflektieren den Commit.
        val reserveAfter = idempotencyStore.reserve(s, "fp", now)
        reserveAfter.shouldBeInstanceOf<IdempotencyReserveOutcome.Committed>()
        reserveAfter.resultRef shouldBe "j-1"
        jobStore.findById(tenant, "j-1") shouldBe outcome.record
    }

    test("IdempotencyNotEligible: Ohne vorheriges reserve schlaegt commit fehl, JobStore bleibt leer") {
        val (_, jobStore, transaction) = setup()
        val s = scope("k-no-reserve")

        val outcome = transaction.commit(jobRecord("j-x"), s, now)
        outcome shouldBe JobStartTransactionOutcome.IdempotencyNotEligible
        // Kein Halbzustand: Job darf nicht im JobStore sein.
        jobStore.findById(tenant, "j-x") shouldBe null
    }

    test("Doppel-Commit fuer denselben Scope: zweiter Aufruf ist IdempotencyNotEligible") {
        val (idempotencyStore, jobStore, transaction) = setup()
        val s = scope("k-double")
        idempotencyStore.reserve(s, "fp", now)

        val first = transaction.commit(jobRecord("j-first"), s, now)
        first.shouldBeInstanceOf<JobStartTransactionOutcome.Committed>()

        val second = transaction.commit(jobRecord("j-second"), s, now)
        second shouldBe JobStartTransactionOutcome.IdempotencyNotEligible
        // Nur der ERSTE Job ist im Store.
        jobStore.findById(tenant, "j-first") shouldNotBe null
        jobStore.findById(tenant, "j-second") shouldBe null
    }

    test("Parallele commits auf verschiedene Scopes: jeder gewinnt seinen Slot") {
        val (idempotencyStore, jobStore, transaction) = setup()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..16).map { i ->
                Callable {
                    val s = scope("k-parallel-$i")
                    idempotencyStore.reserve(s, "fp", now)
                    transaction.commit(jobRecord("j-$i"), s, now)
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            results.count { it is JobStartTransactionOutcome.Committed } shouldBe 16
            // Alle 16 Jobs im Store, in keiner Race-Halbzustand-Kombination.
            (1..16).forEach { jobStore.findById(tenant, "j-$it") shouldNotBe null }
        } finally {
            pool.shutdown()
        }
    }

    test("Parallele commits auf DENSELBEN Scope: genau einer wird Committed, Rest IdempotencyNotEligible") {
        val (idempotencyStore, jobStore, transaction) = setup()
        val s = scope("k-contended")
        idempotencyStore.reserve(s, "fp", now)
        val pool = Executors.newFixedThreadPool(8)
        val attemptSeq = AtomicInteger(0)
        try {
            val tasks = (1..16).map {
                Callable {
                    val n = attemptSeq.incrementAndGet()
                    transaction.commit(jobRecord("j-$n"), s, now)
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            results.count { it is JobStartTransactionOutcome.Committed } shouldBe 1
            results.count { it is JobStartTransactionOutcome.IdempotencyNotEligible } shouldBe 15
            // Genau ein Job ist im Store gelandet — das matchende ist
            // dasjenige aus dem Committed-Outcome.
            val committedJobId = results
                .filterIsInstance<JobStartTransactionOutcome.Committed>()
                .single().record.managedJob.jobId
            jobStore.findById(tenant, committedJobId) shouldNotBe null
            jobStore.list(tenant, dev.dmigrate.server.core.pagination.PageRequest(pageSize = 100)).items shouldHaveSize 1
        } finally {
            pool.shutdown()
        }
    }
})

/**
 * Fixture-Tripel fuer [JobStartTransactionContractTests]. Implementoren
 * konstruieren konsistent verkabelte Stores + Transaction (Production-
 * Adapter teilen typischerweise denselben DB-Konnektor, damit
 * LF-012 / LN-011 / LN-017 / LN-027-Atomicity greift).
 */
data class JobStartTransactionFixture(
    val idempotencyStore: IdempotencyStore,
    val jobStore: JobStore,
    val transaction: JobStartTransaction,
)

private infix fun Any?.shouldNotBe(other: Any?) {
    if (this == other) error("expected $this != $other")
}
