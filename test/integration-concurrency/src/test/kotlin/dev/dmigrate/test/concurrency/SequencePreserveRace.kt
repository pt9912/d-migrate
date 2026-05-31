package dev.dmigrate.test.concurrency

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Reusable Probe → Writer → Restore race harness for the three
 * dialect-specific race reproducers in this module.
 *
 * Plan-Doc: `docs/planning/done/quality-coverage-expansion-plan.md`
 * §5.3 (Sub-Slice C).
 *
 * **Pattern**:
 *
 * 1. The probe-thread reads the current sequence value (probedValue).
 * 2. The probe-thread signals "probe observed" and blocks until the
 *    writer-thread completes its 50 `nextval`-equivalent advances.
 * 3. The writer-thread waits for the "probe observed" signal, runs
 *    `nextval` N times, records the post-writer maximum value, and
 *    signals "writer finished".
 * 4. The probe-thread resumes, restores the sequence back to
 *    `probedValue` (the stale restore), and signals completion.
 * 5. The test asserts:
 *    - `finalValue == observedProbeValue` (stale restore happened),
 *    - `postWriterMaximum > observedProbeValue` (the writer made
 *      forward progress that the restore overwrote).
 *
 * **knownRace = true**: the reproducer exists to document the
 * existing non-atomic gap in `SequencePreserveStage`. It is NOT a
 * correctness contract. When the atomic-lock slice
 * (`docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`)
 * lands, the assertion flips to `finalValue >= postWriterMaximum`
 * and these tests either move to a quarantine list or stay as a
 * historical reproducer with the new gate disabled.
 *
 * **No free-running writers**: the writer-thread must be latched
 * exactly to the probe→restore window. A writer that runs before
 * the probe or after the restore would either be a no-op (probe sees
 * the advanced value) or mask the stale-restore finding (restore
 * snaps back to a stale value, then writer advances past it again,
 * obscuring whether the restore actually overwrote anything).
 */
internal object SequencePreserveRace {

    /**
     * Observation produced by [runAgainst]. The fields are sufficient
     * to assert both the legacy `knownRace=true` shape and the future
     * atomic-lock-slice gate.
     */
    data class Observation(
        val observedProbeValue: Long,
        val postWriterMaximum: Long,
        val finalValue: Long,
        val writerAdvances: Int,
        val knownRace: Boolean,
    )

    /**
     * Per-dialect adapter used by [runAgainst] to drive the probe,
     * writer and restore against the actual database. Each method is
     * expected to use a fresh connection / fresh transaction so the
     * three steps see each other's effects without snapshot
     * isolation hiding the race.
     */
    interface Adapter {
        /** Read the current sequence value. */
        fun readCurrentValue(): Long

        /** Advance the sequence by one (logically `nextval`). Return the new value. */
        fun advance(): Long

        /** Reset the sequence's current value back to [value]. */
        fun restore(value: Long)
    }

    /**
     * Run the race once. The default `writerAdvances = 50` matches
     * the plan-doc skeleton; bump only with a per-test commit note
     * explaining why.
     *
     * @param latchTimeoutSeconds maximum time either side waits for
     *   its counterpart's signal. The test fails fast (with a clear
     *   error from this function rather than a hung Kotest spec) if
     *   the latch is not honoured within the budget.
     */
    fun runAgainst(adapter: Adapter, writerAdvances: Int = 50, latchTimeoutSeconds: Long = 10): Observation {
        require(writerAdvances > 0) { "writerAdvances must be > 0, was $writerAdvances" }
        val probeObserved = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        // Writer publishes postWriterMaximum before countDown();
        // probe reads it after await() returns. CountDownLatch's
        // happens-before relationship is sufficient for visibility,
        // so no @Volatile is required on the captured var.
        var postWriterMaximum: Long = Long.MIN_VALUE

        val writerThread = thread(start = true, name = "race-writer") {
            check(probeObserved.await(latchTimeoutSeconds, TimeUnit.SECONDS)) {
                "writer-thread timed out waiting for probe to observe its read"
            }
            var max = Long.MIN_VALUE
            repeat(writerAdvances) {
                max = maxOf(max, adapter.advance())
            }
            postWriterMaximum = max
            writerFinished.countDown()
        }

        val observed = adapter.readCurrentValue()
        probeObserved.countDown()
        check(writerFinished.await(latchTimeoutSeconds, TimeUnit.SECONDS)) {
            "probe-thread timed out waiting for writer to finish advances"
        }
        // Restore overwrites whatever the writer advanced to.
        adapter.restore(observed)
        writerThread.join(TimeUnit.SECONDS.toMillis(latchTimeoutSeconds))
        check(!writerThread.isAlive) {
            "writer-thread did not terminate within latch budget"
        }

        val finalValue = adapter.readCurrentValue()
        return Observation(
            observedProbeValue = observed,
            postWriterMaximum = postWriterMaximum,
            finalValue = finalValue,
            writerAdvances = writerAdvances,
            knownRace = true,
        )
    }
}
