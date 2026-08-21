package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.mysql.MysqlCheckPreflightProbe
import dev.dmigrate.driver.postgresql.PostgresCheckPreflightProbe
import dev.dmigrate.driver.sqlite.SqliteCheckPreflightProbe
import java.nio.file.Path

/**
 * F.5 Sub-Slice E.4 (2026-05-19): cross-dialect CLI wiring for the
 * live CHECK preflight probe. Mirrors
 * [SqliteCastPreflightProbeRunner] for connection-pool plumbing;
 * the per-dialect dispatch picks the right adapter probe
 * (`Postgres*` / `Mysql*` / `Sqlite*`) based on [DatabaseDialect].
 *
 * The function is called by `SchemaMigrateRenderPipeline.run` via
 * the [CheckPreflightProbeFn] typealias when the request is
 * `--execute` against a database target and the planner produced
 * at least one CHECK Add declaration.
 */
internal object CheckPreflightProbeRunner {

    fun probe(
        target: CompareOperand.Database,
        configPath: Path?,
        plan: DiffResult,
        dialect: DatabaseDialect,
    ): List<CheckPreflightDeclaration> {
        val url = try {
            NamedConnectionResolver(configPathFromCli = configPath).resolve(target.source)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
        val config = try {
            CredentialFilling(target.source).fill(url)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "URL parse failed", e)
        }
        val pool = HikariConnectionPoolFactory.create(config)
        return pool.use { p ->
            p.borrow().use { conn -> dispatch(conn, dialect, plan) }
        }
    }

    /**
     * Per-dialect probe selection. Intentionally exhaustive over
     * [DatabaseDialect] (no `else`): a future dialect must wire a probe
     * explicitly, the compiler enforces it here.
     */
    internal fun dispatch(
        connection: DatabaseConnection,
        dialect: DatabaseDialect,
        plan: DiffResult,
    ): List<CheckPreflightDeclaration> = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgresCheckPreflightProbe.probe(connection, plan)
        DatabaseDialect.MYSQL -> MysqlCheckPreflightProbe.probe(connection, plan)
        DatabaseDialect.SQLITE -> SqliteCheckPreflightProbe.probe(connection, plan)
        DatabaseDialect.MSSQL -> error(
            "unreachable: DialectCommandGate rejects mssql for schema migrate (ADR 0047)",
        )
    }
}
