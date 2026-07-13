package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.cli.config.ConfigMissingDefaultException
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.config.PipelineCheckpointResolver
import dev.dmigrate.cli.output.MessageResolver
import dev.dmigrate.cli.output.ProgressRenderer
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.format.SchemaCodec
import dev.dmigrate.format.data.DefaultDataChunkReaderFactory
import dev.dmigrate.format.data.DefaultValueDeserializerFactory
import dev.dmigrate.format.parquet.ParquetSeekableDataChunkReaderFactory
import dev.dmigrate.format.yaml.YamlSchemaCodec
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.StreamingImporter
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.FileCheckpointStore
import java.nio.file.Path

internal data class DataImportOptions(
    val target: String?,
    val source: String,
    val format: String?,
    val schema: Path?,
    val table: String?,
    val tables: List<String>?,
    val tableOrder: List<String>? = null,
    val onError: String,
    val onConflict: String?,
    val triggerMode: String,
    val truncate: Boolean,
    val atomic: Boolean,
    val disableFkChecks: Boolean,
    val reseedSequences: Boolean,
    val encoding: String?,
    val csvNoHeader: Boolean,
    val csvNullString: String,
    val chunkSize: Int,
    val parallel: Int,
    val resume: String?,
    val checkpointDir: Path?,
    val noCheckpoint: Boolean,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal data class DataImportWiringBundle(
    val targetResolver: (String?, Path?) -> String,
    val urlParser: (String) -> ConnectionConfig,
    val poolFactory: (ConnectionConfig) -> ConnectionPool,
    val writerLookup: (DatabaseDialect) -> DataWriter,
    val schemaCodec: SchemaCodec,
    val preflightFactory: (SchemaCodec) -> DataImportSchemaPreflight,
    val importExecutor: ImportExecutor,
    val progressReporter: ProgressReporter,
    val checkpointStoreFactory: ((Path) -> CheckpointStore)?,
    val checkpointConfigResolver: (Path?) -> CheckpointConfig?,
)

internal fun interface DataImportWiringFactory {
    fun build(cliContext: CliContext): DataImportWiringBundle
}

internal object DefaultDataImportWiringFactory : DataImportWiringFactory {

    override fun build(cliContext: CliContext): DataImportWiringBundle {
        val writerLookup: (DatabaseDialect) -> DataWriter = { dialect ->
            DatabaseDriverRegistry.get(dialect).dataWriter()
        }
        val readerFactory = DefaultDataChunkReaderFactory()
        return DataImportWiringBundle(
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
            schemaCodec = YamlSchemaCodec(),
            preflightFactory = ::DataImportSchemaPreflight,
            importExecutor = ImportExecutor { ctx, opts, resume, callbacks ->
                val importer = StreamingImporter(
                    readerFactory = readerFactory,
                    valueDeserializerFactory = DefaultValueDeserializerFactory(),
                    seekableReaderFactory = ParquetSeekableDataChunkReaderFactory(),
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
                    fkLayers = importFkLayers(ctx.pool, opts.config.parallelism),
                )
            },
            progressReporter = ProgressRenderer(messages = MessageResolver(cliContext.locale)),
            checkpointStoreFactory = { dir -> FileCheckpointStore(dir) },
            checkpointConfigResolver = { cliCfg ->
                PipelineCheckpointResolver(configPathFromCli = cliCfg).resolve()
            },
        )
    }

    /**
     * LN-007/LN-008 (ADR 0032): on the parallel path, read the target schema once to group
     * the resolved inputs into FK-safe layers (a child-partition input inherits its parent's
     * layer). Kept in the composition root (driver access); single layer for the sequential path.
     */
    private fun importFkLayers(pool: ConnectionPool, parallelism: Int): (List<String>) -> List<List<String>> {
        if (parallelism <= 1) return { listOf(it) }
        return { inputTables -> ImportLayerPlanner.plan(readStructuralSchema(pool), inputTables) }
    }
}

internal object DataImportWiring {

    fun execute(
        options: DataImportOptions,
        factory: DataImportWiringFactory = DefaultDataImportWiringFactory,
        recorder: CliAuditRecorder = cliAuditRecorder(options.configPath),
    ): Int = recorder.record("data.import", listOfNotNull(options.target)) {
        executeInner(options, factory)
    }

    private fun executeInner(
        options: DataImportOptions,
        factory: DataImportWiringFactory,
    ): Int {
        val bundle = factory.build(options.cliContext)
        val preflight = bundle.preflightFactory(bundle.schemaCodec)
        val request = DataImportRequest(
            target = options.target,
            source = options.source,
            format = options.format,
            schema = options.schema,
            table = options.table,
            tables = options.tables,
            tableOrder = options.tableOrder,
            onError = options.onError,
            onConflict = options.onConflict,
            triggerMode = options.triggerMode,
            truncate = options.truncate,
            atomic = options.atomic,
            disableFkChecks = options.disableFkChecks,
            reseedSequences = options.reseedSequences,
            encoding = options.encoding,
            csvNoHeader = options.csvNoHeader,
            csvNullString = options.csvNullString,
            chunkSize = options.chunkSize,
            parallel = options.parallel,
            cliConfigPath = options.configPath,
            quiet = options.cliContext.quiet,
            noProgress = options.cliContext.noProgress,
            resume = options.resume,
            checkpointDir = options.checkpointDir,
            noCheckpoint = options.noCheckpoint,
        )
        val runner = DataImportRunner(
            targetResolver = bundle.targetResolver,
            urlParser = bundle.urlParser,
            poolFactory = bundle.poolFactory,
            writerLookup = bundle.writerLookup,
            schemaPreflight = preflight::prepare,
            schemaTargetValidator = preflight::validateTargetTable,
            importExecutor = bundle.importExecutor,
            progressReporter = bundle.progressReporter,
            checkpointStoreFactory = bundle.checkpointStoreFactory,
            checkpointConfigResolver = bundle.checkpointConfigResolver,
            inputResolutionHook = ParquetImportInputResolutionHook(),
        )
        return runner.execute(request)
    }
}
