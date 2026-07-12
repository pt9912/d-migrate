package dev.dmigrate.core.concurrency

import dev.dmigrate.core.cancel.CancellationToken
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a list of independent work units with a bounded degree of
 * concurrency and returns their results in input order.
 *
 * LN-007 / LN-008 (see `docs/adr/0032-paralleler-datenpfad-tabellen-partitionen.md`):
 * the FK-safe basis for parallel table / partition processing. Callers
 * group work into layers whose units carry no ordering constraint among
 * themselves, then hand one layer to [run].
 *
 * Contract:
 * - at most `degree` units run at once (a bounded fixed thread pool);
 * - `degree <= 1` — or a single/empty unit list — runs **sequentially on
 *   the calling thread**: no pool is created, so the common default is
 *   byte-for-byte the previous sequential path (zero regression risk);
 * - the first unit that throws aborts the run: not-yet-started units are
 *   skipped and the original throwable is rethrown (fail-fast);
 * - [CancellationToken] is observed before each unit — a requested cancel
 *   throws [dev.dmigrate.core.cancel.OperationCancelledException];
 * - JDBC is blocking I/O, so a plain thread pool (not coroutines) is the
 *   natural primitive.
 */
class ParallelWorkExecutor(
    private val threadNamePrefix: String = "dmigrate-worker",
) {
    fun <T> run(
        units: List<() -> T>,
        degree: Int,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): List<T> {
        if (units.isEmpty()) return emptyList()
        val effectiveDegree = degree.coerceAtLeast(1).coerceAtMost(units.size)
        return if (effectiveDegree == 1) {
            runSequential(units, cancellationToken)
        } else {
            runParallel(units, effectiveDegree, cancellationToken)
        }
    }

    private fun <T> runSequential(units: List<() -> T>, token: CancellationToken): List<T> {
        val results = ArrayList<T>(units.size)
        for (unit in units) {
            token.throwIfCancellationRequested()
            results.add(unit())
        }
        return results
    }

    private fun <T> runParallel(units: List<() -> T>, degree: Int, token: CancellationToken): List<T> {
        val firstError = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(degree, NamedDaemonThreadFactory(threadNamePrefix))
        try {
            val futures: List<Future<T>> = units.map { unit ->
                pool.submit(Callable { runUnit(unit, firstError, token) })
            }
            val results = ArrayList<T>(units.size)
            for (future in futures) {
                results.add(future.get())
            }
            return results
        } catch (e: ExecutionException) {
            // Any completed failure has recorded the first throwable; surface
            // that real cause, not the SkippedUnit sentinel of a later future.
            throw firstError.get() ?: e.cause ?: e
        } finally {
            // Join in-flight workers before returning: on the fail-fast path a straggler
            // must not keep writing on its borrowed connection after run() has thrown
            // (would race a caller's cleanup / leave force-closed connections). On the
            // success path every future is already done, so this returns immediately.
            pool.shutdownNow()
            pool.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> runUnit(
        unit: () -> T,
        firstError: AtomicReference<Throwable?>,
        token: CancellationToken,
    ): T {
        if (firstError.get() != null) throw SkippedUnitException()
        token.throwIfCancellationRequested()
        return try {
            unit()
        } catch (t: Throwable) {
            firstError.compareAndSet(null, t)
            throw t
        }
    }

    private class NamedDaemonThreadFactory(private val prefix: String) : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread =
            Thread(r, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Sentinel for a unit skipped after a sibling already failed (never surfaced to callers). */
    private class SkippedUnitException : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private companion object {
        /** Bounded wait for in-flight workers to finish after run() completes/aborts. */
        const val SHUTDOWN_GRACE_SECONDS = 30L
    }
}
