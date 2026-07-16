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
import dev.dmigrate.cli.config.resolveEffectiveDataPipeline

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
        help = "Output format (REQUIRED): json, yaml, csv, parquet",
    ).choice("json", "yaml", "csv", "parquet").required()

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
        help = "Rows per chunk (streaming buffer size); default: 10 000 " +
            "(overrides pipeline.chunk_size in config)",
    ).int()
    val fetchSize by option(
        "--fetch-size",
        help = "JDBC cursor prefetch size for reading the source (default: dialect-specific 1000; " +
            "overrides pipeline.fetch_size in config). SQLite: hint only.",
    ).int()

    val parallel by option(
        "--parallel",
        help = "Max tables/partitions to export concurrently (default: 1 = sequential; " +
            "overrides pipeline.parallelism in config). Keep <= the connection pool size " +
            "(default 10); clamped to 1 for SQLite; incompatible with --resume. " +
            "Per-child fan-out applies to --split-files only.",
    ).int()

    val readOnly by option(
        "--read-only",
        help = "Open the source read-only (default). SQLite: SQLITE_OPEN_READONLY, no -wal/-shm " +
            "side files. --no-read-only forces a read-write open.",
    ).flag("--no-read-only", default = true)

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

    val manifestSha256 by option(
        "--manifest-sha256",
        help = "Parquet bundles only: compute a SHA-256 digest per table file " +
            "and record it in manifest.yaml. Adds a second read pass over " +
            "every exported file; off by default.",
    ).flag()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        // LN-005 + pipeline.parallelism: chunk_size/fetch_size/parallelism in EINEM Config-Ladevorgang
        // mergen (CLI-explizit > Config > Default), statt die YAML pro Resolver erneut zu parsen.
        val pipeline = try {
            resolveEffectiveDataPipeline(root?.config, chunkSize, fetchSize, parallel)
        } catch (e: ConfigResolveException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(7)
        } catch (e: IllegalArgumentException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(2)
        }
        val tuning = pipeline.tuning
        val par = pipeline.parallelism
        val exitCode = DataExportWiring.execute(
            DataExportOptions(
                source = source,
                format = format,
                output = output,
                tables = tables,
                filter = filter,
                sinceColumn = sinceColumn,
                since = since,
                encoding = encoding,
                chunkSize = tuning.chunkSize,
                parallel = par.degree,
                parallelFromCli = par.fromCli,
                parallelSourceLabel = par.sourceLabel,
                readOnly = readOnly,
                fetchSize = tuning.fetchSize,
                splitFiles = splitFiles,
                csvDelimiter = csvDelimiter,
                csvBom = csvBom,
                csvNoHeader = csvNoHeader,
                nullString = nullString,
                resume = resume,
                checkpointDir = checkpointDir,
                manifestSha256 = manifestSha256,
                cliContext = root?.cliContext() ?: CliContext(),
                configPath = root?.config,
                pool = pipeline.pool,
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
