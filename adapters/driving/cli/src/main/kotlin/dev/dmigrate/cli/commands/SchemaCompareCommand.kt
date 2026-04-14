package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.LogScrubber
import dev.dmigrate.format.SchemaFileResolver

class SchemaCompareCommand : CliktCommand(name = "compare") {
    override fun help(context: Context) = "Compare two schema definitions"

    val source by option("--source", help = "Schema operand: file path, file:<path>, or db:<url-or-alias>")
        .required()
    val target by option("--target", help = "Schema operand: file path, file:<path>, or db:<url-or-alias>")
        .required()
    val output by option("--output", help = "Output file path (default: stdout)")
        .path()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val formatter = OutputFormatter(ctx)
        val validator = SchemaValidator()
        val request = SchemaCompareRequest(
            source = source,
            target = target,
            output = output,
            outputFormat = ctx.outputFormat,
            quiet = ctx.quiet,
            verbose = ctx.verbose,
            cliConfigPath = root?.config,
        )
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                val schema = SchemaFileResolver.codecForPath(op.path).read(op.path)
                ResolvedSchemaOperand(
                    reference = op.path.toString(),
                    schema = schema,
                    validation = validator.validate(schema),
                )
            },
            dbLoader = { op, cfgPath ->
                // Phase 1: Config/URL resolution (exit 7 on failure)
                val url: String
                val config: dev.dmigrate.driver.connection.ConnectionConfig
                try {
                    url = NamedConnectionResolver(configPathFromCli = cfgPath).resolve(op.source)
                    config = ConnectionUrlParser.parse(url)
                } catch (e: Exception) {
                    throw CompareConfigException(e.message ?: "Config resolution failed", e)
                }
                val userRef = if (op.source.contains("://")) LogScrubber.maskUrl(url) else op.source
                // Phase 2: Connection/read (exit 4 on failure)
                val pool = HikariConnectionPoolFactory.create(config)
                pool.use { p ->
                    val result = DatabaseDriverRegistry.get(config.dialect).schemaReader()
                        .read(p, SchemaReadOptions())
                    ResolvedSchemaOperand(
                        reference = userRef,
                        schema = result.schema,
                        validation = validator.validate(result.schema),
                        notes = result.notes,
                        skippedObjects = result.skippedObjects,
                    )
                }
            },
            urlScrubber = LogScrubber::maskUrl,
            comparator = { left, right -> SchemaComparator().compare(left, right) },
            projectDiff = SchemaCompareHelpers::projectDiff,
            renderPlain = SchemaCompareHelpers::renderPlain,
            renderJson = SchemaCompareHelpers::renderJson,
            renderYaml = SchemaCompareHelpers::renderYaml,
            printError = { msg, src -> formatter.printError(msg, src) },
        )
        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
