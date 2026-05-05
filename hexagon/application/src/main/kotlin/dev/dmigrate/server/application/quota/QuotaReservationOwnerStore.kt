package dev.dmigrate.server.application.quota

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase E §7.9 Persistent-Owner-Tracking-Store fuer Quota-Reservierungen.
 *
 * Der Store haelt fuer jeden Owner genau einen Eintrag. Status-Uebergaenge
 * sind absorbierend ueber [QuotaReservationStatus] (kein Zurueck aus
 * REFUNDED/RELEASED). Implementierungen muessen Status-Wechsel atomar
 * relativ zum [ownerId] durchfuehren — der [QuotaReservationSweeper]
 * verlaesst sich auf das exactly-once-Refund-Verhalten von
 * [markRefunded].
 *
 * Production-Adapter persistieren in der Datenbank; die hier mitgelieferte
 * [InMemoryQuotaReservationOwnerStore] reicht fuer Single-Process-Boot
 * + Tests.
 */
interface QuotaReservationOwnerStore {

    /**
     * Registriert eine frische Reservierung mit Status [QuotaReservationStatus.PENDING].
     * Wenn ein Eintrag fuer [ownerId] bereits existiert, MUSS die
     * Implementierung einen Fehler werfen — Doppel-Reserve fuer denselben
     * Owner ist ein Caller-Bug.
     */
    fun register(
        ownerId: String,
        reservation: QuotaReservation,
        leaseExpiresAt: Instant,
        now: Instant,
    ): QuotaReservationOwner

    /**
     * `PENDING -> COMMITTED`. Nur erlaubt von PENDING; sonst no-op
     * (`null`). Ein erfolgreicher Aufruf signalisiert, dass der zugehoerige
     * Job erfolgreich committed wurde — der Sweeper darf jetzt nicht
     * mehr refunden.
     */
    fun markCommitted(ownerId: String, now: Instant): QuotaReservationOwner?

    /**
     * `COMMITTED -> RELEASED`. Wird vom Dispatcher gerufen bei
     * `succeeded`/`failed`/`cancelled`. Nur erlaubt von COMMITTED; sonst
     * no-op.
     */
    fun markReleased(ownerId: String, now: Instant): QuotaReservationOwner?

    /**
     * `PENDING -> REFUNDED`. Vom [QuotaReservationSweeper] aufgerufen,
     * nachdem die Lease abgelaufen ist; oder vom Orchestrator bei pre-
     * commit Fehlern. Nur erlaubt von PENDING; sonst no-op.
     *
     * Der Sweeper relies auf das `null`-Return als Indikator, dass der
     * Eintrag bereits in einem terminalen Status war (committed oder
     * schon refunded), und darf in dem Fall NICHT zusaetzlich
     * `quotaService.refund(...)` rufen.
     */
    fun markRefunded(ownerId: String, now: Instant): QuotaReservationOwner?

    fun findById(ownerId: String): QuotaReservationOwner?

    /**
     * Listet alle Eintraege mit `status = PENDING` und
     * `leaseExpiresAt <= now` — also abgelaufene Reservierungen, die
     * der Sweeper refunden muss.
     */
    fun listExpiredPending(now: Instant): List<QuotaReservationOwner>
}

/**
 * In-Memory-Implementation fuer Tests + Single-Process-Boot. ConcurrentHashMap +
 * `compute`-CAS fuer atomare Status-Uebergaenge.
 */
class InMemoryQuotaReservationOwnerStore : QuotaReservationOwnerStore {

    private val entries = ConcurrentHashMap<String, QuotaReservationOwner>()

    override fun register(
        ownerId: String,
        reservation: QuotaReservation,
        leaseExpiresAt: Instant,
        now: Instant,
    ): QuotaReservationOwner {
        val owner = QuotaReservationOwner(
            ownerId = ownerId,
            reservation = reservation,
            status = QuotaReservationStatus.PENDING,
            leaseExpiresAt = leaseExpiresAt,
            createdAt = now,
            updatedAt = now,
        )
        val previous = entries.putIfAbsent(ownerId, owner)
        require(previous == null) {
            "QuotaReservationOwnerStore: ownerId $ownerId already registered"
        }
        return owner
    }

    override fun markCommitted(ownerId: String, now: Instant): QuotaReservationOwner? =
        transitionFromPending(ownerId, now, QuotaReservationStatus.COMMITTED)

    override fun markReleased(ownerId: String, now: Instant): QuotaReservationOwner? {
        var result: QuotaReservationOwner? = null
        entries.computeIfPresent(ownerId) { _, current ->
            if (current.status != QuotaReservationStatus.COMMITTED) {
                current
            } else {
                val updated = current.copy(status = QuotaReservationStatus.RELEASED, updatedAt = now)
                result = updated
                updated
            }
        }
        return result
    }

    override fun markRefunded(ownerId: String, now: Instant): QuotaReservationOwner? =
        transitionFromPending(ownerId, now, QuotaReservationStatus.REFUNDED)

    override fun findById(ownerId: String): QuotaReservationOwner? = entries[ownerId]

    override fun listExpiredPending(now: Instant): List<QuotaReservationOwner> =
        entries.values
            .filter { it.status == QuotaReservationStatus.PENDING && !it.leaseExpiresAt.isAfter(now) }
            .sortedBy { it.leaseExpiresAt }

    private fun transitionFromPending(
        ownerId: String,
        now: Instant,
        target: QuotaReservationStatus,
    ): QuotaReservationOwner? {
        var result: QuotaReservationOwner? = null
        entries.computeIfPresent(ownerId) { _, current ->
            if (current.status != QuotaReservationStatus.PENDING) {
                current
            } else {
                val updated = current.copy(status = target, updatedAt = now)
                result = updated
                updated
            }
        }
        return result
    }
}
