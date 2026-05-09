package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.LogScrubber
import dev.dmigrate.text.icu.IcuUnicodeTextService

class SchemaRollbackCommand : CliktCommand(name = "rollback") {
    override fun help(context: Context) =
        "Validate and execute a Down-SQL artefact produced by `schema migrate --generate-rollback`"

    val source by option("--source", help = "Down-SQL artefact path")
        .path().required()
    val target by option("--target", help = "Target DB connection (db:<url-or-alias>)")
        .required()
    val execute by option("--execute", help = "Execute Down-SQL against --target").flag()
    val allowDestructive by option("--allow-destructive", help = "Permit destructive Down operations").flag()
    val dryRun by option("--dry-run", help = "Validate / preview only").flag()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val formatter = OutputFormatter(ctx, IcuUnicodeTextService())
        val validator = SchemaValidator()

        val request = SchemaRollbackRequest(
            source = source,
            target = target,
            execute = execute,
            allowDestructive = allowDestructive,
            dryRun = dryRun,
            cliConfigPath = root?.config,
        )
        val runner = SchemaRollbackRunner(
            dbLoader = { op, cfgPath -> loadFromDb(op, cfgPath, validator) },
            executor = JdbcMigrationExecutor::execute,
            urlScrubber = LogScrubber::maskUrl,
            printError = { msg, src -> formatter.printError(msg, src) },
        )
        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }

    private fun loadFromDb(
        op: CompareOperand.Database,
        cfgPath: java.nio.file.Path?,
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
