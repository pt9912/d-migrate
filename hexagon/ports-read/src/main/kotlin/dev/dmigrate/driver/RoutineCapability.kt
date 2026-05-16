package dev.dmigrate.driver

/**
 * E.1 Routine-Migration Slice C.1.a: dialect-aware capability
 * contract for `CREATE OR REPLACE FUNCTION` / `CREATE OR REPLACE
 * PROCEDURE`.
 *
 * The capability is split by routine kind so a dialect can advertise
 * different support for functions vs procedures (e.g. older MySQL
 * server versions support `CREATE OR REPLACE FUNCTION` but not
 * `CREATE OR REPLACE PROCEDURE`).
 *
 * Both kind fields are non-nullable. A "missing mapping" cannot
 * occur because the per-dialect default is always provided via
 * [RoutineCapabilityDefaults.forDialect]; the resolver therefore
 * has three states, not four (see [RoutineCapabilityResolution]).
 *
 * Lives in `hexagon:ports-read` (not `hexagon:core`) because
 * `MysqlServerVersion?` references the MySQL-flavoured version type
 * also hosted in ports-read, and `hexagon:core` cannot import
 * ports-read by design (`ZERO external dependencies`).
 */
data class RoutineCapability(
    val function: RoutineKindCapability,
    val procedure: RoutineKindCapability,
) {
    fun forKind(kind: RoutineKind): RoutineKindCapability = when (kind) {
        RoutineKind.FUNCTION -> function
        RoutineKind.PROCEDURE -> procedure
    }
}

/**
 * Per-routine-kind enablement and optional MySQL server-version
 * floor. `minServerVersion` is only meaningful for MySQL targets;
 * PostgreSQL leaves it `null`.
 */
data class RoutineKindCapability(
    val enabled: Boolean,
    val minServerVersion: MysqlServerVersion? = null,
)

/**
 * Routine kind selector for [RoutineCapability.forKind] and the
 * routine-renderer dispatch. Triggers are intentionally out of
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
 * - [InvalidConfig]: the capability source is structurally broken
 *   (unparsable, contradictory). Slice C.1.a has no configurable
 *   source, so production code never returns this; renderer tests
 *   construct it via test fakes to pin the diagnostic path.
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
 *   that declares a `minServerVersion` resolves to [Disabled] in
 *   that case (no proof the server meets the threshold).
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
