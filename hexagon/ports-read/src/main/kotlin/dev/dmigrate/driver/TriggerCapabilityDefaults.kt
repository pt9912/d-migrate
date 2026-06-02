package dev.dmigrate.driver

/**
 * E.2 Trigger-Migration Sub-Slice A.2: per-dialect default
 * [TriggerCapability].
 *
 * - **PostgreSQL**: `enabled = true` with `minPostgresMajorVersion = 14`.
 *   PG-14 introduced `CREATE OR REPLACE TRIGGER`; older servers fall
 *   back to Drop+Create with the gap-warning contract.
 * - **MySQL**: `enabled = false`. Neither Oracle MySQL nor MariaDB
 *   support `CREATE OR REPLACE TRIGGER` (as of 8.4 / 11.x). The
 *   renderer always emits Drop+Create.
 * - **SQLite**: `enabled = false`. SQLite has no `CREATE OR REPLACE
 *   TRIGGER` grammar; Drop+Create is the only path.
 *
 * Unlike `RoutineCapabilityDefaults`, this object does not expose a
 * `forMysqlServerVersion(...)` variant — there is no MariaDB-specific
 * trigger capability and the floor is PG-only.
 */
object TriggerCapabilityDefaults {

    private val PostgreSQL = TriggerCapability(enabled = true, minPostgresMajorVersion = 14)
    private val MySQL = TriggerCapability(enabled = false)
    private val SQLite = TriggerCapability(enabled = false)

    fun forDialect(dialect: DatabaseDialect): TriggerCapability = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgreSQL
        DatabaseDialect.MYSQL -> MySQL
        DatabaseDialect.SQLITE -> SQLite
    }
}
