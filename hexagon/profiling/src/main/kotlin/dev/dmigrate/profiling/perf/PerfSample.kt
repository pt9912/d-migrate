package dev.dmigrate.profiling.perf

/**
 * Aggregated timing summary from a [PerfMeasure.run] invocation. All
 * latencies are exposed in **milliseconds** (`Double`) so the spec can
 * compare them directly against the per-hotpath `*_SMOKE_MAX_MS` /
 * `*_BASELINE_MS` constants without converting from nanoseconds at the
 * call site.
 *
 * Min and max are included so a runaway-Smoke break reports the worst
 * outlier explicitly — useful when shared-container CI jitter produces
 * a single ugly tail iteration that would otherwise hide in the median.
 */
data class PerfSample(
    val iterations: Int,
    val medianMs: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val minMs: Double,
    val maxMs: Double,
) {
    companion object {

        /**
         * Compute a sample from raw nanosecond iteration timings. The
         * input is not modified — we copy into a fresh `LongArray`
         * before sorting so callers can keep their unsorted timings
         * around for diagnostics.
         */
        fun of(timingsNanos: LongArray): PerfSample {
            require(timingsNanos.isNotEmpty()) {
                "timingsNanos must not be empty"
            }
            val sorted = timingsNanos.copyOf()
            sorted.sort()
            val n = sorted.size
            return PerfSample(
                iterations = n,
                medianMs = PerfMeasure.toMillis(percentile(sorted, 0.50)),
                p95Ms = PerfMeasure.toMillis(percentile(sorted, 0.95)),
                p99Ms = PerfMeasure.toMillis(percentile(sorted, 0.99)),
                minMs = PerfMeasure.toMillis(sorted.first()),
                maxMs = PerfMeasure.toMillis(sorted.last()),
            )
        }

        /**
         * Nearest-rank percentile. With a sample as small as 20 the
         * difference between interpolation strategies (nearest-rank,
         * linear, Hazen) is < 1 % for stable hotpaths and dwarfed by
         * container jitter — nearest-rank keeps the contract trivial
         * to reason about in cross-team review and matches what the
         * existing ad-hoc helpers in `LargeJsonFixture` do.
         *
         * **Caveat for small samples**: nearest-rank with the default
         * iterations=20 gives `p99 == max` (`ceil(20 * 0.99) = 20`,
         * sorted[19]) and `p95 == second-worst` (sorted[18]). Below
         * n=100 the `p99Ms` field in [PerfSample] and the trend
         * report is structurally identical to `maxMs` — treat it as
         * an extra max channel, not as an independent tail signal.
         * For iterations=1 (single-shot specs) all five fields
         * collapse to the same value.
         */
        private fun percentile(sorted: LongArray, q: Double): Long {
            require(q in 0.0..1.0) { "q must be in [0,1], was $q" }
            if (sorted.size == 1) return sorted[0]
            val rank = Math.ceil(q * sorted.size).toInt().coerceAtLeast(1)
            return sorted[rank - 1]
        }
    }
}
