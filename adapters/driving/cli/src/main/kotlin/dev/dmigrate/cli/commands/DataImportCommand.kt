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
import dev.dmigrate.cli.config.ConfigMissingDefaultException
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.format.data.DefaultDataChunkReaderFactory
import dev.dmigrate.cli.output.MessageResolver
import dev.dmigrate.cli.output.ProgressRenderer
import dev.dmigrate.streaming.StreamingImporter

/**
 * `d-migrate data import` — streamt Daten aus Dateien (json/yaml/csv) oder
 * stdin in eine Zieldatenbank.
 *
 * Plan §3.7.1 / §6.11. Analog zum `DataExportCommand` ist dieser Command
 * eine dünne Clikt-Schale: er sammelt die CLI-Argumente in einen
 * [DataImportRequest] und delegiert an [DataImportRunner], der die gesamte
 * Geschäftslogik hält.
 */
class DataImportCommand : CliktCommand(name = "import") {
    override fun help(context: Context) =
        "Import data from JSON, YAML, or CSV into a database"

    val target by option(
        "--target",
        help = "Connection URL or named connection from .d-migrate.yaml; " +
            "default from database.default_target in config",
    )

    val source by option(
        "--source",
        help = "Source file, directory, or '-' for stdin",
    ).required()

    val format by option(
        "--format",
        help = "Input format: json, yaml, csv (auto-detected from file extension if omitted)",
    ).choice("json", "yaml", "csv")

    val schema by option(
        "--schema",
        help = "Optional schema file for local validation and directory import ordering",
    ).path()

    val table by option(
        "--table",
        help = "Target table name (required for stdin and single-file sources)",
    )

    val tables by option(
        "--tables",
        help = "Comma-separated list of tables to import (directory source only)",
    ).split(",")

    val onError by option(
        "--on-error",
        help = "Chunk error handling: abort (default), skip, log",
    ).choice("abort", "skip", "log").default("abort")

    val onConflict by option(
        "--on-conflict",
        help = "PK/unique conflict handling: abort (default), skip, update",
    ).choice("abort", "skip", "update")

    val triggerMode by option(
        "--trigger-mode",
        help = "Trigger handling: fire (default), disable (PG only), strict",
    ).choice("fire", "disable", "strict").default("fire")

    val truncate by option(
        "--truncate",
        help = "Truncate target table before import (non-atomic)",
    ).flag()

    val disableFkChecks by option(
        "--disable-fk-checks",
        help = "Disable FK checks during import (MySQL/SQLite only)",
    ).flag()

    val reseedSequences by option(
        "--reseed-sequences",
        help = "Reseed identity/sequence columns after import (default: true)",
    ).flag("--no-reseed-sequences", default = true)

    val encoding by option(
        "--encoding",
        help = "Input encoding (e.g. utf-8, iso-8859-1); default: auto-detect via BOM",
    )

    val csvNoHeader by option(
        "--csv-no-header",
        help = "CSV input has no header row; columns are positional",
    ).flag()

    val csvNullString by option(
        "--csv-null-string",
        help = "CSV NULL representation; default: empty string",
    ).default("")

    val chunkSize by option(
        "--chunk-size",
        help = "Rows per chunk (streaming buffer size); default: 10 000",
    ).int().default(10_000)

    // 0.9.0 Phase A (docs/ImpPlan-0.9.0-A.md §4.3/§4.4): Resume-Oberflaeche.
    // CLI-Vertrag ist in 0.9.0 Phase A definiert; die Resume-Runtime
    // (Checkpoint-Port, Manifest, Streaming-Wiederaufnahme) folgt in
    // Phase B bis D des Milestones.
    val resume by option(
        "--resume",
        help = "Resume an earlier import from a checkpoint reference " +
            "(file/directory source only; not supported with stdin `-`). " +
            "Accepts a checkpoint-id or a path; paths MUST be inside " +
            "the effective --checkpoint-dir / pipeline.checkpoint.directory.",
    )

    val checkpointDir by option(
        "--checkpoint-dir",
        help = "Directory for checkpoint storage. Overrides pipeline.checkpoint.directory " +
            "from the config file when set.",
    ).path()

    override fun run() {
        // Hierarchie: d-migrate → data → import → ZWEI parent-hops nach oben
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val writerLookup: (DatabaseDialect) -> DataWriter = { dialect ->
            DatabaseDriverRegistry.get(dialect).dataWriter()
        }
        val readerFactory = DefaultDataChunkReaderFactory()
        val request = DataImportRequest(
            target = target,
            source = source,
            format = format,
            schema = schema,
            table = table,
            tables = tables,
            onError = onError,
            onConflict = onConflict,
            triggerMode = triggerMode,
            truncate = truncate,
            disableFkChecks = disableFkChecks,
            reseedSequences = reseedSequences,
            encoding = encoding,
            csvNoHeader = csvNoHeader,
            csvNullString = csvNullString,
            chunkSize = chunkSize,
            cliConfigPath = root?.config,
            quiet = ctx.quiet,
            noProgress = ctx.noProgress,
            resume = resume,
            checkpointDir = checkpointDir,
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
            schemaPreflight = DataImportSchemaPreflight::prepare,
            schemaTargetValidator = DataImportSchemaPreflight::validateTargetTable,
            importExecutor = ImportExecutor {
                pool, input, fmt, opts, readOpts, cfg, onTableOpened, reporter,
                opId, resuming, skipped, resumeStates, onChunk, onDone,
                ->
                val importer = StreamingImporter(
                    readerFactory = readerFactory,
                    writerLookup = writerLookup,
                    onTableOpened = onTableOpened,
                )
                importer.import(
                    pool = pool,
                    input = input,
                    format = fmt,
                    options = opts,
                    readOptions = readOpts,
                    config = cfg,
                    progressReporter = reporter,
                    operationId = opId,
                    resuming = resuming,
                    skippedTables = skipped,
                    resumeStateByTable = resumeStates,
                    onChunkCommitted = onChunk,
                    onTableCompleted = onDone,
                )
            },
            progressReporter = ProgressRenderer(messages = MessageResolver(ctx.locale)),
            // 0.9.0 Phase D.1 (docs/ImpPlan-0.9.0-D.md §5.1):
            // dateibasierter CheckpointStore + Config-Resolver —
            // symmetrisch zum Export-Pfad.
            checkpointStoreFactory = { dir ->
                dev.dmigrate.streaming.checkpoint.FileCheckpointStore(dir)
            },
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
