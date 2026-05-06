package dev.dmigrate.server.application.job

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

class JobDispatchAdmissionTest : FunSpec({

    val now: Instant = Instant.parse("2026-05-06T10:00:00Z")

    context("SyncJobDispatchAdmission") {
        test("always returns Granted with a no-op permit") {
            val outcome = SyncJobDispatchAdmission.tryAcquire(now)
            val granted = outcome.shouldBeInstanceOf<JobDispatchAdmissionOutcome.Granted>()
            granted.permit.close()
            granted.permit.close()
            // A second tryAcquire still returns Granted — sync has no capacity.
            SyncJobDispatchAdmission.tryAcquire(now)
                .shouldBeInstanceOf<JobDispatchAdmissionOutcome.Granted>()
        }
    }

    context("BoundedAsyncJobDispatchAdmission") {

        fun cfg(maxThreads: Int = 2, queueCapacity: Int = 3, retryMs: Long = 250) =
            JobExecutorConfig.Async(
                coreThreads = maxThreads,
                maxThreads = maxThreads,
                queueCapacity = queueCapacity,
                retryAfter = Duration.ofMillis(retryMs),
            )

        test("vergibt exakt maxThreads + queueCapacity Permits, dann Saturated") {
            val admission = BoundedAsyncJobDispatchAdmission(cfg(maxThreads = 2, queueCapacity = 3))
            admission.capacityValue shouldBe 5

            val permits = (1..5).map {
                admission.tryAcquire(now).shouldBeInstanceOf<JobDispatchAdmissionOutcome.Granted>().permit
            }

            val saturated = admission.tryAcquire(now)
                .shouldBeInstanceOf<JobDispatchAdmissionOutcome.Saturated>()
            saturated.current shouldBe 5L
            saturated.limit shouldBe 5L
            saturated.retryAfter shouldBe Duration.ofMillis(250)

            permits.first().close()
            admission.availablePermits() shouldBe 1
            admission.tryAcquire(now).shouldBeInstanceOf<JobDispatchAdmissionOutcome.Granted>()
        }

        test("Permit.close() ist idempotent — zweite Close gibt KEIN zweites Permit frei") {
            val admission = BoundedAsyncJobDispatchAdmission(cfg(maxThreads = 1, queueCapacity = 0))
            val permit = admission.tryAcquire(now)
                .shouldBeInstanceOf<JobDispatchAdmissionOutcome.Granted>().permit
            admission.availablePermits() shouldBe 0

            permit.close()
            admission.availablePermits() shouldBe 1
            permit.close()
            admission.availablePermits() shouldBe 1
            permit.close()
            admission.availablePermits() shouldBe 1
        }

        test("nach close() liefert tryAcquire Closed; bereits ausgegebene Permits bleiben gueltig") {
            val admission = BoundedAsyncJobDispatchAdmission(cfg(maxThreads = 1, queueCapacity = 1))
            val first = admission.tryAcquire(now)
                .shouldBeInstanceOf<JobDispatchAdmissionOutcome.Granted>().permit

            admission.close()

            admission.tryAcquire(now) shouldBe JobDispatchAdmissionOutcome.Closed
            // Already-issued permit must still release cleanly (graceful drain).
            first.close()
        }

        test("Saturated.retryAfter kommt aus der Config") {
            val admission = BoundedAsyncJobDispatchAdmission(
                cfg(maxThreads = 1, queueCapacity = 0, retryMs = 750),
            )
            admission.tryAcquire(now)
            val saturated = admission.tryAcquire(now)
                .shouldBeInstanceOf<JobDispatchAdmissionOutcome.Saturated>()
            saturated.retryAfter shouldBe Duration.ofMillis(750)
        }
    }
})
