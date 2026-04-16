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
 * Plan §3.6 / §6.10 / §6.14 / §6.15 / §6.17. Der Command ist eine dünne
 * Clikt-Schale: er sammelt die CLI-Argumente in einen [DataExportRequest]
 * und delegiert an [DataExportRunner], der die gesamte Geschäftslogik
 * hält. Dieser Split existiert, damit alle Exit-Code-Pfade ohne echten
 * Connection-Pool, ohne Datenbank und ohne Clikt-Kontext testbar sind —
 * siehe `DataExportRunnerTest`.
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
        help = "Raw SQL WHERE clause applied to all tables (without the 'WHERE' keyword). " +
            "WARNING: not parameterized — see Plan §6.7 for the trust-boundary contract.",
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

    val csvNoHeader by option(
        "--csv-no-header",
        help = "Omit the CSV header row (§6.17 default: header on)",
    ).flag()

    val nullString by option(
        "--null-string",
        help = "CSV NULL representation; default: empty string",
    ).default("")

    // 0.9.0 Phase A (docs/ImpPlan-0.9.0-A.md §4.3/§4.4): Resume-Oberflaeche.
    // CLI-Vertrag ist in 0.9.0 Phase A definiert; die Resume-Runtime
    // (Checkpoint-Port, Manifest, Streaming-Wiederaufnahme) folgt in
    // Phase B bis D des Milestones.
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
        // Hierarchie: d-migrate → data → export → ZWEI parent-hops nach oben
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val request = DataExportRequest(
            source = source,
            format = format,
            output = output,
            tables = tables,
            filter = filter,
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
            exportExecutor = ExportExecutor { pool, reader, lister, factory, tbls, out, fmt, opts, cfg, flt, reporter, opId, resuming, skipped, onDone ->
                StreamingExporter(reader, lister, factory)
                    .export(pool, tbls, out, fmt, opts, cfg, flt, reporter, opId, resuming, skipped, onDone)
            },
            progressReporter = ProgressRenderer(messages = MessageResolver(ctx.locale)),
            // 0.9.0 Phase C.1: dateibasierter CheckpointStore. Die CLI-
            // Seite wired den produktiven Adapter hier ein; der
            // Runner selbst kennt `FileCheckpointStore` nicht (Hex-
            // Richtung: application -> ports, nicht application -> adapters).
            checkpointStoreFactory = { dir ->
                dev.dmigrate.streaming.checkpoint.FileCheckpointStore(dir)
            },
            // 0.9.0 Phase C.1 §4.4 / §5.2: Config-Resolver fuer
            // `pipeline.checkpoint.*` — der Runner ruft
            // `CheckpointConfig.merge(cliDirectory, config)` und sticht
            // die Config damit in `--checkpoint-dir` um.
            checkpointConfigResolver = { cliCfg ->
                dev.dmigrate.cli.config.PipelineCheckpointResolver(
                    configPathFromCli = cliCfg,
                ).resolve()
            },
        )
        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
