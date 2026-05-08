package dev.dmigrate.core.cancel

import java.util.concurrent.atomic.AtomicReference

/**
 * Producer side of the [CancellationToken] contract.
 *
 * Tests, MCP job controllers and worker orchestration cancel through this
 * handle. [cancel] is idempotent: the first call wins and the recorded
 * reason is preserved on every subsequent call.
 */
interface CancellationTokenSource {
    val token: CancellationToken

    fun cancel(reason: String? = null)

    companion object {
        fun create(): CancellationTokenSource = DefaultCancellationTokenSource()
    }
}

private class DefaultCancellationTokenSource : CancellationTokenSource {

    private val state: AtomicReference<State> = AtomicReference(State.NotCancelled)

    override val token: CancellationToken = TokenView()

    override fun cancel(reason: String?) {
        state.compareAndSet(State.NotCancelled, State.Cancelled(reason))
    }

    private inner class TokenView : CancellationToken {
        override val isCancellationRequested: Boolean
            get() = state.get() is State.Cancelled

        override val cancellationReason: String?
            get() = (state.get() as? State.Cancelled)?.reason

        override fun throwIfCancellationRequested() {
            val current = state.get()
            if (current is State.Cancelled) {
                throw OperationCancelledException(current.reason)
            }
        }
    }

    private sealed class State {
        data object NotCancelled : State()
        data class Cancelled(val reason: String?) : State()
    }
}
