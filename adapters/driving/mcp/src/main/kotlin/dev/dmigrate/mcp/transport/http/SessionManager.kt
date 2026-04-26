package dev.dmigrate.mcp.transport.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory session registry per `ImpPlan-0.9.6-B.md` §12.5 / §12.13.
 *
 * Sessions are keyed by UUID v4 and live in a `ConcurrentHashMap`.
 * `get` updates `lastSeen` on every hit so an active client never
 * triggers the idle timeout.
 *
 * The TTL reaper is a daemon coroutine running on
 * [Dispatchers.Default]. It sweeps every [sweepInterval] and evicts
 * entries whose `lastSeen + idleTimeout` is in the past. [close]
 * cancels the reaper and clears the map; the manager is single-shot
 * (no restart).
 */
class SessionManager(
    private val idleTimeout: Duration,
    private val sweepInterval: Duration = Duration.ofSeconds(60),
    private val clock: () -> Instant = Instant::now,
) : AutoCloseable {

    private val sessions = ConcurrentHashMap<UUID, SessionState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (isActive) {
                delay(sweepInterval.toMillis())
                evictExpired()
            }
        }
    }

    fun create(state: SessionState): UUID {
        val id = UUID.randomUUID()
        sessions[id] = state
        return id
    }

    /**
     * Lookup without side effects. The route uses this for
     * pre-dispatch validation (Session-Id and Protocol-Version) so
     * a client spamming bad headers cannot extend its own TTL by
     * keeping `lastSeen` fresh on each rejected request. Once the
     * request is accepted (validation passed), call [touch].
     */
    fun peek(id: UUID): SessionState? = sessions[id]

    /** Marks the session as recently seen. No-op if the id is unknown. */
    fun touch(id: UUID) {
        sessions[id]?.lastSeen = clock()
    }

    /** Returns true if the session existed and was removed. */
    fun remove(id: UUID): Boolean = sessions.remove(id) != null

    /** Eviction sweep. Public for deterministic tests. */
    fun evictExpired() {
        val now = clock()
        sessions.entries.removeIf { (_, state) ->
            now.isAfter(state.lastSeen.plus(idleTimeout))
        }
    }

    fun size(): Int = sessions.size

    override fun close() {
        scope.cancel()
        sessions.clear()
    }
}
