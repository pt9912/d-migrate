package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.migration.JdbcMigrationStatementExecutor
import dev.dmigrate.driver.migration.MigrationDdlStatement
import java.nio.file.Path

/**
 * Composition-root wiring for executing rendered migration statements.
 *
 * The CLI resolves the target and creates the pool; JDBC unwrapping,
 * transaction handling, rollback mapping, and runner hooks live in the
 * driven JDBC adapter.
 */
internal object JdbcMigrationExecutor {

    fun execute(
        target: CompareOperand.Database,
        statements: List<MigrationDdlStatement>,
        configPath: Path?,
    ): ExecutionTrace {
        if (statements.isEmpty()) {
            return ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = 0,
            )
        }
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
            JdbcMigrationStatementExecutor.execute(p, statements)
        }
    }
}
