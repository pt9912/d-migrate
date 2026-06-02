package dev.dmigrate.server.ports

import dev.dmigrate.server.core.upload.AbortOutcome

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — strukturiertes Outcome-Repository
 * fuer administrative / fremde `artifact_upload_abort`-Aufrufe.
 *
 * Indiziert ueber den von [SyncEffectIdempotencyStore] vergebenen
 * `resultRef`-String, der beim Abort-Commit zurueckgegeben wird.
 * LF-012 / LN-011 / LN-017 / LN-027: "Existiert bereits SyncEffectReserveOutcome.Existing(
 * resultRef), wird resultRef in einem `AbortOutcomeStore` auf einen
 * strukturierten Abort-Outcome-Record aufgeloest."
 *
 * Der Store ist ResultRef-keyed (nicht sessionId-keyed), weil derselbe
 * `(tenant, caller, toolName, approvalKey)`-SyncEffect-Scope mehrere
 * Pre-Abort-Zustaende derselben Session umfassen kann (z.B. nach
 * Reclaim oder Crash) und der Plan fordert, dass nur ein
 * resultRef-eindeutiger Abort-Outcome zurueckgegeben wird.
 *
 * Implementoren MUESSEN den `findByResultRef`-Lookup atomisch + tenant-
 * scoped halten und sicherstellen, dass `save` eine bestehende
 * `resultRef`-Kollision deterministisch via [SaveOutcome.Conflict]
 * meldet (statt stillschweigend zu ueberschreiben — der Plan
 * verlangt Replay-Konsistenz fuer denselben resultRef).
 */
interface AbortOutcomeStore {

    fun save(resultRef: String, outcome: AbortOutcome): SaveOutcome

    fun findByResultRef(resultRef: String): AbortOutcome?

    sealed interface SaveOutcome {
        data class Stored(val resultRef: String, val outcome: AbortOutcome) : SaveOutcome

        /**
         * Identischer `resultRef` mit identischem [AbortOutcome.abortFingerprint] —
         * No-Op-Replay (gleiche Identitaet, gleiches Outcome).
         */
        data class AlreadyStored(val existing: AbortOutcome) : SaveOutcome

        /**
         * Identischer `resultRef`, aber abweichender Fingerprint —
         * LF-012 / LN-011 / LN-017 / LN-027 "abweichende Request-Felder liefern
         * IDEMPOTENCY_CONFLICT". Der Caller darf den alten Outcome
         * NICHT zurueckgeben.
         */
        data class Conflict(
            val existingFingerprint: String,
            val attemptedFingerprint: String,
        ) : SaveOutcome
    }
}
