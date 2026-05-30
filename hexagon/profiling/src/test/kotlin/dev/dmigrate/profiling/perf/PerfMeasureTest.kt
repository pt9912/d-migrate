package dev.dmigrate.profiling.perf

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.atomic.AtomicInteger

class PerfMeasureTest : FunSpec({

    test("run executes the block exactly warmup + iterations times") {
        val counter = AtomicInteger(0)
        PerfMeasure.run(warmup = 3, iterations = 7) {
            counter.incrementAndGet()
        }
        counter.get() shouldBeExactly 10
    }

    test("run with warmup = 0 still produces a sample") {
        val sample = PerfMeasure.run(warmup = 0, iterations = 5) { 42 }
        sample.iterations shouldBeExactly 5
        sample.minMs shouldBeGreaterThanOrEqual 0.0
        sample.maxMs shouldBeGreaterThanOrEqual sample.minMs
    }

    test("run returns ordered percentile aggregate") {
        val sample = PerfMeasure.run(warmup = 1, iterations = 20) {
            // Tiny synthetic workload so the sample contains non-zero
            // values without introducing flakiness from container jitter.
            var acc = 0L
            for (i in 0 until 1_000) {
                acc += i
            }
            acc
        }
        sample.iterations shouldBeExactly 20
        sample.medianMs shouldBeLessThanOrEqual sample.p95Ms
        sample.p95Ms shouldBeLessThanOrEqual sample.p99Ms
        sample.p99Ms shouldBeLessThanOrEqual sample.maxMs
        sample.minMs shouldBeLessThanOrEqual sample.medianMs
        // Synthetic block must finish well under 1s per iteration even
        // on a slow shared runner — runaway-Smoke guard.
        sample.maxMs shouldBeLessThan 1_000.0
    }

    test("run rejects negative warmup") {
        val ex = shouldThrow<IllegalArgumentException> {
            PerfMeasure.run(warmup = -1, iterations = 1) { Unit }
        }
        ex.message!!.shouldContain("warmup")
    }

    test("run rejects zero iterations") {
        val ex = shouldThrow<IllegalArgumentException> {
            PerfMeasure.run(warmup = 1, iterations = 0) { Unit }
        }
        ex.message!!.shouldContain("iterations")
    }

    test("run rejects negative iterations") {
        shouldThrow<IllegalArgumentException> {
            PerfMeasure.run(warmup = 0, iterations = -3) { Unit }
        }
    }

    test("run propagates exceptions thrown by the block") {
        shouldThrow<IllegalStateException> {
            PerfMeasure.run(warmup = 0, iterations = 1) {
                error("boom")
            }
        }
    }

    test("toMillis converts nanoseconds to milliseconds with double precision") {
        PerfMeasure.toMillis(2_500_000L) shouldBeExactlyClose 2.5
        PerfMeasure.toMillis(0L) shouldBeExactlyClose 0.0
        PerfMeasure.toMillis(1L) shouldBeExactlyClose 1e-6
    }

    test("run clears the Sink after returning (review finding #4)") {
        // The Sink retains the last consumed value as a singleton field
        // and used to keep that reference reachable for the entire test
        // JVM lifetime — polluting heapBefore in a subsequent spec. The
        // try/finally cleanup in PerfMeasure.run replaces it with null
        // so the prior return value can be garbage-collected before
        // the next spec starts.
        class HeavyReturn { val payload = ByteArray(1024 * 1024) }
        var refSnapshot: java.lang.ref.WeakReference<HeavyReturn>? = null
        PerfMeasure.run(warmup = 0, iterations = 1) {
            val heavy = HeavyReturn()
            refSnapshot = java.lang.ref.WeakReference(heavy)
            heavy
        }
        // Best-effort GC nudge; if Sink still strong-references the
        // HeavyReturn it survives all of these. Multiple System.gc()
        // calls plus an allocation pressure step matches the pattern
        // the LargeJsonFixture.usedHeapBytes() helper relies on.
        repeat(5) {
            System.gc()
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
        }
        check(refSnapshot!!.get() == null) {
            "PerfMeasure.Sink still strongly references the last consumed value " +
                "after run() returned — finding #4 cleanup is not effective."
        }
    }
})

private infix fun Double.shouldBeExactlyClose(expected: Double) {
    val delta = kotlin.math.abs(this - expected)
    check(delta < 1e-9) { "expected $expected ± 1e-9 but was $this" }
}
