package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.sqlite.SqliteCastPreflightProbe
import java.nio.file.Path

/**
 * CLI wiring for the SQLite cast live-data preflight. Mirrors the
 * live-catalog probe's connection resolution so the preflight reads
 * from the same target database the execute path will mutate.
 */
internal object SqliteCastPreflightProbeRunner {

    fun probe(
        target: CompareOperand.Database,
        configPath: Path?,
        plan: DiffResult,
    ): List<SqliteCastPreflightDeclaration> {
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
                SqliteCastPreflightProbe.probe(conn, plan)
            }
        }
    }
}
