package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.SqliteLiveCatalog
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.sqlite.SqliteLiveCatalogProbe
import java.nio.file.Path

/**
 * Plan-2 §A.2: connection-open wrapper around
 * [SqliteLiveCatalogProbe] for the CLI runner. Resolves the target
 * URL the same way [JdbcMigrationExecutor] does (named-connection +
 * URL parser + Hikari pool) so the probe sees the exact same SQLite
 * file the `--execute` path will mutate.
 *
 * Any exception thrown here (config resolution, URL parse, Hikari
 * failure, `sqlite_master` read failure) propagates to
 * `SchemaMigrateRunner.runSqliteLiveCatalogProbe`, where it becomes
 * a `SQLITE_LIVE_CATALOG_PROBE_FAILED` blocker that short-circuits
 * before the first mutating statement (Exit 8).
 */
internal object SqliteLiveCatalogProbeRunner {

    fun probe(target: CompareOperand.Database, configPath: Path?): SqliteLiveCatalog {
        val url = try {
            NamedConnectionResolver(configPathFromCli = configPath).resolve(target.source)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
        val config = try {
            ConnectionUrlParser.parse(url)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "URL parse failed", e)
        }
        val pool = HikariConnectionPoolFactory.create(config)
        return pool.use { p ->
            p.borrow().use { conn ->
                SqliteLiveCatalogProbe.probe(conn)
            }
        }
    }
}
