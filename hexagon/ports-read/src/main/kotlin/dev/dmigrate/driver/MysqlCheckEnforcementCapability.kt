package dev.dmigrate.driver

/**
 * F.5 Sub-Slice C: per-server enforcement contract for MySQL / MariaDB
 * CHECK constraints.
 *
 * Pre-MySQL 8.0.16 and pre-MariaDB 10.2.1 silently *parse* a CHECK
 * clause but never evaluate it at insert/update time, so an
 * `ADD CONSTRAINT … CHECK (…)` migration produces no actual data
 * guarantee on those servers. The renderer treats that case as
 * `MANUAL_ACTION_REQUIRED` (`MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16`)
 * rather than emitting silently-no-op DDL.
 *
 * When no live server version is known (file-to-file mode, or read
 * failure), the renderer cannot prove enforcement either way and
 * blocks with `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`.
 *
 * `enforced` and `known` are independent:
 * - `enforced=true, known=true`  → server is provably ≥ floor, render natively.
 * - `enforced=false, known=true` → server is provably below floor, block ADD/REPLACE.
 * - `enforced=false, known=false` → no live version, block ADD/REPLACE.
 *
 * Drop-side CHECK rendering does not need enforcement (you can drop a
 * silently-not-enforced constraint), but it *does* need `known=true`
 * so the renderer can be sure `DROP CHECK <name>` syntax is supported
 * (MySQL 8.0.16+, MariaDB 10.2.1+). When `known=false` the drop is
 * blocked with the same `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`.
 */
data class MysqlCheckEnforcementCapability(
    val enforced: Boolean,
    val known: Boolean,
    val rationale: String,
)

/**
 * Resolves a [MysqlServerVersion] (possibly `null` for file-only
 * targets) into a [MysqlCheckEnforcementCapability].
 *
 * The version floors come from the upstream release notes:
 * - MySQL 8.0.16 (April 2019) introduced enforced CHECK clauses.
 * - MariaDB 10.2.1 (July 2016) shipped enforced CHECK semantics.
 *
 * Both floors are inclusive: `live ≥ floor ⇒ enforced=true`.
 */
object MysqlCheckEnforcementResolver {

    private val MARIADB_FLOOR = MysqlServerVersion(10, 2, 1)
    private val MYSQL_FLOOR = MysqlServerVersion(8, 0, 16)

    fun resolve(serverVersion: MysqlServerVersion?): MysqlCheckEnforcementCapability {
        if (serverVersion == null) {
            return MysqlCheckEnforcementCapability(
                enforced = false,
                known = false,
                rationale = "mysqlServerVersion konnte nicht gelesen werden",
            )
        }
        return if (serverVersion.isMariaDb) {
            if (serverVersion >= MARIADB_FLOOR) {
                MysqlCheckEnforcementCapability(true, true, "MariaDB ≥ 10.2.1")
            } else {
                MysqlCheckEnforcementCapability(
                    enforced = false,
                    known = true,
                    rationale = "MariaDB < 10.2.1 ignoriert CHECK semantisch",
                )
            }
        } else {
            if (serverVersion >= MYSQL_FLOOR) {
                MysqlCheckEnforcementCapability(true, true, "MySQL ≥ 8.0.16")
            } else {
                MysqlCheckEnforcementCapability(
                    enforced = false,
                    known = true,
                    rationale = "MySQL < 8.0.16 ignoriert CHECK semantisch",
                )
            }
        }
    }
}
