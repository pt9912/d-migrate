package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.SqliteLiveCatalog
import java.sql.Connection

/**
 * Plan-2 §A.2: reads the live `sqlite_master` catalog into a
 * port-level [SqliteLiveCatalog]. Public entry point for the
 * `SchemaMigrateRunner`'s pre-render probe — keeps
 * `SqliteCatalogSnapshot` (the renderer-internal type) hidden from
 * the application layer.
 *
 * The probe is read-only: a single `SELECT name, type FROM
 * sqlite_master WHERE type IN (...)` per connection. SQLite owns a
 * single namespace per database for table / view / index / trigger
 * names, so the four sets are populated in one pass.
 *
 * Filter contract:
 *
 * - `sqlite_%`-prefixed system objects (`sqlite_sequence`,
 *   `sqlite_autoindex_*`, etc.) are EXCLUDED — they're never user
 *   objects and never collide with renderer-chosen rebuild temp
 *   names. SQLite itself rejects user objects with `sqlite_`-
 *   prefixed names at CREATE time, so a collision against one of
 *   them can't happen.
 * - Auto-created indices behind `UNIQUE`/`PRIMARY KEY` are filtered
 *   via the same `sqlite_%` rule (their names start with
 *   `sqlite_autoindex_`).
 *
 * Error handling: a `SQLException` from JDBC or a malformed
 * `sqlite_master` row propagates to the caller. The runner wraps
 * it into a `SQLITE_LIVE_CATALOG_PROBE_FAILED` diagnostic and a
 * MANUAL_ACTION_REQUIRED blocker, which short-circuits execute
 * with Exit 8.
 */
object SqliteLiveCatalogProbe {

    private const val QUERY =
        "SELECT name, type FROM sqlite_master " +
            "WHERE type IN ('table', 'view', 'index', 'trigger') " +
            "AND name NOT LIKE 'sqlite_%'"

    fun probe(connection: Connection): SqliteLiveCatalog {
        val tables = mutableSetOf<String>()
        val views = mutableSetOf<String>()
        val indices = mutableSetOf<String>()
        val triggers = mutableSetOf<String>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(QUERY).use { rs ->
                while (rs.next()) {
                    val name = rs.getString("name") ?: continue
                    when (rs.getString("type")) {
                        "table" -> tables += name
                        "view" -> views += name
                        "index" -> indices += name
                        "trigger" -> triggers += name
                    }
                }
            }
        }
        return SqliteLiveCatalog(
            tables = tables,
            views = views,
            indices = indices,
            triggers = triggers,
        )
    }
}
