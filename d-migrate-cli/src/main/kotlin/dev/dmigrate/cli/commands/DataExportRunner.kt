package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataReaderRegistry
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriterFactory
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
 * Dünne Naht über [StreamingExporter.export], damit der
 * `catch (Throwable) → Exit 5`-Pfad im [DataExportRunner] mit einem Fake
 * getestet werden kann, ohne dass [StreamingExporter] `open` werden muss.
 *
 * Production-Default baut einen echten [StreamingExporter] und ruft dessen
 * `export` auf; Tests übergeben eine eigene Lambda, die z.B. throws wirft
 * oder ein vorgefertigtes [ExportResult] zurückliefert.
 */
internal fun interface ExportExecutor {
    fun execute(
        pool: ConnectionPool,
        reader: DataReader,
        lister: TableLister,
        factory: DataChunkWriterFactory,
        tables: List<String>,
        output: ExportOutput,
        format: DataExportFormat,
        options: ExportOptions,
        config: PipelineConfig,
        filter: DataFilter?,
    ): ExportResult
}

/**
 * Production-Implementierung von [ExportExecutor] — delegiert an einen
 * echten [StreamingExporter].
 */
internal val defaultExportExecutor: ExportExecutor =
    ExportExecutor { pool, reader, lister, factory, tables, output, format, options, config, filter ->
        StreamingExporter(reader, lister, factory).export(
            pool = pool,
            tables = tables,
            output = output,
            format = format,
            options = options,
            config = config,
            filter = filter,
        )
    }

/**
 * Immutable DTO mit allen CLI-Eingaben für `d-migrate data export`.
 *
 * [DataExportCommand] baut aus seinen Clikt-Feldern einen Request und
 * delegiert an [DataExportRunner] — der Command bleibt dadurch ein reiner
 * Argument-Mapper, alle Verzweigungs- und Fehlerlogik sitzt im Runner.
 */
internal data class DataExportRequest(
    val source: String,
    val format: String,
    val output: Path?,
    val tables: List<String>?,
    val filter: String?,
    val encoding: String,
    val chunkSize: Int,
    val splitFiles: Boolean,
    val csvDelimiter: String,
    val csvBom: Boolean,
    val csvNoHeader: Boolean,
    val nullString: String,
    val cliConfigPath: Path?,
    val quiet: Boolean,
    val noProgress: Boolean,
)

/**
 * Kern-Logik für `d-migrate data export`. Delegiert I/O und Registry-
 * Zugriffe über konstruktor-injizierte Collaborators, damit alle
 * Verzweigungen (inkl. Fehlerpfade und Exit-Codes) ohne echte Datenbank,
 * ohne HikariCP und ohne Clikt-Kontext unit-testbar sind.
 *
 * Der Runner gibt einen Integer-Exit-Code zurück (0 bei Erfolg, 2/4/5/7
 * bei Fehlern gemäß Plan §6.10) und schreibt Fehlermeldungen, Warnings
 * und die ProgressSummary über die injizierte [stderr]-Lambda.
 *
 * Production-Defaults zeigen auf die echten Kollaborateure
 * ([HikariConnectionPoolFactory], [DataReaderRegistry], [StreamingExporter],
 * [DefaultDataChunkWriterFactory]); Tests überschreiben sie mit Fakes.
 */
internal class DataExportRunner(
    private val resolverFactory: (Path?) -> NamedConnectionResolver =
        { NamedConnectionResolver(configPathFromCli = it) },
    private val urlParser: (String) -> ConnectionConfig = ConnectionUrlParser::parse,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool = HikariConnectionPoolFactory::create,
    private val readerLookup: (DatabaseDialect) -> DataReader = DataReaderRegistry::dataReader,
    private val listerLookup: (DatabaseDialect) -> TableLister = DataReaderRegistry::tableLister,
    private val writerFactoryBuilder: ((ValueSerializer.Warning) -> Unit) -> DataChunkWriterFactory =
        { sink -> DefaultDataChunkWriterFactory(warningSink = sink) },
    private val exportExecutor: ExportExecutor = defaultExportExecutor,
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    /**
     * Führt den Export aus und gibt den CLI-Exit-Code zurück.
     *
     * Exit-Code-Mapping (Plan §6.10):
     * - 0 Erfolg
     * - 2 CLI-Validierungsfehler (bad encoding, bad identifier, bad delimiter,
     *     Output-Konflikt, leere Tabellen-Liste)
     * - 4 Connection- oder Tabellen-Lister-Fehler
     * - 5 Export-Fehler während Streaming
     * - 7 Konfigurations-/URL-Parser-/Registry-Fehler
     */
    fun execute(request: DataExportRequest): Int {
        // ─── 1. Source auflösen → vollständige Connection-URL ───
        val resolver = resolverFactory(request.cliConfigPath)
        val resolvedUrl = try {
            resolver.resolve(request.source)
        } catch (e: ConfigResolveException) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 2. URL → ConnectionConfig ──────────────────────────
        val connectionConfig = try {
            urlParser(resolvedUrl)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 3. Encoding parsen ─────────────────────────────────
        val charset = try {
            Charset.forName(request.encoding)
        } catch (e: Exception) {
            stderr("Error: Unknown encoding '${request.encoding}': ${e.message}")
            return 2
        }

        // ─── 4. Pool öffnen ─────────────────────────────────────
        val pool: ConnectionPool = try {
            poolFactory(connectionConfig)
        } catch (e: Throwable) {
            stderr("Error: Failed to connect to database: ${e.message}")
            return 4
        }

        return try {
            executeWithPool(request, connectionConfig, charset, pool)
        } finally {
            try { pool.close() } catch (_: Throwable) {}
        }
    }

    private fun executeWithPool(
        request: DataExportRequest,
        connectionConfig: ConnectionConfig,
        charset: Charset,
        pool: ConnectionPool,
    ): Int {
        // ─── 5. Reader + Lister aus Registry ───────────────────
        val reader = try {
            readerLookup(connectionConfig.dialect)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }
        val tableLister = try {
            listerLookup(connectionConfig.dialect)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 6. Tabellen ermitteln (--tables oder Auto-Discovery) ───
        val explicitTables = request.tables?.takeIf { it.isNotEmpty() }
        if (explicitTables != null) {
            val invalid = DataExportHelpers.firstInvalidTableIdentifier(explicitTables)
            if (invalid != null) {
                stderr(
                    "Error: --tables value '$invalid' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
        }
        val effectiveTables = explicitTables ?: try {
            tableLister.listTables(pool)
        } catch (e: Throwable) {
            stderr("Error: Failed to list tables: ${e.message}")
            return 4
        }
        if (effectiveTables.isEmpty()) {
            stderr("Error: No tables to export.")
            return 2
        }

        // ─── 7. ExportOutput auflösen ─────────────────────────
        val exportOutput = try {
            ExportOutput.resolve(
                outputPath = request.output,
                splitFiles = request.splitFiles,
                tableCount = effectiveTables.size,
            )
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 2
        }

        // ─── 8. ExportOptions aus den CLI-Flags bauen ─────────
        val delimiterChar = DataExportHelpers.parseCsvDelimiter(request.csvDelimiter)
            ?: run {
                stderr("Error: --csv-delimiter must be a single character, got '${request.csvDelimiter}'")
                return 2
            }
        val exportOptions = ExportOptions(
            encoding = charset,
            csvHeader = !request.csvNoHeader,
            csvDelimiter = delimiterChar,
            csvBom = request.csvBom,
            csvNullString = request.nullString,
        )

        val warnings = mutableListOf<ValueSerializer.Warning>()
        val factory = writerFactoryBuilder { warnings += it }
        val effectiveFilter = DataExportHelpers.resolveFilter(request.filter)

        // ─── 9. Streaming ─────────────────────────────────────
        val result: ExportResult = try {
            exportExecutor.execute(
                pool = pool,
                reader = reader,
                lister = tableLister,
                factory = factory,
                tables = effectiveTables,
                output = exportOutput,
                format = DataExportFormat.fromCli(request.format),
                options = exportOptions,
                config = PipelineConfig(chunkSize = request.chunkSize),
                filter = effectiveFilter,
            )
        } catch (e: Throwable) {
            stderr("Error: Export failed: ${e.message}")
            return 5
        }

        // ─── 10. Pro-Tabelle-Fehler → Exit 5 ──────────────────
        val failed = result.tables.firstOrNull { it.error != null }
        if (failed != null) {
            stderr("Error: Failed to export table '${failed.table}': ${failed.error}")
            return 5
        }

        // ─── 11. Warnings auf stderr (unterdrückt mit --quiet) ──
        if (!request.quiet) {
            for (warning in warnings) {
                stderr("  ⚠ ${warning.code} ${warning.table}.${warning.column} (${warning.javaClass}): ${warning.message}")
            }
        }

        // ─── 12. ProgressSummary (unterdrückt mit --quiet/--no-progress) ──
        val suppressProgress = request.quiet || request.noProgress
        if (!suppressProgress) {
            stderr(DataExportHelpers.formatProgressSummary(result))
        }

        return 0
    }
}
