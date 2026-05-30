package dev.dmigrate.profiling.perf

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe

class PerfSampleTest : FunSpec({

    test("of rejects empty timings") {
        shouldThrow<IllegalArgumentException> {
            PerfSample.of(LongArray(0))
        }
    }

    test("of with single timing reports the same value for every percentile") {
        val sample = PerfSample.of(longArrayOf(7_500_000L))
        sample.iterations shouldBeExactly 1
        sample.minMs shouldBe 7.5
        sample.medianMs shouldBe 7.5
        sample.p95Ms shouldBe 7.5
        sample.p99Ms shouldBe 7.5
        sample.maxMs shouldBe 7.5
    }

    test("of computes nearest-rank percentiles on a known sample") {
        // 20 timings: 1ms, 2ms, …, 20ms. Nearest-rank for q=0.50 is
        // ceil(20*0.5)=10 → 10ms; q=0.95 → 19; q=0.99 → 20.
        val timings = LongArray(20) { (it + 1) * 1_000_000L }
        val sample = PerfSample.of(timings)
        sample.iterations shouldBeExactly 20
        sample.minMs shouldBe 1.0
        sample.medianMs shouldBe 10.0
        sample.p95Ms shouldBe 19.0
        sample.p99Ms shouldBe 20.0
        sample.maxMs shouldBe 20.0
    }

    test("of does not modify the caller's array") {
        val timings = longArrayOf(5L, 3L, 8L, 1L, 9L)
        val before = timings.copyOf()
        PerfSample.of(timings)
        timings shouldBe before
    }

    test("of accepts already-sorted input") {
        val timings = longArrayOf(1L, 2L, 3L, 4L, 5L)
        val sample = PerfSample.of(timings)
        sample.minMs shouldBeLessThanOrEqual sample.maxMs
    }
})
