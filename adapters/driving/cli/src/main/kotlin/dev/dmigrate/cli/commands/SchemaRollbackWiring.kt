package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.cli.audit.recordIf
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.LogScrubber
import dev.dmigrate.text.icu.IcuUnicodeTextService
import java.nio.file.Path

internal data class SchemaRollbackOptions(
    val source: Path,
    val target: String,
    val execute: Boolean,
    val allowDestructive: Boolean,
    val allowPartialRollback: Boolean,
    val dryRun: Boolean,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal object SchemaRollbackWiring {

    fun execute(
        options: SchemaRollbackOptions,
        recorder: CliAuditRecorder = cliAuditRecorder(options.configPath),
    ): Int = recorder.recordIf(options.execute, "schema.rollback", listOf(options.target)) {
        executeInner(options)
    }

    private fun executeInner(options: SchemaRollbackOptions): Int {
        val formatter = OutputFormatter(options.cliContext, IcuUnicodeTextService())
        val validator = SchemaValidator()
        val request = SchemaRollbackRequest(
            source = options.source,
            target = options.target,
            execute = options.execute,
            allowDestructive = options.allowDestructive,
            allowPartialRollback = options.allowPartialRollback,
            dryRun = options.dryRun,
            cliConfigPath = options.configPath,
        )
        val runner = SchemaRollbackRunner(
            dbLoader = { op, cfgPath -> loadFromDb(op, cfgPath, validator) },
            executor = JdbcMigrationExecutor::execute,
            urlScrubber = LogScrubber::maskUrl,
            printError = { msg, src -> formatter.printError(msg, src) },
        )
        return runner.execute(request)
    }

    private fun loadFromDb(
        op: CompareOperand.Database,
        cfgPath: Path?,
        validator: SchemaValidator,
    ): ResolvedSchemaOperand {
        val url = try {
            NamedConnectionResolver(configPathFromCli = cfgPath).resolve(op.source)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
        val config = ConnectionUrlParser.parse(url)
        val userRef = if (op.source.contains("://")) LogScrubber.maskUrl(url) else op.source
        val pool = HikariConnectionPoolFactory.create(config)
        return pool.use { p ->
            val result = DatabaseDriverRegistry.get(config.dialect).schemaReader()
                .read(p, SchemaReadOptions())
            ResolvedSchemaOperand(
                reference = userRef,
                schema = result.schema,
                validation = validator.validate(result.schema),
                notes = result.notes,
                skippedObjects = result.skippedObjects,
                dialect = config.dialect,
            )
        }
    }
}
