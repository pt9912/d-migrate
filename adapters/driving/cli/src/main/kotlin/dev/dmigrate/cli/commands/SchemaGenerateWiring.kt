package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.resolveEffectiveHashPartitions
import dev.dmigrate.cli.config.resolveEffectivePartitionStorage
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.PreGenerationValidator
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.mysqlContext
import dev.dmigrate.driver.sqliteContext
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.report.TransformationReportWriter
import dev.dmigrate.text.icu.IcuUnicodeTextService
import java.nio.file.Path

internal data class SchemaGenerateOptions(
    val source: Path,
    val target: String,
    val output: Path?,
    val report: Path?,
    val generateRollback: Boolean,
    val deterministic: Boolean,
    val spatialProfile: String?,
    val partitionStorage: String?,
    val split: String,
    val mysqlNamedSequences: String?,
    val sqliteNamedSequences: String?,
    val mssqlHashPartitions: String?,
    val cliContext: CliContext,
    val configPath: Path? = null,
)

/** Die aus CLI und Konfigurationsdatei zusammengefuehrten `ddl:`-Werte. */
private data class EffectiveDdlSettings(
    val partitionStorage: String?,
    val hashPartitions: String?,
)

internal data class SchemaGenerateWiringBundle(
    val schemaReader: (Path) -> SchemaDefinition,
    val generatorLookup: (DatabaseDialect) -> DdlGenerator,
    val preGenerationValidatorLookup: (DatabaseDialect) -> PreGenerationValidator,
    val reportWriter: (Path, DdlResult, SchemaDefinition, String, Path, String?, DdlGenerationOptions) -> Unit,
    val formatJsonOutput: (
        DdlResult,
        SchemaDefinition,
        String,
        SplitMode,
        MysqlNamedSequenceMode?,
        SqliteNamedSequenceMode?,
    ) -> String,
    val sidecarPath: (Path, String) -> Path,
    val rollbackPath: (Path) -> Path,
    val splitPath: (Path, DdlPhase) -> Path,
    val printError: (String, String) -> Unit,
    val printValidationResult: (ValidationResult, SchemaDefinition, String) -> Unit,
)

internal fun interface SchemaGenerateWiringFactory {
    fun build(cliContext: CliContext): SchemaGenerateWiringBundle
}

internal object DefaultSchemaGenerateWiringFactory : SchemaGenerateWiringFactory {

    override fun build(cliContext: CliContext): SchemaGenerateWiringBundle {
        val formatter = OutputFormatter(cliContext, IcuUnicodeTextService())
        val reportWriter = TransformationReportWriter()
        return SchemaGenerateWiringBundle(
            schemaReader = { path -> SchemaFileResolver.codecForPath(path).read(path) },
            generatorLookup = { DatabaseDriverRegistry.get(it).ddlGenerator() },
            preGenerationValidatorLookup = { DatabaseDriverRegistry.get(it).preGenerationValidator() },
            reportWriter = { path, result, schema, dialect, src, splitModeStr, ddlOptions ->
                reportWriter.write(
                    path,
                    result,
                    schema,
                    dialect,
                    src,
                    splitModeStr,
                    ddlOptions.mysqlContext?.namedSequenceMode,
                    ddlOptions.generatedAt,
                    ddlOptions.deterministic,
                    ddlOptions.sqliteContext?.namedSequenceMode,
                )
            },
            formatJsonOutput = SchemaGenerateHelpers::formatJsonOutput,
            sidecarPath = SchemaGenerateHelpers::sidecarPath,
            rollbackPath = SchemaGenerateHelpers::rollbackPath,
            splitPath = SchemaGenerateHelpers::splitPath,
            printError = { msg, src -> formatter.printError(msg, src) },
            printValidationResult = { result, schema, src ->
                formatter.printValidationResult(result, schema, src)
            },
        )
    }
}

internal object SchemaGenerateWiring {

    fun execute(
        options: SchemaGenerateOptions,
        factory: SchemaGenerateWiringFactory = DefaultSchemaGenerateWiringFactory,
    ): Int {
        val bundle = factory.build(options.cliContext)
        // Der `ddl:`-Block ist nach Dialekt geschachtelt; `ddl.mssql.*` wirkt
        // deshalb nur, wenn auch gegen SQL Server generiert wird. Ein anderer
        // Ziel-Dialekt sieht ausschliesslich das CLI-Flag.
        val isMssql = options.target.equals("mssql", ignoreCase = true)
        val ddl = if (isMssql) {
            try {
                EffectiveDdlSettings(
                    partitionStorage = resolveEffectivePartitionStorage(
                        options.configPath, options.partitionStorage,
                    ),
                    hashPartitions = resolveEffectiveHashPartitions(
                        options.configPath, options.mssqlHashPartitions,
                    ),
                )
            } catch (e: ConfigResolveException) {
                bundle.printError(e.message ?: "Failed to resolve ddl configuration", "ddl")
                return 7
            }
        } else {
            EffectiveDdlSettings(options.partitionStorage, options.mssqlHashPartitions)
        }
        val splitMode = if (options.split == "pre-post") SplitMode.PRE_POST else SplitMode.SINGLE
        val request = SchemaGenerateRequest(
            source = options.source,
            target = options.target,
            spatialProfile = options.spatialProfile,
            partitionStorage = ddl.partitionStorage,
            output = options.output,
            report = options.report,
            generateRollback = options.generateRollback,
            outputFormat = options.cliContext.outputFormat,
            verbose = options.cliContext.verbose,
            quiet = options.cliContext.quiet,
            splitMode = splitMode,
            mysqlNamedSequences = options.mysqlNamedSequences,
            sqliteNamedSequences = options.sqliteNamedSequences,
            mssqlHashPartitions = ddl.hashPartitions,
            deterministic = options.deterministic,
        )
        val runner = SchemaGenerateRunner(
            schemaReader = bundle.schemaReader,
            generatorLookup = bundle.generatorLookup,
            preGenerationValidatorLookup = bundle.preGenerationValidatorLookup,
            reportWriter = bundle.reportWriter,
            formatJsonOutput = bundle.formatJsonOutput,
            sidecarPath = bundle.sidecarPath,
            rollbackPath = bundle.rollbackPath,
            splitPath = bundle.splitPath,
            printError = bundle.printError,
            printValidationResult = bundle.printValidationResult,
        )
        return runner.execute(request)
    }
}
