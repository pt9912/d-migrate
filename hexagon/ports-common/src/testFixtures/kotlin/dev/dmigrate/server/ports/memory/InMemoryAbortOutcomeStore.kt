package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.upload.AbortOutcome
import dev.dmigrate.server.ports.AbortOutcomeStore
import dev.dmigrate.server.ports.AbortOutcomeStore.SaveOutcome
import java.util.concurrent.ConcurrentHashMap

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — In-Memory-Backing fuer
 * [AbortOutcomeStore] hinter dem Test/Dev-Wiring der LF-010 / LF-013 / LN-009 / LN-011-
 * Adapter. Atomare Save-Semantik via `ConcurrentHashMap.compute`
 * gewaehrleistet, dass parallele `(resultRef, fingerprint)`-Requests
 * deterministisch in `Stored | AlreadyStored | Conflict` aufloesen.
 */
class InMemoryAbortOutcomeStore : AbortOutcomeStore {

    private val entries = ConcurrentHashMap<String, AbortOutcome>()

    override fun save(resultRef: String, outcome: AbortOutcome): SaveOutcome {
        var result: SaveOutcome? = null
        entries.compute(resultRef) { _, existing ->
            when {
                existing == null -> {
                    result = SaveOutcome.Stored(resultRef, outcome)
                    outcome
                }
                existing.abortFingerprint == outcome.abortFingerprint -> {
                    result = SaveOutcome.AlreadyStored(existing)
                    existing
                }
                else -> {
                    result = SaveOutcome.Conflict(
                        existingFingerprint = existing.abortFingerprint,
                        attemptedFingerprint = outcome.abortFingerprint,
                    )
                    existing
                }
            }
        }
        return result!!
    }

    override fun findByResultRef(resultRef: String): AbortOutcome? = entries[resultRef]
}
