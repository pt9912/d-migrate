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
import dev.dmigrate.core.version.VersionInfo
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.DefaultDataChunkWriterFactory
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.ValueSerializer
import dev.dmigrate.format.parquet.ParquetChunkWriterFactory
import dev.dmigrate.format.parquet.manifest.ParquetBundleClosure
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter
import dev.dmigrate.streaming.ExportOutput
import dev.dmigrate.streaming.StreamingExporter
import dev.dmigrate.streaming.checkpoint.FileCheckpointStore
import java.io.OutputStream
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
            writerFactoryBuilder = { exportOutput ->
                buildWriterFactoryForOutput(exportOutput, warnings)
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
                        // ParquetBundleClosure schreibt manifest.yaml und
                        // ignoriert Nicht-Parquet-Formate selbst (AP7 §10.1).
                        onBundleClosure = ParquetBundleClosure(
                            producerVersion = VersionInfo.PRODUCT_VERSION,
                        )::invoke,
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
     * Output-Mode-aware Parquet-Factory-Builder. Subklasse-Branching folgt
     * `docs/adr/0005-writerfactorybuilder-output-mode-invariant.md`:
     * Single-File bekommt den Footer-KV-Provider, FilePerTable den
     * Default. Der Stdout-Zweig setzt eine Unreachable-Sentinel-Factory,
     * weil `DataExportRunner.validateRequest` PARQUET+Stdout bereits via
     * `requiresSeekableOutput` ablehnt — der Composite wuerde die
     * Parquet-Factory hier nur erreichen, wenn ein zukuenftiger Refactor
     * den Validator-Guard schwaecht. `warningSink` wird symmetrisch zur
     * Default-Factory geteilt.
     */
    private fun buildWriterFactoryForOutput(
        exportOutput: ExportOutput,
        warnings: MutableList<ValueSerializer.Warning>,
    ): DataChunkWriterFactory {
        val sink: (ValueSerializer.Warning) -> Unit = { warnings += it }
        val parquetFactory = when (exportOutput) {
            is ExportOutput.SingleFile -> ParquetChunkWriterFactory(
                warningSink = sink,
                extraMetaDataProvider = ParquetSingleFileManifestWriter(
                    producerVersion = VersionInfo.PRODUCT_VERSION,
                ).provider,
            )
            is ExportOutput.FilePerTable -> ParquetChunkWriterFactory(warningSink = sink)
            is ExportOutput.Stdout -> UnreachableParquetWriterFactory
        }
        return CompositeDataChunkWriterFactory(
            defaultFactory = DefaultDataChunkWriterFactory(warningSink = sink),
            parquetFactory = parquetFactory,
        )
    }

    /**
     * Fail-fast-Sentinel: PARQUET+Stdout wird upstream von
     * `DataExportRunner.validateRequest` (requiresSeekableOutput) abgelehnt.
     * Erreicht der Composite trotzdem diese Factory, ist die Validator-
     * Invariante gebrochen — wir werfen IllegalStateException
     * (ADR-0006 Wiring-Drift-Exception-Familie) statt eine halbgare
     * Parquet-Datei nach stdout zu streamen.
     */
    private object UnreachableParquetWriterFactory : DataChunkWriterFactory {
        override fun create(
            format: DataExportFormat,
            output: OutputStream,
            options: ExportOptions,
        ): DataChunkWriter = error(
            "unreachable: PARQUET+Stdout is rejected by " +
                "DataExportRunner.validateRequest (requiresSeekableOutput). " +
                "If this throws, the validator guard was removed without " +
                "updating buildWriterFactoryForOutput."
        )
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
