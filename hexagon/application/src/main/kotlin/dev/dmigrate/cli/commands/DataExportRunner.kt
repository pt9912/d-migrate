package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.streaming.ExportOutput
import dev.dmigrate.streaming.ExportResult
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * Thin seam over the streaming export, allowing the Runner to be tested
 * without a real [StreamingExporter][dev.dmigrate.streaming.StreamingExporter].
 * The production implementation is wired in the CLI module.
 */
fun interface ExportExecutor {
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
        progressReporter: ProgressReporter,
    ): ExportResult
}

/**
 * Immutable DTO with all CLI inputs for `d-migrate data export`.
 */
data class DataExportRequest(
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
    val cliConfigPath: Path?,
    val quiet: Boolean,
    val noProgress: Boolean,
)

/**
 * Core logic for `d-migrate data export`. All external collaborators are
 * constructor-injected so every branch (including error paths and exit
 * codes) is unit-testable without a real database or CLI framework.
 *
 * Exit codes (Plan §6.10):
 * - 0 success
 * - 2 CLI validation error
 * - 4 connection / table-lister error
 * - 5 export streaming error
 * - 7 config / URL / registry error
 */
class DataExportRunner(
    private val sourceResolver: (source: String, configPath: Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val readerLookup: (DatabaseDialect) -> DataReader,
    private val listerLookup: (DatabaseDialect) -> TableLister,
    private val writerFactoryBuilder: () -> DataChunkWriterFactory,
    private val collectWarnings: () -> List<String>,
    private val exportExecutor: ExportExecutor,
    private val progressReporter: ProgressReporter = NoOpProgressReporter,
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    fun execute(request: DataExportRequest): Int {
        // ─── 1. Source auflösen → vollständige Connection-URL ───
        val resolvedUrl = try {
            sourceResolver(request.source, request.cliConfigPath)
        } catch (e: Exception) {
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

        // ─── 2b. Incremental-export CLI-Preflight (LF-013 / M-R5) ──
        val hasSinceColumn = !request.sinceColumn.isNullOrBlank()
        val hasSinceValue = !request.since.isNullOrBlank()
        if (hasSinceColumn != hasSinceValue) {
            stderr("Error: --since-column and --since must be used together.")
            return 2
        }
        if (hasSinceColumn) {
            val invalidSinceColumn = DataExportHelpers.firstInvalidQualifiedIdentifier(request.sinceColumn!!)
            if (invalidSinceColumn != null) {
                stderr(
                    "Error: --since-column value '$invalidSinceColumn' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
            if (DataExportHelpers.containsLiteralQuestionMark(request.filter)) {
                stderr(
                    "Error: --filter must not contain literal '?' when combined with --since " +
                        "(parameterized query); use a rewritten predicate or escape the literal differently"
                )
                return 2
            }
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

        val factory = writerFactoryBuilder()
        val effectiveFilter = DataExportHelpers.resolveFilter(
            rawFilter = request.filter,
            dialect = connectionConfig.dialect,
            sinceColumn = request.sinceColumn,
            since = request.since,
        )

        // ─── 9. Streaming ─────────────────────────────────────
        val result: ExportResult = try {
            val effectiveReporter = if (request.quiet || request.noProgress)
                NoOpProgressReporter else progressReporter
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
                progressReporter = effectiveReporter,
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
            for (line in collectWarnings()) {
                stderr(line)
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
