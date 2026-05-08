package dev.dmigrate.server.ports.quota

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Duration

enum class QuotaDimension {
    ACTIVE_JOBS,
    ACTIVE_UPLOAD_SESSIONS,
    UPLOAD_BYTES,
    PARALLEL_SEGMENT_WRITES,
    /**
     * LF-010 / LF-013 / LN-009 / LN-011: persistierte Artefakt-Bytes nach
     * erfolgreicher Finalisierung. Die [ACTIVE_UPLOAD_SESSIONS]-
     * und [UPLOAD_BYTES]-Reservierungen aus Init werden auf
     * `COMPLETED` freigegeben; die Bytes wandern in diese Dimension,
     * sodass ein Tenant nur fuer den durablen Bestand bezahlt (nicht
     * fuer in-flight + durabel doppelt). LF-012 / LN-011 / LN-017 / LN-027 wortlaeufig:
     * "COMPLETED bucht gespeicherte Artefaktbytes genau einmal und
     * gibt reservierte Upload-Bytes frei".
     */
    STORED_ARTIFACT_BYTES,
    PROVIDER_CALLS,
}

/**
 * Identifies a quota counter slot. The §4.7 plan demands "active jobs
 * per tenant/principal **and operation**", so [operation] participates
 * in the key alongside the optional [principalId]. `null` means
 * tenant-scoped (or tenant+principal-scoped) without per-operation
 * partitioning; non-null applies the limit per `(tenant, principal?,
 * operation)` triple.
 */
data class QuotaKey(
    val tenantId: TenantId,
    val dimension: QuotaDimension,
    val principalId: PrincipalId? = null,
    val operation: String? = null,
)

data class QuotaCounter(
    val key: QuotaKey,
    val current: Long,
    val limit: Long,
)

sealed interface QuotaOutcome {
    val key: QuotaKey
    val amount: Long

    data class Granted(
        override val key: QuotaKey,
        override val amount: Long,
        val newCurrent: Long,
        val limit: Long,
    ) : QuotaOutcome

    data class RateLimited(
        override val key: QuotaKey,
        override val amount: Long,
        val current: Long,
        val limit: Long,
        /**
         * LF-012 / LN-011 / LN-017 / LN-027 line 1294-1295: `RATE_LIMITED`-Details muessen
         * `retryAfter` enthalten. Fuer Window-Rate-Limits aus dem
         * naechsten Window-Reset; fuer aktive Jobquoten aus einem
         * konfigurierten Retry-Hint, weil Slot-Freigabe ereignisgetrieben
         * ist. Default ist [DEFAULT_ACTIVE_JOB_RETRY_AFTER] — Wert > 0,
         * damit das Idempotency-Lease auf maximal `now + retryAfter`
         * gesetzt werden kann (LF-012 / LN-011 / LN-017 / LN-027 line 1295).
         */
        val retryAfter: Duration = DEFAULT_ACTIVE_JOB_RETRY_AFTER,
    ) : QuotaOutcome

    companion object {
        /**
         * Default-`retryAfter` fuer aktive Jobquoten, wenn keine
         * spezifische Window-Reset-Zeit greift. 30 Sekunden ist ein
         * konservativer Hint; Bootstrap-Wiring kann ueber den
         * QuotaService-Pfad einen anderen Wert injizieren.
         */
        val DEFAULT_ACTIVE_JOB_RETRY_AFTER: Duration = Duration.ofSeconds(30)
    }
}

/**
 * Tracks raw quota counters. The application-layer `QuotaService` (LF-012 / LN-027 / LN-028 / LN-038)
 * wraps reserve/commit/release/refund semantics on top of these primitives.
 */
interface QuotaStore {

    fun reserve(key: QuotaKey, amount: Long, limit: Long): QuotaOutcome

    fun release(key: QuotaKey, amount: Long): Long

    fun current(key: QuotaKey): Long
}
