package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.DataReaderRegistry
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.DefaultDataChunkWriterFactory
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.ValueSerializer
import dev.dmigrate.streaming.ExportOutput
import dev.dmigrate.streaming.ExportResult
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.StreamingExporter
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * `data` Top-Level-Kommando — bündelt alle Subcommands rund um Datenexport
 * (0.3.0) und Datenimport (folgt in 0.4.0).
 *
 * Plan §3.6 — die Hierarchie ist `d-migrate → data → export`. Beim
 * Zugriff auf das Root-Command `DMigrate` aus `DataExportCommand` müssen
 * deshalb ZWEI parent-hops über den `currentContext` gemacht werden.
 */
class DataCommand : CliktCommand(name = "data") {
    override fun help(context: Context) = "Data export and import commands"

    init {
        subcommands(DataExportCommand())
    }

    override fun run() = Unit
}

/**
 * `d-migrate data export` — streamt Tabellen aus einer Datenbank in eines
 * der drei unterstützten Formate (json/yaml/csv).
 *
 * Plan §3.6 / §6.10 / §6.14 / §6.15 / §6.17. Die Implementierung ist eine
 * dünne Glue-Schicht über [StreamingExporter] und implementiert nur:
 *
 * 1. Auflösung des Root-Command für `--config`/`-c`-Zugriff
 * 2. [NamedConnectionResolver] (§6.14)
 * 3. URL → [ConnectionConfig] → [ConnectionPool] über [HikariConnectionPoolFactory]
 * 4. Tabellen-Auflösung (`--tables` oder [TableLister.listTables])
 * 5. [ExportOutput.resolve] für `--output × --split-files × tableCount`
 * 6. [ExportOptions]-Aufbau aus `--csv-*` / `--encoding` / `--null-string`
 * 7. Aufruf von [StreamingExporter.export] mit dem Pool
 * 8. Exit-Code-Mapping nach §6.10:
 *    - 0 bei Erfolg
 *    - 2 bei CLI-Fehler (Clikt's [UsageError] regelt das automatisch)
 *    - 4 bei Connection-Fehler (HikariCP)
 *    - 5 bei Export-Fehler während Streaming
 *    - 7 bei Konfigurationsfehler ([ConfigResolveException], URL-Parser)
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
        help = "Prefix CSV output with a UTF-8 BOM",
    ).flag()

    val csvNoHeader by option(
        "--csv-no-header",
        help = "Omit the CSV header row (§6.17 default: header on)",
    ).flag()

    val nullString by option(
        "--null-string",
        help = "CSV NULL representation; default: empty string",
    ).default("")

    override fun run() {
        // ─── 0. Root-Command-Zugriff (analog zu SchemaValidateCommand) ───
        // Hierarchie: d-migrate → data → export → ZWEI parent-hops nach oben
        val root = currentContext.parent?.parent?.command as? DMigrate
        val cliConfigPath: Path? = root?.config

        // ─── 1. Source auflösen → vollständige Connection-URL ────────────
        val resolver = NamedConnectionResolver(configPathFromCli = cliConfigPath)
        val resolvedUrl = try {
            resolver.resolve(source)
        } catch (e: ConfigResolveException) {
            System.err.println("Error: ${e.message}")
            throw ProgramResult(7)
        }

        // ─── 2. URL → ConnectionConfig (Exit 7 bei Parser-Fehler) ────────
        val connectionConfig = try {
            ConnectionUrlParser.parse(resolvedUrl)
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            throw ProgramResult(7)
        }

        // ─── 3. Encoding parsen (Exit 2 bei ungültigem Charset) ──────────
        val charset = try {
            Charset.forName(encoding)
        } catch (e: Exception) {
            System.err.println("Error: Unknown encoding '$encoding': ${e.message}")
            throw ProgramResult(2)
        }

        // ─── 4. ConnectionPool öffnen (Exit 4 bei Connection-Fehler) ─────
        val pool: ConnectionPool = try {
            HikariConnectionPoolFactory.create(connectionConfig)
        } catch (e: Throwable) {
            System.err.println("Error: Failed to connect to database: ${e.message}")
            throw ProgramResult(4)
        }

        try {
            // ─── 5. Reader + Lister aus Registry ─────────────────────────
            val reader = try {
                DataReaderRegistry.dataReader(connectionConfig.dialect)
            } catch (e: IllegalArgumentException) {
                System.err.println("Error: ${e.message}")
                throw ProgramResult(7)
            }
            val tableLister = try {
                DataReaderRegistry.tableLister(connectionConfig.dialect)
            } catch (e: IllegalArgumentException) {
                System.err.println("Error: ${e.message}")
                throw ProgramResult(7)
            }

            // ─── 6. Tabellen ermitteln (--tables oder Auto-Discovery) ────
            // F33 / Plan §6.7: --tables wird strikt validiert. Nur Identifier
            // im Muster `[A-Za-z_][A-Za-z0-9_]*` (optional schema-qualifiziert
            // als `schema.table`) sind erlaubt. Auto-discovered tables aus
            // dem TableLister werden NICHT gegen das Pattern geprüft, weil
            // sie aus dem information_schema kommen und keine User-Eingabe
            // sind.
            val explicitTables = tables?.takeIf { it.isNotEmpty() }
            if (explicitTables != null) {
                val invalid = explicitTables.firstOrNull { !TABLE_IDENTIFIER.matches(it) }
                if (invalid != null) {
                    System.err.println(
                        "Error: --tables value '$invalid' is not a valid identifier. " +
                            "Expected '<name>' or '<schema>.<name>' matching $TABLE_IDENTIFIER_PATTERN."
                    )
                    throw ProgramResult(2)
                }
            }
            val effectiveTables = explicitTables ?: try {
                tableLister.listTables(pool)
            } catch (e: Throwable) {
                System.err.println("Error: Failed to list tables: ${e.message}")
                throw ProgramResult(4)
            }

            if (effectiveTables.isEmpty()) {
                System.err.println("Error: No tables to export.")
                throw ProgramResult(2)
            }

            // ─── 7. ExportOutput auflösen (Exit 2 bei Konflikt) ──────────
            val exportOutput = try {
                ExportOutput.resolve(
                    outputPath = output,
                    splitFiles = splitFiles,
                    tableCount = effectiveTables.size,
                )
            } catch (e: IllegalArgumentException) {
                System.err.println("Error: ${e.message}")
                throw ProgramResult(2)
            }

            // ─── 8. ExportOptions aus den CLI-Flags bauen ────────────────
            // F35: harte Validierung mit klarem Exit-2-Mapping statt rohem
            // require()/IllegalArgumentException.
            if (csvDelimiter.length != 1) {
                System.err.println(
                    "Error: --csv-delimiter must be a single character, got '$csvDelimiter'"
                )
                throw ProgramResult(2)
            }
            val exportOptions = ExportOptions(
                encoding = charset,
                csvHeader = !csvNoHeader,
                csvDelimiter = csvDelimiter[0],
                csvBom = csvBom,
                csvNullString = nullString,
            )

            val warnings = mutableListOf<ValueSerializer.Warning>()
            val factory = DefaultDataChunkWriterFactory(warningSink = { warnings += it })

            // F32 / Plan §6.7: --filter wird unverändert als Roh-WHERE-Klausel
            // an alle Tabellen weitergegeben. Trust-Boundary ist die lokale
            // Shell — Re-Validation an einer zukünftigen REST-API ist eine
            // separate Voraussetzung (siehe §6.7 Forward-Compat-Hinweis).
            val effectiveFilter: DataFilter? = filter?.takeIf { it.isNotBlank() }
                ?.let { DataFilter.WhereClause(it) }

            // ─── 9. Streamen ──────────────────────────────────────────────
            val exporter = StreamingExporter(reader, tableLister, factory)
            val result: ExportResult = try {
                exporter.export(
                    pool = pool,
                    tables = effectiveTables,
                    output = exportOutput,
                    format = DataExportFormat.fromCli(format),
                    options = exportOptions,
                    config = PipelineConfig(chunkSize = chunkSize),
                    filter = effectiveFilter,
                )
            } catch (e: Throwable) {
                System.err.println("Error: Export failed: ${e.message}")
                throw ProgramResult(5)
            }

            // ─── 10. Pro-Tabelle-Fehler → Exit 5 ─────────────────────────
            val failed = result.tables.firstOrNull { it.error != null }
            if (failed != null) {
                System.err.println(
                    "Error: Failed to export table '${failed.table}': ${failed.error}"
                )
                throw ProgramResult(5)
            }

            val ctx = root?.cliContext()

            // ─── 11. Warnings auf stderr ausgeben ────────────────────────
            // F37: --quiet bedeutet laut cli-spec §1.3 "Nur Fehler" — also
            // unterdrücken wir hier sowohl die ValueSerializer-Warnings als
            // auch die ProgressSummary unten. Echte Fehlermeldungen
            // (Exit 2/4/5/7) bleiben unverändert sichtbar.
            if (ctx?.quiet != true) {
                for (warning in warnings) {
                    System.err.println(
                        "  ⚠ ${warning.code} ${warning.table}.${warning.column} (${warning.javaClass}): ${warning.message}"
                    )
                }
            }

            // ─── 12. ProgressSummary auf stderr (Plan §6.10 / §3.6) ──────
            // F34: ProgressSummary wird sowohl bei --quiet als auch bei
            // --no-progress unterdrückt (cli-spec.md §1.3 / §6.4).
            val suppressProgress = ctx?.quiet == true || ctx?.noProgress == true
            if (!suppressProgress) {
                System.err.println(formatProgressSummary(result))
            }
        } finally {
            try { pool.close() } catch (_: Throwable) {}
        }
    }

    private fun formatProgressSummary(result: ExportResult): String {
        val mb = result.totalBytes.toDouble() / (1024 * 1024)
        val seconds = result.durationMs.toDouble() / 1000
        return "Exported ${result.tables.size} table(s) " +
            "(${result.totalRows} rows, ${"%.2f".format(mb)} MB) " +
            "in ${"%.2f".format(seconds)} s"
    }

    companion object {
        /**
         * F33 / Plan §6.7: Identifier-Pattern für `--tables`. Erlaubt
         * `<name>` und `<schema>.<name>`. Beide Segmente folgen den
         * SQL-Identifier-Regeln (`[A-Za-z_][A-Za-z0-9_]*`). Der Plan nennt
         * `weird name` (mit Whitespace) explizit als abzulehnenden Wert.
         */
        internal const val TABLE_IDENTIFIER_PATTERN =
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$"
        internal val TABLE_IDENTIFIER = Regex(TABLE_IDENTIFIER_PATTERN)
    }
}
