package dev.dmigrate.driver

/**
 * E.1 Routine-Migration Slice C.1.a: per-routine-kind enablement and
 * optional MySQL server-version floor for `CREATE OR REPLACE`.
 *
 * Carried inside [EffectiveRoutineCapability.Valid]. The
 * 0.9.7-routine-capability-configurable-source carve-out wrapped the
 * earlier top-level `RoutineCapability` data class in the sealed
 * [EffectiveRoutineCapability] envelope; this file now hosts only the
 * per-kind value plus the resolver dispatch types.
 *
 * `minServerVersion` is only meaningful for MySQL targets; PostgreSQL
 * leaves it `null`.
 */
data class RoutineKindCapability(
    val enabled: Boolean,
    val minServerVersion: MysqlServerVersion? = null,
)

/**
 * Routine kind selector for [EffectiveRoutineCapability.Valid.forKind]
 * and the routine-renderer dispatch. Triggers are intentionally out of
 * scope here — they have their own renderer path (E.2).
 */
enum class RoutineKind {
    FUNCTION,
    PROCEDURE,
}

/**
 * Result of resolving a [RoutineKindCapability] against an optional
 * live server version. Renderers consume this and never the raw
 * capability:
 *
 * - [Active]: `enabled=true` AND either no `minServerVersion`
 *   declared OR the live server meets it.
 * - [Disabled]: `enabled=false`, OR `minServerVersion` declared and
 *   the live server is older / unknown.
 * - [InvalidConfig]: produced by the renderer when the upstream
 *   [EffectiveRoutineCapability] is [EffectiveRoutineCapability.Invalid].
 *   The renderer translates that into `ROUTINE_CAPABILITY_CONFIG_INVALID`
 *   manifest blocks.
 */
sealed interface RoutineCapabilityResolution {
    data object Active : RoutineCapabilityResolution
    data object Disabled : RoutineCapabilityResolution
    data object InvalidConfig : RoutineCapabilityResolution
}

/**
 * Resolves a [RoutineKindCapability] against the runtime context.
 *
 * - File-to-file targets pass `serverVersion = null`. A capability
 *   that declares a `minServerVersion` resolves to [RoutineCapabilityResolution.Disabled]
 *   in that case (no proof the server meets the threshold).
 * - File-to-DB targets pass the live server version. Comparison is
 *   inclusive (`live >= minServerVersion` ⇒ Active).
 */
fun RoutineKindCapability.resolve(serverVersion: MysqlServerVersion?): RoutineCapabilityResolution {
    if (!enabled) return RoutineCapabilityResolution.Disabled
    val floor = minServerVersion ?: return RoutineCapabilityResolution.Active
    if (serverVersion == null) return RoutineCapabilityResolution.Disabled
    return if (serverVersion >= floor) {
        RoutineCapabilityResolution.Active
    } else {
        RoutineCapabilityResolution.Disabled
    }
}
