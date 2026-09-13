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

    /**
     * Duenne Weiterleitung an [DialectReadCapabilityLookup]; die Werte liegen
     * im Treibermodul des jeweiligen Dialekts.
     */
    fun forDialect(dialect: DatabaseDialect): EffectiveRoutineCapability.Valid =
        DialectReadCapabilityLookup.forDialect(dialect).routineCapability(null)

    /**
     * Die MySQL-Familie, Version eingerechnet: `MYSQL` steht fuer Oracle MySQL
     * **und** MariaDB, und nur eine von beiden kennt `CREATE OR REPLACE`.
     */
    fun forMysqlServerVersion(version: MysqlServerVersion?): EffectiveRoutineCapability.Valid =
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.MYSQL).routineCapability(version)
}
