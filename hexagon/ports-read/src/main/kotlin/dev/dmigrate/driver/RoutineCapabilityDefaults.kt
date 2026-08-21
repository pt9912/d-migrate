package dev.dmigrate.driver

/**
 * E.1 Routine-Migration Slice C.1.a: per-dialect default
 * [EffectiveRoutineCapability]. The 0.9.7
 * routine-capability-configurable-source carve-out keeps the defaults
 * as the lowest-precedence layer (CLI > YAML > Defaults); they always
 * return [EffectiveRoutineCapability.Valid] — only operator-supplied
 * configuration can produce [EffectiveRoutineCapability.Invalid].
 *
 * PostgreSQL defaults to native `CREATE OR REPLACE` support. The
 * MySQL-family default is intentionally conservative: the neutral
 * `MYSQL` dialect maps to Oracle MySQL unless the live server version
 * proves a MariaDB vendor. Oracle MySQL's stored-routine syntax does
 * not support `CREATE OR REPLACE FUNCTION` / `PROCEDURE`; MariaDB does.
 * File-to-file MySQL plans therefore use the renderer's guarded
 * `DROP + CREATE` fallback until a live MariaDB version or an
 * operator-supplied capability source enables `CREATE OR REPLACE`.
 *
 * SQLite has no user-defined routines in the classical sense
 * (functions/procedures); its default exists for symmetry and is
 * never consumed by a renderer.
 */
object RoutineCapabilityDefaults {

    private val PostgreSQL = EffectiveRoutineCapability.Valid(
        function = RoutineKindCapability(enabled = true, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = true, minServerVersion = null),
    )

    private val OracleMySQL = EffectiveRoutineCapability.Valid(
        function = RoutineKindCapability(enabled = false, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = false, minServerVersion = null),
    )

    private val MariaDB = EffectiveRoutineCapability.Valid(
        function = RoutineKindCapability(enabled = true, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = true, minServerVersion = null),
    )

    private val SQLite = EffectiveRoutineCapability.Valid(
        function = RoutineKindCapability(enabled = false, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = false, minServerVersion = null),
    )

    // Konservativ wie Oracle MySQL: Drop+Create-Fallback ist fuer T-SQL immer
    // gueltig; ob der Renderer `CREATE OR ALTER` (2016 SP1+) nutzt, entscheidet
    // der Routinen-Slice (docs/planning/in-progress/mssql-dialect-scoping.md,
    // Slice 9).
    private val Mssql = EffectiveRoutineCapability.Valid(
        function = RoutineKindCapability(enabled = false, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = false, minServerVersion = null),
    )

    fun forDialect(dialect: DatabaseDialect): EffectiveRoutineCapability.Valid = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgreSQL
        DatabaseDialect.MYSQL -> OracleMySQL
        DatabaseDialect.SQLITE -> SQLite
        DatabaseDialect.MSSQL -> Mssql
    }

    fun forMysqlServerVersion(version: MysqlServerVersion?): EffectiveRoutineCapability.Valid =
        if (version?.isMariaDb == true) MariaDB else OracleMySQL
}
