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
import dev.dmigrate.cli.config.resolveEffectiveChunkSize
import dev.dmigrate.cli.config.resolveEffectiveParallelism

/**
 * `d-migrate data import` — streamt Daten aus Dateien (json/yaml/csv) oder
 * stdin in eine Zieldatenbank.
 *
 * LF-010 / LF-013 / LN-009 / LN-011. Analog zum `DataExportCommand` ist dieser Command
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
        help = "Input format: json, yaml, csv, parquet " +
            "(auto-detected from file extension or directory manifest.yaml if omitted)",
    ).choice("json", "yaml", "csv", "parquet")

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

    val tableOrder by option(
        "--table-order",
        help = "Comma-separated explicit import order (directory source only). " +
            "Authoritative over the --schema FK-topological sort; --schema then " +
            "only validates. Must be a permutation of the imported tables.",
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

    // LN-013: atomarer Clean-Load — bei Fehler alle Tabellen auf leer zurück.
    val atomic by option(
        "--atomic",
        help = "Atomic clean-load: on any error, roll back all target tables to empty. " +
            "Requires --truncate; not compatible with --resume.",
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
        help = "Rows per chunk (streaming buffer size); default: 10 000 " +
            "(overrides pipeline.chunk_size in config)",
    ).int()

    val parallel by option(
        "--parallel",
        help = "Max tables/partitions to import concurrently (default: 1 = sequential; " +
            "overrides pipeline.parallelism in config). Clamped to 1 for SQLite; " +
            "incompatible with --resume and --atomic.",
    ).int()

    // LF-010 / LF-013 / LN-012: Resume-Oberflaeche fuer Datei- und
    // Directory-Importe. Stdin bleibt ausgeschlossen, weil kein
    // stabiler Input-Pfad fuer Wiederaufnahme existiert.
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

    // Parquet Cut A S6 (AP12 §4.2): explizit Checkpoint-Persistenz fuer den
    // aktuellen Lauf abschalten. Konfliktet mit --resume (Exit 2 in
    // validateCliFlags). Implizit: Phase-1 berechnet keinen contentSha256.
    val noCheckpoint by option(
        "--no-checkpoint",
        help = "Disable checkpoint reads/writes for this run. Mutually exclusive with --resume.",
    ).flag()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        // LN-005: --chunk-size (nullbar) mit pipeline.chunk_size mergen (CLI > Config > Default).
        // Bewusst NUR chunk_size: der Import liest aus Format-Dateien (kein JDBC-DataReader), also
        // darf ein für ihn irrelevanter pipeline.fetch_size-Config-Fehler den Import nicht scheitern lassen.
        val effectiveChunkSize = try {
            resolveEffectiveChunkSize(root?.config, chunkSize)
        } catch (e: ConfigResolveException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(7)
        } catch (e: IllegalArgumentException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(2)
        }
        val par = try {
            resolveEffectiveParallelism(root?.config, parallel)
        } catch (e: ConfigResolveException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(7)
        } catch (e: IllegalArgumentException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(2)
        }
        val exitCode = DataImportWiring.execute(
            DataImportOptions(
                target = target,
                source = source,
                format = format,
                schema = schema,
                table = table,
                tables = tables,
                tableOrder = tableOrder,
                onError = onError,
                onConflict = onConflict,
                triggerMode = triggerMode,
                truncate = truncate,
                atomic = atomic,
                disableFkChecks = disableFkChecks,
                reseedSequences = reseedSequences,
                encoding = encoding,
                csvNoHeader = csvNoHeader,
                csvNullString = csvNullString,
                chunkSize = effectiveChunkSize,
                parallel = par.degree,
                parallelFromCli = par.fromCli,
                parallelSourceLabel = par.sourceLabel,
                resume = resume,
                checkpointDir = checkpointDir,
                noCheckpoint = noCheckpoint,
                cliContext = root?.cliContext() ?: CliContext(),
                configPath = root?.config,
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
