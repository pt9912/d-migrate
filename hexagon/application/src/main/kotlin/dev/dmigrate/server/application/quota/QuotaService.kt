package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import dev.dmigrate.server.ports.quota.QuotaStore

/**
 * Application-layer wrapper around [QuotaStore] that adds the
 * reserve/commit/release/refund lifecycle (`ImpPlan-0.9.6-A.md` §6.6).
 *
 * The handler pattern is:
 *   1. `val outcome = service.reserve(key, amount)` — short-circuit on
 *      `RateLimited`.
 *   2. on success-path completion: `service.commit(reservation)` (audit
 *      hook only — counter stays incremented).
 *   3. on terminal/abort/expiry/finalize: `service.release(reservation)`.
 *   4. on error or idempotency-replay: `service.refund(reservation)`.
 *
 * Idempotency interaction is the caller's responsibility:
 * `QuotaService` knows nothing about idempotency. Handlers must skip
 * `reserve` on `ExistingPending`/`Committed` and the matching
 * `release`/`refund` accordingly.
 */
interface QuotaService {

    fun reserve(key: QuotaKey, amount: Long): QuotaOutcome

    fun commit(reservation: QuotaReservation)

    fun release(reservation: QuotaReservation)

    fun refund(reservation: QuotaReservation)
}

class DefaultQuotaService(
    private val store: QuotaStore,
    private val limitFor: (QuotaKey) -> Long,
) : QuotaService {

    override fun reserve(key: QuotaKey, amount: Long): QuotaOutcome =
        store.reserve(key, amount, limitFor(key))

    override fun commit(reservation: QuotaReservation) {
        // Phase A: counter stays at the reserved level. The audit sink
        // (AP 6.8) will hook in here so the success-path is observable.
    }

    override fun release(reservation: QuotaReservation) {
        store.release(reservation.key, reservation.amount)
    }

    override fun refund(reservation: QuotaReservation) {
        store.release(reservation.key, reservation.amount)
    }
}
