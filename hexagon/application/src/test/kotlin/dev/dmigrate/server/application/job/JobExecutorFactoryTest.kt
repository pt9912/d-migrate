package dev.dmigrate.server.application.job

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.Duration
import java.time.Instant

class JobExecutorFactoryTest : FunSpec({

    val now: Instant = Instant.parse("2026-05-06T10:00:00Z")

    test("create(Sync) liefert Singleton-Bundle mit Sync-Komponenten") {
        val first = JobExecutorFactory.create(JobExecutorConfig.Sync)
        val second = JobExecutorFactory.create(JobExecutorConfig.SYNC_DEFAULT)

        first.executor shouldBeSameInstanceAs SyncExecutor
        first.admission shouldBeSameInstanceAs SyncJobDispatchAdmission
        first.lifecycle shouldBeSameInstanceAs SyncExecutorLifecycle

        // Sync-Bundle ist Singleton: zwei Aufrufe liefern dasselbe Bundle.
        first shouldBeSameInstanceAs second
    }

    test("create(Async) liefert frisches Bundle mit BoundedAsync-Komponenten") {
        val cfg = JobExecutorConfig.Async(
            coreThreads = 2,
            maxThreads = 2,
            queueCapacity = 4,
            retryAfter = Duration.ofMillis(500),
            shutdownTimeout = Duration.ofSeconds(2),
            threadNamePrefix = "factory-test",
        )

        val bundle = JobExecutorFactory.create(cfg)
        try {
            bundle.executor.shouldBeInstanceOf<BoundedAsyncJobExecutor>()
            val admission = bundle.admission.shouldBeInstanceOf<BoundedAsyncJobDispatchAdmission>()
            bundle.lifecycle.shouldBeInstanceOf<BoundedAsyncJobExecutorLifecycle>()

            admission.capacityValue shouldBe 6
            admission.availablePermits() shouldBe 6
        } finally {
            bundle.lifecycle.shutdown(Duration.ofSeconds(2))
        }
    }

    test("create(Async) gibt pro Aufruf frische Pools/Admissions zurueck") {
        val cfg = JobExecutorConfig.Async(
            coreThreads = 1,
            maxThreads = 1,
            queueCapacity = 1,
        )
        val first = JobExecutorFactory.create(cfg)
        val second = JobExecutorFactory.create(cfg)
        try {
            (first.executor === second.executor) shouldBe false
            (first.admission === second.admission) shouldBe false
            (first.lifecycle === second.lifecycle) shouldBe false
        } finally {
            first.lifecycle.shutdown(Duration.ofSeconds(2))
            second.lifecycle.shutdown(Duration.ofSeconds(2))
        }
    }

    test("Async-Bundle: Lifecycle.shutdown schliesst die Admission") {
        val bundle = JobExecutorFactory.create(
            JobExecutorConfig.Async(coreThreads = 1, maxThreads = 1, queueCapacity = 1),
        )
        bundle.lifecycle.shutdown(Duration.ofSeconds(2)) shouldBe true
        bundle.admission.tryAcquire(now) shouldBe JobDispatchAdmissionOutcome.Closed
    }
})
