package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.time.Instant

/**
 * Phase E §7.9 ownership-aware Composite ueber dem bestehenden
 * [QuotaService]. Verbindet `reserve` mit dem
 * [QuotaReservationOwnerStore], sodass:
 *
 * - jede erfolgreiche Reservierung mit `(ownerId, leaseExpiresAt)`
 *   persistiert wird (Plan §7.9 line 1280-1281),
 * - `commitForOwner`/`releaseForOwner`/`refundForOwner` sowohl den
 *   Counter im [QuotaService] aktualisieren als auch den Owner-Status,
 * - der [QuotaReservationSweeper] orphane Eintraege ueber den Owner-
 *   Store findet und exactly-once-refunded.
 *
 * Backward-compat: das underlying [QuotaService]-Interface bleibt
 * unveraendert. Caller, die kein Owner-Tracking brauchen (z.B. Phase-C
 * Upload-Slots), nutzen weiterhin den simpel-Pfad direkt.
 */
class OwnerAwareQuotaService(
    private val delegate: QuotaService,
    private val ownerStore: QuotaReservationOwnerStore,
) {

    /**
     * Reserviert + registriert den Owner. Bei [QuotaOutcome.RateLimited]
     * wird KEIN Owner-Eintrag angelegt — der Caller bekommt die
     * RateLimited-Antwort ohne side-effect am Owner-Store.
     */
    fun reserve(
        key: QuotaKey,
        amount: Long,
        ownerId: String,
        leaseExpiresAt: Instant,
        now: Instant,
    ): QuotaOutcome {
        val outcome = delegate.reserve(key, amount)
        if (outcome is QuotaOutcome.Granted) {
            ownerStore.register(
                ownerId = ownerId,
                reservation = QuotaReservation.of(outcome),
                leaseExpiresAt = leaseExpiresAt,
                now = now,
            )
        }
        return outcome
    }

    /**
     * Markiert den Owner als COMMITTED — der Slot bleibt belegt, der
     * Sweeper darf nicht mehr refunden. Plan §7.9 line 1278:
     * `commit` nur nach erfolgreichem Job-Commit.
     *
     * Wenn kein Owner-Eintrag existiert (z.B. weil das vorherige
     * `reserve` ohne Owner-Tracking lief), ist der Aufruf no-op.
     */
    fun commitForOwner(ownerId: String, now: Instant) {
        val owner = ownerStore.findById(ownerId) ?: return
        delegate.commit(owner.reservation)
        ownerStore.markCommitted(ownerId, now)
    }

    /**
     * Plan §7.9 line 1291-1292: bei `succeeded`/`failed`/`cancelled`/
     * Runner-Timeout-Cleanup freigeben. Counter wird via [delegate]
     * dekrementiert; Owner-Status auf RELEASED.
     */
    fun releaseForOwner(ownerId: String, now: Instant) {
        val owner = ownerStore.findById(ownerId) ?: return
        delegate.release(owner.reservation)
        ownerStore.markReleased(ownerId, now)
    }

    /**
     * Plan §7.9 line 1282-1284: refund nur fuer Start-Timeouts und
     * technische Pre-Commit-Fehler des konkreten Pipeline-Owners.
     * Owner-Status auf REFUNDED — der Sweeper sieht den Eintrag dann
     * nicht mehr in `listExpiredPending`.
     */
    fun refundForOwner(ownerId: String, now: Instant) {
        val owner = ownerStore.findById(ownerId) ?: return
        if (owner.status != QuotaReservationStatus.PENDING) return
        delegate.refund(owner.reservation)
        ownerStore.markRefunded(ownerId, now)
    }
}
