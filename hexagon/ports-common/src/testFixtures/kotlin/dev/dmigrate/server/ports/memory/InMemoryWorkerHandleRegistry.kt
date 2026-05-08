package dev.dmigrate.server.ports.memory

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.server.ports.SignalOutcome
import dev.dmigrate.server.ports.WorkerHandleRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local in-memory implementation of [WorkerHandleRegistry].
 * Stateful but not durable — entries vanish on process restart, which
 * matches the runtime-only contract documented on the interface.
 */
class InMemoryWorkerHandleRegistry : WorkerHandleRegistry {

    private val sources = ConcurrentHashMap<String, CancellationTokenSource>()

    override fun register(jobId: String, source: CancellationTokenSource) {
        sources[jobId] = source
    }

    override fun signal(jobId: String, reason: String?): SignalOutcome {
        val source = sources[jobId] ?: return SignalOutcome.NotFound
        source.cancel(reason)
        return SignalOutcome.Signaled
    }

    override fun unregister(jobId: String) {
        sources.remove(jobId)
    }
}
