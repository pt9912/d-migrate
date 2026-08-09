package dev.dmigrate.server.persistence.jdbc.idempotency

import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.idempotency.IdempotencyState
import java.time.Instant

/**
 * Gelesener Stand einer `idempotency_reservations`-Zeile.
 *
 * Traegt bewusst nur Daten und keine `Connection`: damit ist die
 * Entscheidung, die auf ihr fusst, ohne Datenbank pruefbar.
 */
internal data class ReservationRow(
    val state: IdempotencyState,
    val claimed: Boolean,
    val fingerprint: String,
    val expiresAt: Instant,
    val resultRef: String?,
    val challengeJson: String?,
    val reason: String?,
)

/**
 * Was auf eine gelesene Zeile hin zu tun ist — beschrieben, nicht getan.
 */
internal sealed interface ReserveDecision {

    /** Das Ergebnis steht fest, es ist nichts zu schreiben. */
    data class Complete(val outcome: IdempotencyReserveOutcome) : ReserveDecision

    /**
     * Die Reservierung ist abgelaufen und wird uebernommen. Der Aufrufer
     * fuehrt die Recovery-CAS aus; erst deren Ergebnis entscheidet, ob der
     * Zugriff gewonnen wurde.
     */
    data object RecoverExpired : ReserveDecision
}

/**
 * Entscheidet ueber eine bestehende Reservierung, ohne sie anzufassen.
 *
 * Ein abweichender Fingerprint schlaegt jeden Zustand: derselbe Schluessel mit
 * anderem Payload ist ein Konflikt, kein Wiederaufsetzen.
 */
internal fun decideReserve(
    scope: IdempotencyScope,
    fingerprint: String,
    now: Instant,
    existing: ReservationRow,
): ReserveDecision {
    if (existing.fingerprint != fingerprint) {
        return ReserveDecision.Complete(
            IdempotencyReserveOutcome.Conflict(scope, existing.fingerprint),
        )
    }
    return when (existing.state) {
        IdempotencyState.COMMITTED -> ReserveDecision.Complete(
            IdempotencyReserveOutcome.Committed(scope, existing.resultRef!!),
        )

        IdempotencyState.DENIED -> ReserveDecision.Complete(
            IdempotencyReserveOutcome.Denied(scope, existing.expiresAt, existing.reason!!),
        )

        IdempotencyState.FAILED -> ReserveDecision.Complete(
            IdempotencyReserveOutcome.Failed(scope, existing.expiresAt, existing.reason!!),
        )

        IdempotencyState.PENDING ->
            if (existing.isLive(now)) {
                ReserveDecision.Complete(
                    IdempotencyReserveOutcome.ExistingPending(scope, existing.expiresAt),
                )
            } else {
                ReserveDecision.RecoverExpired
            }

        IdempotencyState.AWAITING_APPROVAL ->
            if (existing.isLive(now)) {
                ReserveDecision.Complete(
                    IdempotencyReserveOutcome.AwaitingApproval(
                        scope,
                        existing.expiresAt,
                        existing.challengeJson?.let { ApprovalChallengeJson.fromJson(it) },
                    ),
                )
            } else {
                ReserveDecision.RecoverExpired
            }
    }
}

private fun ReservationRow.isLive(now: Instant): Boolean = expiresAt.isAfter(now)
