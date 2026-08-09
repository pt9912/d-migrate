package dev.dmigrate.server.persistence.jdbc.idempotency

import dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.idempotency.IdempotencyState
import dev.dmigrate.server.core.idempotency.InitResumeOutcome
import dev.dmigrate.server.core.idempotency.InitResumeScope
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

/**
 * Was auf einen Claim-Versuch hin zu tun ist — beschrieben, nicht getan.
 */
internal sealed interface ClaimDecision {

    /** Das Ergebnis steht fest, es ist nichts zu schreiben. */
    data class Complete(val outcome: IdempotencyClaimOutcome) : ClaimDecision

    /**
     * Die Freigabe ist gueltig und wird eingeloest. Der Aufrufer fuehrt die
     * Claim-CAS aus.
     */
    data object TransitionToClaimed : ClaimDecision
}

/**
 * Entscheidet ueber einen Claim-Versuch, ohne die Reservierung anzufassen.
 *
 * [existing] ist `null`, wenn es keine Zeile gibt — behandelt wie jeder andere
 * Zustand, aus dem heraus nicht geclaimt werden kann.
 */
internal fun decideClaim(
    scope: IdempotencyScope,
    now: Instant,
    existing: ReservationRow?,
): ClaimDecision {
    val row = existing
        ?: return ClaimDecision.Complete(IdempotencyClaimOutcome.NotAwaitingApproval(scope))

    return when (row.state) {
        IdempotencyState.COMMITTED -> ClaimDecision.Complete(
            IdempotencyClaimOutcome.Committed(scope, row.resultRef!!),
        )

        IdempotencyState.DENIED -> ClaimDecision.Complete(
            IdempotencyClaimOutcome.Denied(scope, row.expiresAt, row.reason!!),
        )

        // Nur eine bereits geclaimte PENDING-Zeile ist ein wiederholter Claim;
        // eine ungeclaimte war nie in Freigabe.
        IdempotencyState.PENDING -> ClaimDecision.Complete(
            if (row.claimed) {
                IdempotencyClaimOutcome.AlreadyClaimed(scope, row.expiresAt)
            } else {
                IdempotencyClaimOutcome.NotAwaitingApproval(scope)
            },
        )

        IdempotencyState.AWAITING_APPROVAL ->
            if (row.isLive(now)) {
                ClaimDecision.TransitionToClaimed
            } else {
                ClaimDecision.Complete(IdempotencyClaimOutcome.NotAwaitingApproval(scope))
            }

        IdempotencyState.FAILED -> ClaimDecision.Complete(
            IdempotencyClaimOutcome.NotAwaitingApproval(scope),
        )
    }
}

/**
 * Gelesener Stand einer `init_resume_reservations`-Zeile.
 *
 * [fingerprint] ist `null`, wenn die Zeile aus dem `RETURNING` eines Inserts
 * stammt — dort wird die Spalte nicht zurueckgelesen, weil sie gerade erst
 * geschrieben wurde.
 */
internal data class InitResumeRow(
    val sessionId: String,
    val fingerprint: String?,
    val expiresAt: Instant,
)

/**
 * Entscheidet ueber eine bestehende Init-Resume-Reservierung.
 *
 * Anders als bei [decideReserve] gibt es hier keine Zustaende und keine Leases:
 * entweder derselbe Payload (dann dieselbe Session), oder ein anderer (Konflikt).
 */
internal fun decideInitResume(
    scope: InitResumeScope,
    fingerprint: String,
    existing: InitResumeRow,
): InitResumeOutcome =
    if (existing.fingerprint != fingerprint) {
        InitResumeOutcome.Conflict(scope, existing.fingerprint!!)
    } else {
        InitResumeOutcome.Existing(scope, existing.sessionId, existing.expiresAt)
    }

/**
 * Ablaufzeitpunkt eines terminalen Zustands (committed / denied / failed).
 *
 * Eine vom Aufrufer gewuenschte [retentionUntil] gewinnt nur, wenn sie **spaeter**
 * liegt als der Default — sie kann die Aufbewahrung verlaengern, aber nicht
 * verkuerzen.
 */
internal fun terminalExpiry(
    now: Instant,
    defaultSeconds: Long,
    retentionUntil: Instant?,
): Instant {
    val defaultExpiresAt = now.plusSeconds(defaultSeconds)
    return if (retentionUntil != null && retentionUntil.isAfter(defaultExpiresAt)) {
        retentionUntil
    } else {
        defaultExpiresAt
    }
}

private fun ReservationRow.isLive(now: Instant): Boolean = expiresAt.isAfter(now)
