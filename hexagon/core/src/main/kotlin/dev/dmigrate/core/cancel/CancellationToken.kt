package dev.dmigrate.core.cancel

/**
 * Adapter-neutral cooperative cancellation handle observed by long-running
 * runners and adapters.
 *
 * LF-012 / LN-011 / LN-017 / LN-027 contract (see implementation-plan-0.9.6 §5.1):
 * - thread-/task-safe; a cancel from any worker thread, coroutine context or
 *   job controller becomes visible to every observing runner with
 *   atomic-/volatile-equivalent semantics
 * - the first cancel reason wins; later cancellations must not overwrite it
 * - [throwIfCancellationRequested] throws [OperationCancelledException]
 *   carrying the same reason that [cancellationReason] reports
 */
interface CancellationToken {
    val isCancellationRequested: Boolean

    val cancellationReason: String?

    fun throwIfCancellationRequested()

    companion object {
        /** Default token for CLI paths and tests that do not exercise cancel. */
        fun none(): CancellationToken = NoneCancellationToken
    }
}

private object NoneCancellationToken : CancellationToken {
    override val isCancellationRequested: Boolean = false
    override val cancellationReason: String? = null
    override fun throwIfCancellationRequested() = Unit
}
