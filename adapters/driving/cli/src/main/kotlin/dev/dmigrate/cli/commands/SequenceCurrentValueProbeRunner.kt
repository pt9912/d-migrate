package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.mysql.MysqlSequenceCurrentValueProbe
import dev.dmigrate.driver.postgresql.PostgresSequenceCurrentValueProbe
import java.nio.file.Path

/**
 * 0.9.7 preserve-current-value Sub-Slice D (2026-05-21): CLI-side
 * wiring for [SequenceCurrentValueProbeFn]. Routes the per-op probe
 * call to the dialect-specific JDBC adapter
 * ([PostgresSequenceCurrentValueProbe] / [MysqlSequenceCurrentValueProbe])
 * by inspecting [SequenceObjectRef.dialect].
 *
 * Unlike the drift-check probe runner which keeps a single MySQL
 * pool open for an entire plan's worth of probes, this runner opens
 * one Hikari pool per `probe(...)` call. Per-op connection cost is
 * acceptable for preserve because plans typically carry few
 * sequence ops; if that changes a future slice can cache pools by
 * `target.source` so multiple probes against the same target share
 * one connection.
 *
 * The runner translates any infrastructure error (config-resolver
 * failure, URL parse failure) into [CompareConfigException], identical
 * to [MysqlSequenceCanonicityProbeRunner], so the runner's `--execute`
 * path routes it to exit 7 consistently with other config-side
 * failures.
 */
internal object SequenceCurrentValueProbeRunner {

    fun probe(
        target: CompareOperand.Database,
        configPath: Path?,
        sequenceRef: SequenceObjectRef,
    ): SequenceCurrentValueProbeResult {
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
                when (sequenceRef.dialect) {
                    RenameProjectionDialect.POSTGRESQL ->
                        PostgresSequenceCurrentValueProbe.probe(conn, sequenceRef)
                    RenameProjectionDialect.MYSQL ->
                        MysqlSequenceCurrentValueProbe.probe(conn, sequenceRef)
                    RenameProjectionDialect.SQLITE ->
                        SequenceCurrentValueProbeResult.NotApplicable
                }
            }
        }
    }
}
