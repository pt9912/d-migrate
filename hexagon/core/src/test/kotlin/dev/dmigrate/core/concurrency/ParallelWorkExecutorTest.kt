package dev.dmigrate.core.concurrency

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.cancel.OperationCancelledException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ParallelWorkExecutorTest : FunSpec({

    val executor = ParallelWorkExecutor("test-worker")

    test("empty unit list returns empty") {
        executor.run(emptyList<() -> Int>(), degree = 4).shouldContainExactly()
    }

    test("results are returned in input order regardless of degree") {
        val units = (1..20).map { i -> { i * i } }
        executor.run(units, degree = 6) shouldContainExactly (1..20).map { it * it }
    }

    test("degree <= 1 runs sequentially on the calling thread (no pool)") {
        val callerThread = Thread.currentThread()
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val units = (1..5).map { i -> { threads.add(Thread.currentThread()); i } }

        executor.run(units, degree = 1)

        threads.all { it === callerThread } shouldBe true
    }

    test("at most `degree` units run concurrently") {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val units = (1..24).map {
            {
                val now = active.incrementAndGet()
                peak.updateAndGet { prev -> maxOf(prev, now) }
                Thread.sleep(15)
                active.decrementAndGet()
                it
            }
        }

        executor.run(units, degree = 3)

        peak.get() shouldBeLessThanOrEqualTo 3
    }

    test("exactly `degree` units can run at the same time (barrier proves real parallelism)") {
        val degree = 4
        val barrier = CyclicBarrier(degree)
        // If the executor serialized these, the first await would block forever
        // and time out; all `degree` reaching the barrier proves concurrency.
        val units = (1..degree).map {
            { barrier.await(5, TimeUnit.SECONDS); it }
        }

        executor.run(units, degree = degree) shouldContainExactly (1..degree).toList()
    }

    test("first unit exception propagates as the original throwable") {
        val boom = IllegalStateException("unit blew up")
        val units = (1..8).map<Int, () -> Int> { i ->
            if (i == 1) {
                { throw boom }
            } else {
                { Thread.sleep(20); i }
            }
        }

        val thrown = shouldThrow<IllegalStateException> { executor.run(units, degree = 3) }
        thrown.message shouldBe "unit blew up"
    }

    test("remaining units are skipped after the first failure (fail-fast)") {
        val started = AtomicInteger(0)
        val units = (1..12).map<Int, () -> Int> { i ->
            if (i == 1) {
                { throw IllegalStateException("fail early") }
            } else {
                { started.incrementAndGet(); Thread.sleep(30); i }
            }
        }

        shouldThrow<IllegalStateException> { executor.run(units, degree = 2) }

        // With a failing first unit, not every remaining unit gets to start.
        started.get() shouldBeLessThan 11
    }

    test("degree 1: a failing unit stops the run and skips the rest entirely") {
        val started = AtomicInteger(0)
        val units = (1..6).map<Int, () -> Int> { i ->
            if (i == 1) {
                { throw IllegalStateException("fail first") }
            } else {
                { started.incrementAndGet(); i }
            }
        }

        shouldThrow<IllegalStateException> { executor.run(units, degree = 1) }

        started.get() shouldBe 0
    }

    test("cancellation before a unit throws OperationCancelledException") {
        val source = CancellationTokenSource.create()
        source.cancel("user aborted")
        val units = (1..4).map { i -> { i } }

        shouldThrow<OperationCancelledException> {
            executor.run(units, degree = 3, cancellationToken = source.token)
        }
    }
})
