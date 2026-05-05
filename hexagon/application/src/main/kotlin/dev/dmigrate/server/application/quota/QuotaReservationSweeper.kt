package dev.dmigrate.server.application.quota

import java.time.Clock

/**
 * Phase E §7.9 line 1285-1287 Sweeper fuer abgelaufene Quota-
 * Reservierungen.
 *
 * Findet PENDING-Eintraege im [QuotaReservationOwnerStore] mit
 * `leaseExpiresAt <= now`, refunded den Slot ueber [delegate] und
 * markiert den Owner als REFUNDED. Plan §7.9 line 1311 verlangt
 * exactly-once-Refund — der [QuotaReservationOwnerStore.markRefunded]-
 * CAS gibt `null` zurueck, wenn der Eintrag bereits in einem terminalen
 * Zustand war (z.B. weil parallel `markCommitted` lief), und dann
 * ueberspringt der Sweeper das Refund.
 *
 * Sweeper laeuft externgetrieben (z.B. ScheduledExecutor in einem
 * Bootstrap-Wiring); diese Klasse selbst kennt keinen Scheduler.
 */
class QuotaReservationSweeper(
    private val ownerStore: QuotaReservationOwnerStore,
    private val delegate: QuotaService,
    private val clock: Clock,
) {

    /**
     * Fuehrt einen Sweep aus und gibt die Anzahl tatsaechlich refundeter
     * Reservierungen zurueck. Aufrufer kann das fuer Telemetrie nutzen.
     */
    fun sweep(): Int {
        val now = clock.instant()
        val expired = ownerStore.listExpiredPending(now)
        var refunded = 0
        for (owner in expired) {
            // CAS-First: erst Owner-Status auf REFUNDED setzen. Wenn das
            // schief geht (Race mit markCommitted), NICHT refund rufen.
            val transitioned = ownerStore.markRefunded(owner.ownerId, now)
            if (transitioned != null) {
                delegate.refund(owner.reservation)
                refunded++
            }
        }
        return refunded
    }
}
