package dev.dmigrate.core.cancel

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic cancel token for runner/checkpoint tests (Phase E0.1).
 *
 * Wraps [CancellationTokenSource.create] and exposes hooks that fire on the
 * Nth call to [CancellationToken.throwIfCancellationRequested]. The wrapped
 * token preserves the production thread-safety and idempotent-cancel
 * semantics; the hooks only flip the underlying source at deterministic
 * points so tests do not depend on wall-clock sleeping.
 *
 * Usage patterns supported (implementation-plan-0.9.6 §7.1):
 *
 * - **Cancel after N checkpoints**: [cancelAfterCheckpoints] arms the source
 *   so the (N+1)th `throwIfCancellationRequested()` call observes the cancel
 *   and throws. With `n = 0` the very first checkpoint already throws —
 *   useful for "sofort gecancelter Token" scenarios where the runner is
 *   asked to stop before any side effect.
 *
 * - **Cancel before next side effect**: tests that already know the call
 *   count to the next side-effect checkpoint can use [cancelAfterCheckpoints]
 *   with the matching index. For non-counting scenarios, [cancel] from the
 *   test thread before invoking the next runner stage works because the
 *   token is thread-safe.
 *
 * - **Immediately cancelled token**: call [cancel] before passing
 *   [token] to the runner.
 */
class TestCancellationTokenSource : CancellationTokenSource {

    private val inner: CancellationTokenSource = CancellationTokenSource.create()
    private val checkpointCount = AtomicInteger(0)
    private val arming = AtomicReference<Arming?>(null)

    /** Number of [CancellationToken.throwIfCancellationRequested] calls observed so far. */
    val observedCheckpoints: Int
        get() = checkpointCount.get()

    override val token: CancellationToken = HookedToken()

    override fun cancel(reason: String?) {
        inner.cancel(reason)
    }

    /**
     * Arm the token to cancel itself once [observedCheckpoints] has passed [n].
     * `n = 0` cancels on the first checkpoint, `n = 1` lets one checkpoint pass
     * and cancels on the second, etc. Re-arming overrides a prior arming that
     * has not yet fired; if the source is already cancelled, this is a no-op.
     */
    fun cancelAfterCheckpoints(n: Int, reason: String? = null) {
        require(n >= 0) { "n must be non-negative, was $n" }
        arming.set(Arming(n, reason))
    }

    private inner class HookedToken : CancellationToken {
        override val isCancellationRequested: Boolean
            get() = inner.token.isCancellationRequested

        override val cancellationReason: String?
            get() = inner.token.cancellationReason

        override fun throwIfCancellationRequested() {
            val n = checkpointCount.incrementAndGet()
            arming.get()?.let { armed ->
                if (n > armed.triggerAfter && !inner.token.isCancellationRequested) {
                    inner.cancel(armed.reason)
                }
            }
            inner.token.throwIfCancellationRequested()
        }
    }

    private data class Arming(val triggerAfter: Int, val reason: String?)
}
