package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.config.PipelineCheckpointResolver
import dev.dmigrate.cli.output.MessageResolver
import dev.dmigrate.cli.output.ProgressRenderer
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.format.data.DefaultDataChunkWriterFactory
import dev.dmigrate.format.data.ValueSerializer
import dev.dmigrate.format.parquet.ParquetChunkWriterFactory
import dev.dmigrate.streaming.StreamingExporter
import dev.dmigrate.streaming.checkpoint.FileCheckpointStore
import java.nio.file.Path

/**
 * Snapshot of all CLI flags the [DataExportCommand] collected, plus the
 * resolved [CliContext] and config path. Keeps [DataExportWiring.execute]
 * Clikt-free and unit-testable.
 */
internal data class DataExportOptions(
    val source: String,
    val format: String,
    val output: Path?,
    val tables: List<String>?,
    val filter: String?,
    val sinceColumn: String?,
    val since: String?,
    val encoding: String,
    val chunkSize: Int,
    val splitFiles: Boolean,
    val csvDelimiter: String,
    val csvBom: Boolean,
    val csvNoHeader: Boolean,
    val nullString: String,
    val resume: String?,
    val checkpointDir: Path?,
    val cliContext: CliContext,
    val configPath: Path?,
)

/**
 * Pure wiring for `data export`: filter validation, request construction,
 * runner assembly. Decoupled from Clikt so tests can drive it directly.
 *
 * Returns the process exit code; the caller is responsible for surfacing
 * a non-zero code as `ProgramResult` to Clikt.
 */
internal object DataExportWiring {

    fun execute(options: DataExportOptions): Int {
        if (options.filter != null && options.filter.isBlank()) {
            System.err.println(
                "Error: --filter must not be empty or whitespace-only. Omit the flag to export without a filter."
            )
            return 2
        }
        val parsedFilter = try {
            parseFilter(options.filter)
        } catch (e: FilterParseException) {
            val err = e.parseError
            val posHint = if (err.index != null) " (at position ${err.index})" else ""
            System.err.println("Error: Invalid --filter expression${posHint}: ${err.message}")
            return 2
        }
        val request = DataExportRequest(
            source = options.source,
            format = options.format,
            output = options.output,
            tables = options.tables,
            filter = parsedFilter,
            sinceColumn = options.sinceColumn,
            since = options.since,
            encoding = options.encoding,
            chunkSize = options.chunkSize,
            splitFiles = options.splitFiles,
            csvDelimiter = options.csvDelimiter,
            csvBom = options.csvBom,
            csvNoHeader = options.csvNoHeader,
            nullString = options.nullString,
            cliConfigPath = options.configPath,
            quiet = options.cliContext.quiet,
            noProgress = options.cliContext.noProgress,
            resume = options.resume,
            checkpointDir = options.checkpointDir,
        )
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val runner = DataExportRunner(
            sourceResolver = { source, configPath ->
                try {
                    NamedConnectionResolver(configPathFromCli = configPath).resolve(source)
                } catch (e: ConfigResolveException) {
                    throw IllegalArgumentException(e.message, e)
                }
            },
            urlParser = ConnectionUrlParser::parse,
            poolFactory = HikariConnectionPoolFactory::create,
            readerLookup = { DatabaseDriverRegistry.get(it).dataReader() },
            listerLookup = { DatabaseDriverRegistry.get(it).tableLister() },
            writerFactoryBuilder = {
                CompositeDataChunkWriterFactory(
                    defaultFactory = DefaultDataChunkWriterFactory(warningSink = { warnings += it }),
                    parquetFactory = ParquetChunkWriterFactory(),
                )
            },
            collectWarnings = {
                warnings.map {
                    "  ⚠ ${it.code} ${it.table}.${it.column} (${it.javaClass}): ${it.message}"
                }
            },
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                StreamingExporter(ctx.reader, ctx.lister, ctx.factory)
                    .export(
                        pool = ctx.pool,
                        tables = opts.tables,
                        output = opts.output,
                        format = opts.format,
                        options = opts.options,
                        config = opts.config,
                        filter = opts.filter,
                        progressReporter = callbacks.progressReporter,
                        operationId = resume.operationId,
                        resuming = resume.resuming,
                        skippedTables = resume.skippedTables,
                        onTableCompleted = callbacks.onTableCompleted,
                        resumeMarkers = resume.resumeMarkers,
                        onChunkProcessed = callbacks.onChunkProcessed,
                        warningSink = callbacks.warningSink,
                    )
            },
            progressReporter = ProgressRenderer(messages = MessageResolver(options.cliContext.locale)),
            checkpointStoreFactory = { dir -> FileCheckpointStore(dir) },
            checkpointConfigResolver = { cliCfg ->
                PipelineCheckpointResolver(configPathFromCli = cliCfg).resolve()
            },
            primaryKeyLookup = pkLookupFromSchemaReader(),
        )
        return runner.execute(request)
    }

    /**
     * Loads the schema once on first invocation and caches per-table primary
     * keys for subsequent calls within the same export.
     */
    private fun pkLookupFromSchemaReader(): (ConnectionPool, DatabaseDialect, String) -> List<String> {
        var cache: Map<String, List<String>>? = null
        return { pool, dialect, table ->
            val resolved = cache ?: run {
                val loaded = try {
                    val reader = DatabaseDriverRegistry.get(dialect).schemaReader()
                    val result = reader.read(pool)
                    result.schema.tables.mapValues { (_, def) -> def.primaryKey }
                } catch (_: Throwable) {
                    emptyMap()
                }
                cache = loaded
                loaded
            }
            resolved[table]
                ?: resolved[table.substringAfterLast('.')]
                ?: emptyList()
        }
    }
}
