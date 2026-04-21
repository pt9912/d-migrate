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
        // §4.4: --filter "" and whitespace-only are invalid (Exit 2)
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
                    )
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
            // 0.9.0 Phase C.2 §5.3: PK-Lookup fuer den Runner. Die
            // produktive Wiring laedt das Schema genau einmal pro
            // Pool+Dialekt, damit mehrfache `primaryKeyLookup`-
            // Aufrufe (pro Tabelle) nicht wiederholt den
            // SchemaReader triggern. Ein Fehlschlag beim Schema-Load
            // mappt auf "keine PK bekannt" — der Runner faellt dann
            // per Phase-C.2-§4.1-Fall-2 auf C.1-Verhalten zurueck.
            primaryKeyLookup = pkLookupFromSchemaReader(),
        )
        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }

    /**
     * 0.9.0 Phase C.2 §5.3: produktive PK-Quelle. Liest das Schema
     * beim **ersten** Aufruf genau einmal per
     * `DatabaseDriverRegistry.get(dialect).schemaReader()` ein und
     * merkt sich das Ergebnis in einem local-scoped Cache. Jeder
     * spaetere Aufruf liefert die PK aus dem Cache.
     *
     * Ein Fehlschlag beim Schema-Laden degradiert auf "keine PK
     * bekannt" — der Runner greift dann Phase-C.2-§4.1-Fall-2
     * (C.1-Fallback mit stderr-Hinweis). Kein Exit, der Lauf geht
     * weiter.
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
            // Keys, weil --tables beide Formen akzeptiert (§3.6).
            resolved[table]
                ?: resolved[table.substringAfterLast('.')]
                ?: emptyList()
        }
    }
}
