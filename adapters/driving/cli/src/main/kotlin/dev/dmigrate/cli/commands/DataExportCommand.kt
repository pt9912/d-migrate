package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.format.data.DefaultDataChunkWriterFactory
import dev.dmigrate.format.data.ValueSerializer
import dev.dmigrate.cli.output.MessageResolver
import dev.dmigrate.cli.output.ProgressRenderer
import dev.dmigrate.streaming.StreamingExporter

/**
 * `d-migrate data export` — streamt Tabellen aus einer Datenbank in eines
 * der drei unterstützten Formate (json/yaml/csv).
 *
 * Der Command ist eine dünne Clikt-Schale: er sammelt die CLI-Argumente
 * in einen [DataExportRequest] und delegiert an [DataExportRunner], damit
 * die Geschäftslogik unabhängig von Clikt, Filesystem und Datenbank
 * getestet werden kann.
 */
class DataExportCommand : CliktCommand(name = "export") {
    override fun help(context: Context) =
        "Export tables from a database to JSON, YAML, or CSV"

    val source by option(
        "--source",
        help = "Connection URL or named connection from .d-migrate.yaml",
    ).required()

    val format by option(
        "--format",
        help = "Output format (REQUIRED): json, yaml, csv",
    ).choice("json", "yaml", "csv").required()

    val output by option(
        "--output", "-o",
        help = "Output file (single table) or directory (with --split-files); default: stdout",
    ).path()

    val tables by option(
        "--tables",
        help = "Comma-separated list of tables to export; default: all",
    ).split(",")

    val filter by option(
        "--filter",
        help = "Filter DSL expression applied to all tables. Supports comparisons (=, !=, >, <, >=, <=), " +
            "IN (...), IS NULL, IS NOT NULL, AND, OR, NOT, parentheses, arithmetic, and functions " +
            "(LOWER, UPPER, TRIM, LENGTH, ABS, ROUND, COALESCE). All literals are bound as JDBC parameters.",
    )

    val sinceColumn by option(
        "--since-column",
        help = "Marker column for incremental export; must be used together with --since",
    )

    val since by option(
        "--since",
        help = "Lower-bound marker value for incremental export; must be used together with --since-column",
    )

    val encoding by option(
        "--encoding",
        help = "Output encoding (e.g. utf-8, iso-8859-1); default: utf-8",
    ).default("utf-8")

    val chunkSize by option(
        "--chunk-size",
        help = "Rows per chunk (streaming buffer size); default: 10 000",
    ).int().default(10_000)

    val splitFiles by option(
        "--split-files",
        help = "Write one file per table into the --output directory",
    ).flag()

    val csvDelimiter by option(
        "--csv-delimiter",
        help = "CSV column delimiter; default: ','",
    ).default(",")

    val csvBom by option(
        "--csv-bom",
        help = "Prefix CSV output with a BOM matching --encoding " +
            "(UTF-8, UTF-16 BE/LE); no-op for other encodings",
    ).flag()

    val csvNoHeader by option("--csv-no-header", help = "Omit the CSV header row").flag()

    val nullString by option(
        "--null-string",
        help = "CSV NULL representation; default: empty string",
    ).default("")

    val resume by option(
        "--resume",
        help = "Resume an earlier export from a checkpoint reference " +
            "(file-based only; not supported with stdout). " +
            "Accepts a checkpoint-id or a path; paths MUST be inside " +
            "the effective --checkpoint-dir / pipeline.checkpoint.directory.",
    )

    val checkpointDir by option(
        "--checkpoint-dir",
        help = "Directory for checkpoint storage. Overrides pipeline.checkpoint.directory " +
            "from the config file when set.",
    ).path()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        if (filter != null && filter!!.isBlank()) {
            System.err.println("Error: --filter must not be empty or whitespace-only. Omit the flag to export without a filter.")
            throw ProgramResult(2)
        }
        val parsedFilter = try {
            parseFilter(filter)
        } catch (e: FilterParseException) {
            val err = e.parseError
            val posHint = if (err.index != null) " (at position ${err.index})" else ""
            System.err.println("Error: Invalid --filter expression${posHint}: ${err.message}")
            throw ProgramResult(2)
        }
        val request = DataExportRequest(
            source = source,
            format = format,
            output = output,
            tables = tables,
            filter = parsedFilter,
            sinceColumn = sinceColumn,
            since = since,
            encoding = encoding,
            chunkSize = chunkSize,
            splitFiles = splitFiles,
            csvDelimiter = csvDelimiter,
            csvBom = csvBom,
            csvNoHeader = csvNoHeader,
            nullString = nullString,
            cliConfigPath = root?.config,
            quiet = ctx.quiet,
            noProgress = ctx.noProgress,
            resume = resume,
            checkpointDir = checkpointDir,
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
            writerFactoryBuilder = { DefaultDataChunkWriterFactory(warningSink = { warnings += it }) },
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
            progressReporter = ProgressRenderer(messages = MessageResolver(ctx.locale)),
            checkpointStoreFactory = { dir ->
                dev.dmigrate.streaming.checkpoint.FileCheckpointStore(dir)
            },
            checkpointConfigResolver = { cliCfg ->
                dev.dmigrate.cli.config.PipelineCheckpointResolver(
                    configPathFromCli = cliCfg,
                ).resolve()
            },
            primaryKeyLookup = pkLookupFromSchemaReader(),
        )
        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }

    /**
     * Lädt das Schema beim ersten Aufruf einmal und liest Primary Keys
     * danach aus einem lokalen Cache.
     */
    private fun pkLookupFromSchemaReader(): (
        dev.dmigrate.driver.connection.ConnectionPool,
        dev.dmigrate.driver.DatabaseDialect,
        String,
    ) -> List<String> {
        var cache: Map<String, List<String>>? = null
        return { pool, dialect, table ->
            val resolved = cache ?: run {
                val loaded = try {
                    val reader = DatabaseDriverRegistry.get(dialect).schemaReader()
                    val result = reader.read(pool)
                    result.schema.tables.mapValues { (_, def) -> def.primaryKey }
                } catch (_: Throwable) {
                    emptyMap<String, List<String>>()
                }
                cache = loaded
                loaded
            }
            // Wir probieren schema-qualifizierte und unqualifizierte
            // Keys, weil --tables beide Formen akzeptiert.
            resolved[table]
                ?: resolved[table.substringAfterLast('.')]
                ?: emptyList()
        }
    }
}
