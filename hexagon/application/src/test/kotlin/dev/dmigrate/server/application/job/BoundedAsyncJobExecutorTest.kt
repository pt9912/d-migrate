package dev.dmigrate.server.application.job

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BoundedAsyncJobExecutorTest : FunSpec({

    val now: Instant = Instant.parse("2026-05-06T10:00:00Z")

    fun smallCfg(threads: Int = 2, queue: Int = 4) = JobExecutorConfig.Async(
        coreThreads = threads,
        maxThreads = threads,
        queueCapacity = queue,
        keepAliveSeconds = 1,
        retryAfter = Duration.ofMillis(100),
        shutdownTimeout = Duration.ofSeconds(5),
        threadNamePrefix = "test-worker",
    )

    test("submitted task runs on a daemon thread with the configured prefix") {
        val executor = BoundedAsyncJobExecutor(smallCfg(threads = 1, queue = 1))
        val capturedName = AtomicReference<String>()
        val capturedDaemon = AtomicReference<Boolean>()
        val done = CountDownLatch(1)
        try {
            executor.execute {
                capturedName.set(Thread.currentThread().name)
                capturedDaemon.set(Thread.currentThread().isDaemon)
                done.countDown()
            }
            done.await(2, TimeUnit.SECONDS) shouldBe true
            capturedName.get() shouldStartWith "test-worker-"
            capturedDaemon.get() shouldBe true
        } finally {
            executor.shutdown(Duration.ofSeconds(2))
        }
    }

    test("status() reflects active/queued/completed counts") {
        val executor = BoundedAsyncJobExecutor(smallCfg(threads = 1, queue = 4))
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            executor.execute {
                started.countDown()
                gate.await()
            }
            // queue 2 more tasks behind the active one
            executor.execute { /* no-op */ }
            executor.execute { /* no-op */ }

            started.await(2, TimeUnit.SECONDS) shouldBe true
            val running = executor.status()
            running.active shouldBe 1L
            running.queued shouldBeGreaterThanOrEqualTo 1L
            running.capacity shouldBe 5L

            gate.countDown()
            executor.shutdown(Duration.ofSeconds(5)) shouldBe true

            val drained = executor.status()
            drained.completed shouldBe 3L
        } finally {
            gate.countDown()
            executor.shutdown(Duration.ofSeconds(2))
        }
    }

    test("uncaught exception does not kill the pool — subsequent task runs") {
        val executor = BoundedAsyncJobExecutor(smallCfg(threads = 1, queue = 4))
        val survived = CountDownLatch(1)
        try {
            executor.execute { throw IllegalStateException("boom") }
            executor.execute { survived.countDown() }
            survived.await(2, TimeUnit.SECONDS) shouldBe true
        } finally {
            executor.shutdown(Duration.ofSeconds(2))
        }
    }

    test("after shutdown, execute throws ExecutorClosedException and rejected counter increments") {
        val executor = BoundedAsyncJobExecutor(smallCfg(threads = 1, queue = 1))
        executor.shutdown(Duration.ofSeconds(1)) shouldBe true

        shouldThrow<ExecutorClosedException> {
            executor.execute { /* never runs */ }
        }
        executor.status().rejected shouldBe 1L
    }

    test("shutdown(timeout=0) on busy pool returns false; shutdownNow interrupts in-flight") {
        val executor = BoundedAsyncJobExecutor(smallCfg(threads = 1, queue = 1))
        val started = CountDownLatch(1)
        val interrupted = AtomicReference<Boolean>(false)
        executor.execute {
            started.countDown()
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                interrupted.set(true)
                Thread.currentThread().interrupt()
            }
        }
        started.await(2, TimeUnit.SECONDS) shouldBe true

        executor.shutdown(Duration.ZERO) shouldBe false

        val notRun = executor.shutdownNow()
        notRun.shouldHaveSize(0)
        // Wait for the worker thread to exit cleanly after interrupt.
        val end = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (executor.status().active > 0 && System.nanoTime() < end) {
            Thread.sleep(10)
        }
        executor.status().active shouldBe 0L
        interrupted.get() shouldBe true
    }

    test("BoundedAsyncJobExecutorLifecycle: shutdown closes admission first, then drains pool") {
        val cfg = smallCfg(threads = 2, queue = 4)
        val executor = BoundedAsyncJobExecutor(cfg)
        val admission = BoundedAsyncJobDispatchAdmission(cfg)
        val lifecycle = BoundedAsyncJobExecutorLifecycle(executor, admission)

        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val permit = admission.tryAcquire(now)
            .let { it as JobDispatchAdmissionOutcome.Granted }.permit
        executor.execute {
            running.countDown()
            release.await()
        }
        running.await(2, TimeUnit.SECONDS) shouldBe true

        // Stage 1: kick off shutdown in the background.
        val shutdownThread = Thread {
            lifecycle.shutdown(Duration.ofSeconds(5))
        }.apply { start() }

        // Give shutdown a moment to close the admission.
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (admission.tryAcquire(now) !is JobDispatchAdmissionOutcome.Closed) {
            if (System.nanoTime() > deadline) error("admission did not close within 2s")
            Thread.sleep(10)
        }

        // Stage 2: drain the in-flight task; shutdown should now finish.
        release.countDown()
        shutdownThread.join(Duration.ofSeconds(5).toMillis())
        shutdownThread.isAlive shouldBe false

        // Lifecycle.status() forwards to executor.status().
        lifecycle.status().completed shouldBe 1L
        permit.close()
    }

    test("BoundedAsyncJobExecutorLifecycle: shutdown timeout escalates to interrupt") {
        val cfg = smallCfg(threads = 1, queue = 1)
        val executor = BoundedAsyncJobExecutor(cfg)
        val admission = BoundedAsyncJobDispatchAdmission(cfg)
        val lifecycle = BoundedAsyncJobExecutorLifecycle(executor, admission)
        val started = CountDownLatch(1)
        val interrupted = AtomicReference<Boolean>(false)

        executor.execute {
            started.countDown()
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                interrupted.set(true)
                Thread.currentThread().interrupt()
            }
        }
        started.await(2, TimeUnit.SECONDS) shouldBe true

        lifecycle.shutdown(Duration.ZERO) shouldBe false

        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (executor.status().active > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        executor.status().active shouldBe 0L
        interrupted.get() shouldBe true
        admission.tryAcquire(now) shouldBe JobDispatchAdmissionOutcome.Closed
    }
})
