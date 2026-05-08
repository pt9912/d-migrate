package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.time.Instant

/**
 * LF-012 / LN-011 / LN-017 / LN-027 * [QuotaService]. Verbindet `reserve` mit dem
 * [QuotaReservationOwnerStore], sodass:
 *
 * - jede erfolgreiche Reservierung mit `(ownerId, leaseExpiresAt)`
 *   persistiert wird (LF-012 / LN-011 / LN-017 / LN-027),
 * - `commitForOwner`/`releaseForOwner`/`refundForOwner` sowohl den
 *   Counter im [QuotaService] aktualisieren als auch den Owner-Status,
 * - der [QuotaReservationSweeper] orphane Eintraege ueber den Owner-
 *   Store findet und exactly-once-refunded.
 *
 * Backward-compat: das underlying [QuotaService]-Interface bleibt
 * unveraendert. Caller, die kein Owner-Tracking brauchen (z.B. legacy
 * Upload-Slots), nutzen weiterhin den simpel-Pfad direkt.
 */
open class OwnerAwareQuotaService(
    private val delegate: QuotaService,
    private val ownerStore: QuotaReservationOwnerStore,
) {

    /**
     * Reserviert + registriert den Owner. Bei [QuotaOutcome.RateLimited]
     * wird KEIN Owner-Eintrag angelegt — der Caller bekommt die
     * RateLimited-Antwort ohne side-effect am Owner-Store.
     *
     * Review-Fix #4 (Atomicity): `synchronized(this)` umfasst beide
     * Schritte (delegate.reserve + ownerStore.register), sodass kein
     * Sweeper-/Concurrent-Reserve dazwischen einen partiellen State
     * sieht. Fuer JVM-Crash zwischen den Schritten gibt InMemory
     * keine Garantien (alles ist eh weg); persistente Backings
     * muessen ein gemeinsames Transaktions-Primitive bereitstellen
     * (siehe `JdbcOwnerAwareQuotaService`).
     *
     * Vollstaendige Atomicity-Vertrags-Beschreibung + Implementor-Guide:
     * `spec/phase-e-port-atomicity.md` Abschnitt (4).
     */
    open fun reserve(
        key: QuotaKey,
        amount: Long,
        ownerId: String,
        leaseExpiresAt: Instant,
        now: Instant,
    ): QuotaOutcome = synchronized(this) {
        val outcome = delegate.reserve(key, amount)
        if (outcome is QuotaOutcome.Granted) {
            ownerStore.register(
                ownerId = ownerId,
                reservation = QuotaReservation.of(outcome),
                leaseExpiresAt = leaseExpiresAt,
                now = now,
            )
        }
        outcome
    }

    /**
     * Markiert den Owner als COMMITTED — der Slot bleibt belegt, der
     * Sweeper darf nicht mehr refunden. LF-012 / LN-011 / LN-017 / LN-027:
     * `commit` nur nach erfolgreichem Job-Commit.
     *
     * Wenn kein Owner-Eintrag existiert (z.B. weil das vorherige
     * `reserve` ohne Owner-Tracking lief), ist der Aufruf no-op.
     */
    open fun commitForOwner(ownerId: String, now: Instant) {
        val owner = ownerStore.findById(ownerId) ?: return
        delegate.commit(owner.reservation)
        ownerStore.markCommitted(ownerId, now)
    }

    /**
     * LF-012 / LN-011 / LN-017 / LN-027: bei `succeeded`/`failed`/`cancelled`/
     * Runner-Timeout-Cleanup freigeben. Counter wird via [delegate]
     * dekrementiert; Owner-Status auf RELEASED.
     *
     * Review-Fix #5 (Double-Release-Race): markReleased ZUERST (CAS-
     * Gewinn), dann delegate.release nur wenn der CAS erfolgreich war.
     * Zwei concurrent Caller (z.B. Dispatcher + JobCancelService)
     * koennen beide findById=COMMITTED sehen, aber nur EINER gewinnt
     * markReleased. Der Verlierer bekommt null und uebergeht
     * delegate.release — kein doppelter Counter-Decrement.
     */
    open fun releaseForOwner(ownerId: String, now: Instant) {
        val transitioned = ownerStore.markReleased(ownerId, now) ?: return
        delegate.release(transitioned.reservation)
    }

    /**
     * LF-012 / LN-011 / LN-017 / LN-027: refund nur fuer Start-Timeouts und
     * technische Pre-Commit-Fehler des konkreten Pipeline-Owners.
     * Owner-Status auf REFUNDED — der Sweeper sieht den Eintrag dann
     * nicht mehr in `listExpiredPending`.
     *
     * Review-Fix #5 (Double-Refund-Race): symmetrisch zu
     * releaseForOwner — markRefunded zuerst, delegate.refund nur bei
     * CAS-Gewinn.
     */
    open fun refundForOwner(ownerId: String, now: Instant) {
        val transitioned = ownerStore.markRefunded(ownerId, now) ?: return
        delegate.refund(transitioned.reservation)
    }
}
