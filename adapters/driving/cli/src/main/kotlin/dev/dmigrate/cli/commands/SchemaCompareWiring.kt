package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.LogScrubber
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.text.icu.IcuUnicodeTextService
import java.nio.file.Path

internal data class SchemaCompareOptions(
    val source: String,
    val target: String,
    val output: Path?,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal data class SchemaCompareWiringBundle(
    val fileLoader: (CompareOperand.File) -> ResolvedSchemaOperand,
    val dbLoader: (CompareOperand.Database, Path?) -> ResolvedSchemaOperand,
    val urlScrubber: (String) -> String,
    val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
    val projectDiff: (SchemaDiff) -> DiffView,
    val renderPlain: (SchemaCompareDocument) -> String,
    val renderJson: (SchemaCompareDocument) -> String,
    val renderYaml: (SchemaCompareDocument) -> String,
    val printError: (String, String) -> Unit,
)

internal fun interface SchemaCompareWiringFactory {
    fun build(cliContext: CliContext): SchemaCompareWiringBundle
}

internal object DefaultSchemaCompareWiringFactory : SchemaCompareWiringFactory {

    override fun build(cliContext: CliContext): SchemaCompareWiringBundle {
        val formatter = OutputFormatter(cliContext, IcuUnicodeTextService())
        val validator = SchemaValidator()
        val comparator = SchemaComparator()
        return SchemaCompareWiringBundle(
            fileLoader = { op -> loadFileOperand(op, validator) },
            dbLoader = { op, cfgPath -> loadDatabaseOperand(op, cfgPath, validator) },
            urlScrubber = LogScrubber::maskUrl,
            comparator = { left, right -> comparator.compare(left, right) },
            projectDiff = SchemaCompareHelpers::projectDiff,
            renderPlain = SchemaCompareHelpers::renderPlain,
            renderJson = SchemaCompareHelpers::renderJson,
            renderYaml = SchemaCompareHelpers::renderYaml,
            printError = { msg, src -> formatter.printError(msg, src) },
        )
    }

    private fun loadFileOperand(
        op: CompareOperand.File,
        validator: SchemaValidator,
    ): ResolvedSchemaOperand {
        val schema = SchemaFileResolver.codecForPath(op.path).read(op.path)
        return ResolvedSchemaOperand(
            reference = op.path.toString(),
            schema = schema,
            validation = validator.validate(schema),
        )
    }

    private fun loadDatabaseOperand(
        op: CompareOperand.Database,
        cfgPath: Path?,
        validator: SchemaValidator,
    ): ResolvedSchemaOperand {
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
            return ResolvedSchemaOperand(
                reference = userRef,
                schema = result.schema,
                validation = validator.validate(result.schema),
                notes = result.notes,
                skippedObjects = result.skippedObjects,
            )
        }
    }
}

internal object SchemaCompareWiring {

    fun execute(
        options: SchemaCompareOptions,
        factory: SchemaCompareWiringFactory = DefaultSchemaCompareWiringFactory,
    ): Int {
        val bundle = factory.build(options.cliContext)
        val request = SchemaCompareRequest(
            source = options.source,
            target = options.target,
            output = options.output,
            outputFormat = options.cliContext.outputFormat,
            quiet = options.cliContext.quiet,
            verbose = options.cliContext.verbose,
            cliConfigPath = options.configPath,
        )
        val runner = SchemaCompareRunner(
            fileLoader = bundle.fileLoader,
            dbLoader = bundle.dbLoader,
            urlScrubber = bundle.urlScrubber,
            comparator = bundle.comparator,
            projectDiff = bundle.projectDiff,
            renderPlain = bundle.renderPlain,
            renderJson = bundle.renderJson,
            renderYaml = bundle.renderYaml,
            printError = bundle.printError,
        )
        return runner.execute(request)
    }
}
