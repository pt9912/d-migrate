package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.config.ConfigMissingDefaultException
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.config.PipelineCheckpointResolver
import dev.dmigrate.cli.output.MessageResolver
import dev.dmigrate.cli.output.ProgressRenderer
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.format.data.DefaultDataChunkReaderFactory
import dev.dmigrate.format.yaml.YamlSchemaCodec
import dev.dmigrate.streaming.StreamingImporter
import dev.dmigrate.streaming.checkpoint.FileCheckpointStore
import java.nio.file.Path

internal data class DataImportOptions(
    val target: String?,
    val source: String,
    val format: String?,
    val schema: Path?,
    val table: String?,
    val tables: List<String>?,
    val onError: String,
    val onConflict: String?,
    val triggerMode: String,
    val truncate: Boolean,
    val disableFkChecks: Boolean,
    val reseedSequences: Boolean,
    val encoding: String?,
    val csvNoHeader: Boolean,
    val csvNullString: String,
    val chunkSize: Int,
    val resume: String?,
    val checkpointDir: Path?,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal object DataImportWiring {

    fun execute(options: DataImportOptions): Int {
        val writerLookup: (DatabaseDialect) -> DataWriter = { dialect ->
            DatabaseDriverRegistry.get(dialect).dataWriter()
        }
        val readerFactory = DefaultDataChunkReaderFactory()
        val preflight = DataImportSchemaPreflight(YamlSchemaCodec())
        val request = DataImportRequest(
            target = options.target,
            source = options.source,
            format = options.format,
            schema = options.schema,
            table = options.table,
            tables = options.tables,
            onError = options.onError,
            onConflict = options.onConflict,
            triggerMode = options.triggerMode,
            truncate = options.truncate,
            disableFkChecks = options.disableFkChecks,
            reseedSequences = options.reseedSequences,
            encoding = options.encoding,
            csvNoHeader = options.csvNoHeader,
            csvNullString = options.csvNullString,
            chunkSize = options.chunkSize,
            cliConfigPath = options.configPath,
            quiet = options.cliContext.quiet,
            noProgress = options.cliContext.noProgress,
            resume = options.resume,
            checkpointDir = options.checkpointDir,
        )
        val runner = DataImportRunner(
            targetResolver = { target, configPath ->
                try {
                    NamedConnectionResolver(configPathFromCli = configPath).resolveTarget(target)
                } catch (e: ConfigMissingDefaultException) {
                    throw CliUsageException(
                        "--target is required when database.default_target is not set.",
                        e,
                    )
                } catch (e: ConfigResolveException) {
                    throw IllegalArgumentException(e.message ?: "Failed to resolve --target.", e)
                }
            },
            urlParser = ConnectionUrlParser::parse,
            poolFactory = HikariConnectionPoolFactory::create,
            writerLookup = writerLookup,
            schemaPreflight = preflight::prepare,
            schemaTargetValidator = preflight::validateTargetTable,
            importExecutor = ImportExecutor { ctx, opts, resume, callbacks ->
                val importer = StreamingImporter(
                    readerFactory = readerFactory,
                    writerLookup = writerLookup,
                    onTableOpened = callbacks.onTableOpened,
                )
                importer.import(
                    pool = ctx.pool,
                    input = ctx.input,
                    format = opts.format,
                    options = opts.options,
                    readOptions = opts.readOptions,
                    config = opts.config,
                    progressReporter = callbacks.progressReporter,
                    operationId = resume.operationId,
                    resuming = resume.resuming,
                    skippedTables = resume.skippedTables,
                    resumeStateByTable = resume.resumeStateByTable,
                    onChunkCommitted = callbacks.onChunkCommitted,
                    onTableCompleted = callbacks.onTableCompleted,
                    cancellationToken = ctx.cancellationToken,
                )
            },
            progressReporter = ProgressRenderer(messages = MessageResolver(options.cliContext.locale)),
            checkpointStoreFactory = { dir -> FileCheckpointStore(dir) },
            checkpointConfigResolver = { cliCfg ->
                PipelineCheckpointResolver(configPathFromCli = cliCfg).resolve()
            },
        )
        return runner.execute(request)
    }
}
