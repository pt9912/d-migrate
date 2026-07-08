package dev.dmigrate.profiling.perf

/**
 * Reusable micro-benchmark harness for opt-in `perf`-tagged Kotest specs.
 *
 * **Plan-Doc**: `docs/planning/done-archive/quality-coverage-expansion-plan.md`
 * §5.1 (Phase A). Sub-Slice A introduces this lib so the existing ad-hoc
 * percentile math under `adapters/driven/formats/...perf/...PerfTest.kt`
 * and `adapters/driven/streaming/...StreamingImporterReorderPerfTest.kt`
 * can be migrated onto a single contract.
 *
 * Convention for callers:
 * - Tag the spec with `tags(NamedTag("perf"))` so it is excluded from the
 *   default `!perf` filter and only runs under explicit
 *   `-Dkotest.tags=perf`.
 * - Pin **two** budgets per hotpath:
 *   - `*_SMOKE_MAX_MS`: a generous runaway guard, asserted against
 *     [PerfSample.medianMs] **and** [PerfSample.p95Ms] separately.
 *   - `*_BASELINE_MS`: a nightly/dedicated-runner expectation, written
 *     into the JSON report via [PerfReport.write] but **not** asserted
 *     on shared-container CI.
 * - Default to 5 warmup + 20 measured iterations; bump only with a
 *   commit note explaining why.
 *
 * Measurement strategy: per iteration we record `System.nanoTime()`
 * deltas, sort the sample, and surface median/p95/p99 plus min/max. The
 * block return value is consumed by an internal sink so the JIT cannot
 * dead-code-eliminate the call body — callers should still return a
 * meaningful value (typically the rendered result) rather than `Unit`.
 */
object PerfMeasure {

    private const val NANOS_PER_MILLI = 1_000_000.0

    /**
     * Run [block] [warmup] times (results discarded), then [iterations]
     * times collecting nanosecond timings. Returns a [PerfSample] with
     * median, p95, p99, min, max in milliseconds plus the raw iteration
     * count.
     *
     * @throws IllegalArgumentException if [warmup] is negative or
     *   [iterations] is not strictly positive.
     */
    fun <T> run(
        warmup: Int = 5,
        iterations: Int = 20,
        block: () -> T,
    ): PerfSample {
        require(warmup >= 0) { "warmup must be >= 0, was $warmup" }
        require(iterations > 0) { "iterations must be > 0, was $iterations" }

        try {
            repeat(warmup) {
                Sink.consume(block())
            }

            val timingsNanos = LongArray(iterations)
            for (i in 0 until iterations) {
                val start = System.nanoTime()
                val result = block()
                val end = System.nanoTime()
                Sink.consume(result)
                timingsNanos[i] = end - start
            }

            return PerfSample.of(timingsNanos)
        } finally {
            // Release the last consumed reference so it cannot keep a
            // spec's heavy return value (e.g. ImportResult, DiffResult)
            // strongly reachable for the rest of the test JVM lifetime
            // and pollute heapBefore in a later perf spec running in
            // the same fork. The volatile write at runtime still
            // defeats JIT DCE; clearing after the measurement window
            // does not. Review finding #4.
            Sink.consume(null)
        }
    }

    /**
     * Sink for block return values. Marked `@Volatile` so the JIT cannot
     * eliminate the assignment — the sample loop's body must not be
     * dead-code-eliminated, otherwise the timing measures nothing.
     */
    private object Sink {
        @Volatile
        @Suppress("unused")
        private var slot: Any? = null

        fun consume(value: Any?) {
            slot = value
        }
    }

    /**
     * Convert a nanosecond value to milliseconds with double precision.
     * Exposed because the [PerfSample.of] factory needs it and callers
     * occasionally want to surface a single raw measurement.
     */
    fun toMillis(nanos: Long): Double = nanos / NANOS_PER_MILLI
}
