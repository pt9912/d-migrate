package dev.dmigrate.driver

/**
 * E.1 Routine-Migration Slice C.1.a: per-dialect default
 * [RoutineCapability]. Slice C.1.a is the only source today; the
 * later configurable-source slice (CLI/YAML override) will plug in
 * without renderer-API changes.
 *
 * All defaults declare `enabled=true` with `minServerVersion=null`.
 * The `minServerVersion=null` choice is deliberate: it keeps Slice
 * C.2 (MySQL renderer) operationally usable for file-to-file flows
 * without depending on Slice C.3 (Dependency-Guard). Operators who
 * want a version floor will set it explicitly via a future
 * configuration source.
 *
 * SQLite has no user-defined routines in the classical sense
 * (functions/procedures); its default exists for symmetry and is
 * never consumed by a renderer.
 */
object RoutineCapabilityDefaults {

    private val PostgreSQL = RoutineCapability(
        function = RoutineKindCapability(enabled = true, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = true, minServerVersion = null),
    )

    private val MySQL = RoutineCapability(
        function = RoutineKindCapability(enabled = true, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = true, minServerVersion = null),
    )

    private val SQLite = RoutineCapability(
        function = RoutineKindCapability(enabled = false, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = false, minServerVersion = null),
    )

    fun forDialect(dialect: DatabaseDialect): RoutineCapability = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgreSQL
        DatabaseDialect.MYSQL -> MySQL
        DatabaseDialect.SQLITE -> SQLite
    }
}
