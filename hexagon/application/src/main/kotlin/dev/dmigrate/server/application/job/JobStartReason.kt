package dev.dmigrate.server.application.job

/**
 * Reason-Codes fuer den `RATE_LIMITED`-Wire-Envelope (LF-012 / LN-011 / LN-017 / LN-027
 * in `LF-012 / LN-011 / LN-017 / LN-027`, § 10 Q5).
 *
 * `reason` ist immer im Wire ausgegeben — auch fuer den bestehenden
 * Quota-Pfad — damit Operations zwischen Tenant-Quota und Pool-Saturation
 * unterscheidet, ohne Log-Korrelation. Default fuer beide RateLimited-
 * DTOs ist [ACTIVE_JOBS_QUOTA] (rueckwaertskompatibel zur server-state
 * Bestands-Wiring); der server-state Admission-Pfad setzt explizit
 * [EXECUTOR_SATURATED].
 */
object JobStartReason {
    const val ACTIVE_JOBS_QUOTA: String = "ACTIVE_JOBS_QUOTA"
    const val EXECUTOR_SATURATED: String = "EXECUTOR_SATURATED"
}
