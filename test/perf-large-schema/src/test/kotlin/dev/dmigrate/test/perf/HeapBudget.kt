package dev.dmigrate.test.perf

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * Heap-peak budget for the large-schema scale tests.
 *
 * Plan-Doc: `docs/planning/done-archive/quality-coverage-expansion-plan.md`
 * §5.4 — "Erste Wahl: `MemoryPoolMXBean.peakUsage` ueber alle
 * Heap-Pools mit explizitem `resetPeakUsage()` vor jedem Scale-Run
 * und einem GC-induzierten Snapshot direkt vor und nach dem Lauf."
 *
 * Pattern (canonical):
 *
 * ```kotlin
 * val budget = HeapBudget.start(scale.maxHeapMb)
 * val duration = measureTimedValue { runMigratePipeline(schema) }.duration
 * budget.peakUsedMb() shouldBeLessThan scale.maxHeapMb
 * ```
 *
 * [start] does three things in order:
 *
 * 1. Two `System.gc()` hints with a short sleep between, giving the
 *    GC a real chance to drain pending finalisers and reclaim
 *    pre-test residue from the previous scale.
 * 2. `resetPeakUsage()` on every heap-typed `MemoryPoolMXBean` so
 *    [peakUsedMb] reflects only the load produced inside the test
 *    block.
 * 3. Returns a `HeapBudget` instance the spec uses to read the
 *    post-block peak.
 *
 * The peak is the SUM of `peakUsage.used` across all heap-typed
 * `MemoryPoolMXBean`s (Eden + Survivor + Old in HotSpot,
 * equivalents on other JVMs), converted to MiB. Summing across
 * pools approximates the total heap working-set peak; a per-pool
 * max would understate the load when Eden and Old peak at
 * overlapping times. Non-heap pools (metaspace, code cache) are
 * deliberately excluded — they reflect JVM lifecycle costs, not
 * workload-driven heap pressure.
 */
internal class HeapBudget private constructor() {

    /**
     * Sum of heap-pool `peakUsage.used` in mebibytes (1 MiB =
     * 1024 * 1024 bytes) since the most recent [start] call.
     */
    fun peakUsedMb(): Long {
        val peakBytes = ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == MemoryType.HEAP }
            .sumOf { pool ->
                val peak = pool.peakUsage ?: return@sumOf 0L
                peak.used.coerceAtLeast(0L)
            }
        return peakBytes / (BYTES_PER_KIB * BYTES_PER_KIB)
    }

    companion object {
        private const val BYTES_PER_KIB: Long = 1024L
        private const val GC_NUDGE_SLEEP_MS: Long = 50L

        /**
         * Reset heap-pool peak counters and return a fresh budget
         * handle. Two `System.gc()` hints with a short sleep give
         * the GC a chance to reclaim residue from the previous
         * scale run; the second hint catches anything the first
         * left in a soft-reference queue.
         *
         * @param maxMb advisory limit, retained on the returned
         *   instance via [peakUsedMb]'s comparison contract. The
         *   value is not enforced by [HeapBudget] itself — the
         *   spec asserts `peakUsedMb() shouldBeLessThan maxMb`.
         */
        @Suppress("UNUSED_PARAMETER", "ExplicitGarbageCollectionCall")
        fun start(maxMb: Long): HeapBudget {
            // System.gc() hints are intentional per plan-doc §5.4:
            // heap-peak measurement is only meaningful after pending
            // finalisers from the previous scale run have had a
            // chance to drain. Detekt's ExplicitGarbageCollectionCall
            // is suppressed narrowly on this method, not module-wide.
            System.gc()
            try {
                Thread.sleep(GC_NUDGE_SLEEP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            System.gc()
            ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP }
                .forEach { pool ->
                    runCatching { pool.resetPeakUsage() }
                }
            return HeapBudget()
        }
    }
}
