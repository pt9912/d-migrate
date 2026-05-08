package dev.dmigrate.server.application.quota

import java.time.Instant

/**
 * LF-012 / LN-011 / LN-017 / LN-027: persistente Reservation-Owner-
 * Bindung fuer Crash-Recovery zwischen `quota.reserve` und
 * `JobStartTransaction.commit`.
 *
 * Der [ownerId] korreliert mit dem JobStart-/Idempotency-Kontext
 * (typischerweise `${tenantId}:${callerId}:${toolName}:${idempotencyKey}`),
 * sodass ein nach `reserve` aber vor `commit` abgestuerzter Server
 * den Eintrag wiederfindet. [leaseExpiresAt] grenzt das Sweeper-
 * Refund-Fenster ein: nach Ablauf und ohne `COMMITTED` refunded der
 * [QuotaReservationSweeper] genau einmal und setzt [status] auf
 * [QuotaReservationStatus.REFUNDED].
 */
data class QuotaReservationOwner(
    val ownerId: String,
    val reservation: QuotaReservation,
    val status: QuotaReservationStatus,
    val leaseExpiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Lifecycle-Status einer persistenten Quota-Reservierung.
 *
 * Uebergaenge (LF-012 / LN-011 / LN-017 / LN-027):
 *
 * ```
 * PENDING --(JobStartTransaction.commit)--> COMMITTED
 * PENDING --(Lease abgelaufen, Sweeper)----> REFUNDED
 * PENDING --(pre-commit Fehler)------------> REFUNDED
 * COMMITTED --(succeeded/failed/cancelled)-> RELEASED
 * ```
 *
 * Terminale Stati [REFUNDED] und [RELEASED] sind absorbierend — der
 * Owner-Store darf einen Eintrag nach Erreichen NICHT zurueckwechseln.
 */
enum class QuotaReservationStatus {
    PENDING,
    COMMITTED,
    RELEASED,
    REFUNDED,
}
