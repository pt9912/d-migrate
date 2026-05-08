package dev.dmigrate.server.application.job

/**
 * Reason-Codes fuer den `RATE_LIMITED`-Wire-Envelope (Plan § 3.5 + § 6.4
 * in `ImpPlan-0.9.6-E3.md`, § 10 Q5).
 *
 * `reason` ist immer im Wire ausgegeben — auch fuer den bestehenden
 * Quota-Pfad — damit Operations zwischen Tenant-Quota und Pool-Saturation
 * unterscheidet, ohne Log-Korrelation. Default fuer beide RateLimited-
 * DTOs ist [ACTIVE_JOBS_QUOTA] (rueckwaertskompatibel zur Phase-E
 * Bestands-Wiring); der Phase-E3 Admission-Pfad setzt explizit
 * [EXECUTOR_SATURATED].
 */
object JobStartReason {
    const val ACTIVE_JOBS_QUOTA: String = "ACTIVE_JOBS_QUOTA"
    const val EXECUTOR_SATURATED: String = "EXECUTOR_SATURATED"
}
