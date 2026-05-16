package dev.dmigrate.driver

/**
 * E.1 Routine-Migration Slice C.1.a: per-dialect default
 * [RoutineCapability]. Slice C.1.a is the only source today; the
 * later configurable-source slice (CLI/YAML override) will plug in
 * without renderer-API changes.
 *
 * PostgreSQL defaults to native `CREATE OR REPLACE` support. The
 * MySQL-family default is intentionally conservative: the neutral
 * `MYSQL` dialect maps to Oracle MySQL unless the live server version
 * proves a MariaDB vendor. Oracle MySQL's stored-routine syntax does
 * not support `CREATE OR REPLACE FUNCTION` / `PROCEDURE`; MariaDB does.
 * File-to-file MySQL plans therefore use the renderer's guarded
 * `DROP + CREATE` fallback until a live MariaDB version or a future
 * explicit capability source enables `CREATE OR REPLACE`.
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

    private val OracleMySQL = RoutineCapability(
        function = RoutineKindCapability(enabled = false, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = false, minServerVersion = null),
    )

    private val MariaDB = RoutineCapability(
        function = RoutineKindCapability(enabled = true, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = true, minServerVersion = null),
    )

    private val SQLite = RoutineCapability(
        function = RoutineKindCapability(enabled = false, minServerVersion = null),
        procedure = RoutineKindCapability(enabled = false, minServerVersion = null),
    )

    fun forDialect(dialect: DatabaseDialect): RoutineCapability = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgreSQL
        DatabaseDialect.MYSQL -> OracleMySQL
        DatabaseDialect.SQLITE -> SQLite
    }

    fun forMysqlServerVersion(version: MysqlServerVersion?): RoutineCapability =
        if (version?.isMariaDb == true) MariaDB else OracleMySQL
}
