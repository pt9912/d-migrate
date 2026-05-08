package dev.dmigrate.server.ports

import dev.dmigrate.server.core.ai.AiToolAcquireOutcome
import dev.dmigrate.server.core.ai.AiToolClaimId
import dev.dmigrate.server.core.ai.AiToolOutcome
import dev.dmigrate.server.core.ai.AiToolScope
import java.time.Duration
import java.time.Instant

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — durable Idempotency- und Outcome-Store
 * für synchrone KI-nahe Tool-Aufrufe.
 *
 * **Why a dedicated store** (statt
 * [SyncEffectIdempotencyStore]-Erweiterung): KI-Tools verteilen
 * `approvalKey` an Agent-Retry-Loops, die häufig parallel
 * identische Aufrufe absetzen. Der LF-010 / LF-013 / LN-009 / LN-011-Store gibt für solche
 * parallele Pending-Reserves erneut `Reserved` zurück (siehe
 * `InProcessUploadControlStores.kt:51`) — was bei KI-Tools doppelte
 * Provider-Kosten und doppelte Artefakt-Publishes erzeugt. Der
 * KI-Store löst das mit einer Single-Writer-Lease + Reclaim:
 *
 * - parallele gleiche Aufrufe sehen `InProgress` (nicht
 *   `Acquired`),
 * - eine abgelaufene Lease wird auf den nächsten Acquire hin
 *   reclaimable in [AiToolOutcome.FailedRetryable] umgewandelt,
 * - terminale Outcomes werden für jede weitere Anfrage 1:1
 *   replayt (kein Provider-Aufruf).
 *
 * Vertrag pro Methode:
 *
 * - [acquire] ist atomar pro `(scope, payloadFingerprint)`.
 * - [commit] erfolgt nur, wenn der Caller den passenden
 *   [AiToolClaimId] aus seinem [AiToolAcquireOutcome.Acquired]
 *   liefert. Outcome ohne `Pending`-Vorgänger wird abgelehnt.
 * - [reclaimExpired] ist sweeper-fähig: idempotent, läuft
 *   periodisch und transitioniert abgelaufene Pending-Claims in
 *   `FailedRetryable`-Form, sodass der nächste Acquire eine neue
 *   Lease ausstellen darf.
 */
interface AiToolOutcomeStore {

    /**
     * Versucht, einen Single-Writer-Claim für [scope] +
     * [payloadFingerprint] zu erwerben. Lebenszyklus-Logik wie in
     * der [AiToolAcquireOutcome]-KDoc-Tabelle.
     */
    fun acquire(
        scope: AiToolScope,
        payloadFingerprint: String,
        leaseDuration: Duration,
        now: Instant,
    ): AiToolAcquireOutcome

    /**
     * Schließt einen offenen [AiToolOutcome.Pending]-Claim mit
     * dem terminalen oder retryable [outcome] ab.
     *
     * - [outcome] muss [AiToolOutcome.Succeeded],
     *   [AiToolOutcome.FailedTerminal] oder
     *   [AiToolOutcome.FailedRetryable] sein. `Pending` als Wert
     *   ist nicht erlaubt — `commit` ist immer ein Übergang in
     *   einen anderen Status.
     * - `outcome.scope` und `outcome.payloadFingerprint` müssen
     *   mit dem ursprünglichen Pending-Claim übereinstimmen.
     * - Wird ein abweichender `claimId` geliefert (z.B. weil
     *   `reclaimExpired` die Lease bereits an einen neuen Caller
     *   übergeben hat), liefert die Methode `false` und der
     *   Caller verwirft sein Ergebnis.
     *
     * @return `true` wenn der Commit angekommen ist, sonst `false`.
     */
    fun commit(
        scope: AiToolScope,
        claimId: AiToolClaimId,
        outcome: AiToolOutcome,
        now: Instant,
    ): Boolean

    /**
     * Sweeper: transformiert alle [AiToolOutcome.Pending]-Einträge
     * mit `leaseExpiresAt <= now` in
     * [AiToolOutcome.FailedRetryable] mit
     * `toolErrorCode=OPERATION_TIMEOUT`. Idempotent.
     *
     * @return Anzahl der reclaimed Einträge.
     */
    fun reclaimExpired(now: Instant): Int
}
