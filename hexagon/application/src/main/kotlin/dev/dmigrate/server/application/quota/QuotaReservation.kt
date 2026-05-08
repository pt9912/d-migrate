package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome

/**
 * Token returned by [QuotaService.reserve] on the success path. Callers
 * pass it back to `commit`/`release`/`refund` so the lifecycle calls
 * always carry the same `(key, amount)` pair the reserve used.
 */
data class QuotaReservation(val key: QuotaKey, val amount: Long) {
    companion object {
        fun of(granted: QuotaOutcome.Granted): QuotaReservation =
            QuotaReservation(granted.key, granted.amount)
    }
}
