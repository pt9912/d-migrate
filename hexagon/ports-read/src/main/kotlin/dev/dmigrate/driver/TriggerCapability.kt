package dev.dmigrate.driver

/**
 * E.2 Trigger-Migration Sub-Slice A.2: per-dialect trigger capability.
 *
 * Mirrors [RoutineKindCapability] for the trigger-renderer path. The
 * `RoutineKind` selector is deliberately scoped to `FUNCTION` and
 * `PROCEDURE` (see `RoutineCapability.kt`), so triggers carry their
 * own capability value rather than overloading the routine capability.
 *
 * `minPostgresMajorVersion` matters only for PostgreSQL — `CREATE OR
 * REPLACE TRIGGER` is PG-14+. File-to-file targets pass
 * `postgresMajorVersion = null` and resolve to
 * [TriggerCapabilityResolution.Disabled] when a floor is declared:
 * without a known server version we conservatively assume the older
 * grammar. MySQL and SQLite never set a floor and always resolve to
 * `Disabled` regardless of `postgresMajorVersion`, because neither
 * dialect supports `CREATE OR REPLACE TRIGGER` at all.
 */
data class TriggerCapability(
    val enabled: Boolean,
    val minPostgresMajorVersion: Int? = null,
)

/**
 * Resolved trigger capability for a render-time decision.
 *
 * `InvalidConfig` is deliberately omitted — the trigger capability has
 * no operator-supplied configuration source in E.2; only the
 * dialect-default layer feeds it.
 */
sealed interface TriggerCapabilityResolution {
    data object Active : TriggerCapabilityResolution
    data object Disabled : TriggerCapabilityResolution
}

/**
 * Resolves a [TriggerCapability] against the live PostgreSQL major
 * version, if known.
 *
 * - File-to-file targets pass `postgresMajorVersion = null`; a
 *   capability that declares `minPostgresMajorVersion` resolves to
 *   [TriggerCapabilityResolution.Disabled] (no proof the server meets
 *   the threshold). A capability without a floor stays `Active`.
 * - File-to-DB targets pass the live PG major. Comparison is inclusive
 *   (`live >= minPostgresMajorVersion` ⇒ Active).
 */
fun TriggerCapability.resolve(postgresMajorVersion: Int?): TriggerCapabilityResolution {
    if (!enabled) return TriggerCapabilityResolution.Disabled
    val floor = minPostgresMajorVersion ?: return TriggerCapabilityResolution.Active
    if (postgresMajorVersion == null) return TriggerCapabilityResolution.Disabled
    return if (postgresMajorVersion >= floor) {
        TriggerCapabilityResolution.Active
    } else {
        TriggerCapabilityResolution.Disabled
    }
}
