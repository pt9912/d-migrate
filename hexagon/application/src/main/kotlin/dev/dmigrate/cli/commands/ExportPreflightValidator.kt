package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.streaming.ExportOutput
import java.nio.charset.Charset

/**
 * Preflight validation and context building for `d-migrate data export`.
 * Extracted from [DataExportRunner] to keep the runner focused on orchestration.
 *
 * Covers: infrastructure lookup, table resolution, output resolution, and
 * construction of the [ExportPreparedContext].
 */
internal class ExportPreflightValidator(
    private val readerLookup: (DatabaseDialect) -> DataReader,
    private val listerLookup: (DatabaseDialect) -> TableLister,
    private val writerFactoryBuilder: () -> DataChunkWriterFactory,
    private val stderr: (String) -> Unit,
) {

    fun resolveInfrastructure(config: ConnectionConfig): ExportInfra? {
        val reader = try { readerLookup(config.dialect) } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}"); return null
        }
        val lister = try { listerLookup(config.dialect) } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}"); return null
        }
        return ExportInfra(reader, lister)
    }

    fun resolveTables(request: DataExportRequest, lister: TableLister, pool: ConnectionPool): TablesResult {
        val explicit = request.tables?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            val invalid = DataExportHelpers.firstInvalidTableIdentifier(explicit)
            if (invalid != null) {
                stderr("Error: --tables value '$invalid' is not a valid identifier. " +
                    "Expected '<name>' or '<schema>.<name>' matching " +
                    DataExportHelpers.TABLE_IDENTIFIER_PATTERN + ".")
                return TablesResult.Exit(2)
            }
        }
        val tables = explicit ?: try {
            lister.listTables(pool)
        } catch (e: Throwable) {
            stderr("Error: Failed to list tables: ${e.message}")
            return TablesResult.Exit(4)
        }
        if (tables.isEmpty()) {
            stderr("Error: No tables to export.")
            return TablesResult.Exit(2)
        }
        return TablesResult.Ok(tables)
    }

    fun resolveOutput(request: DataExportRequest, tables: List<String>): ExportOutput? {
        return try {
            ExportOutput.resolve(outputPath = request.output, splitFiles = request.splitFiles, tableCount = tables.size)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}"); null
        }
    }

    data class ExportContextInput(
        val request: DataExportRequest, val config: ConnectionConfig, val charset: Charset,
        val pool: ConnectionPool, val tables: List<String>, val output: ExportOutput, val infra: ExportInfra,
        val resolvePrimaryKeys: (ConnectionPool, DatabaseDialect, List<String>) -> Map<String, List<String>>,
    )

    fun buildExportContext(input: ExportContextInput): PreparedResult {
        val request = input.request; val config = input.config; val charset = input.charset
        val pool = input.pool; val tables = input.tables; val output = input.output; val infra = input.infra
        val resolvePrimaryKeys = input.resolvePrimaryKeys
        val delimiterChar = DataExportHelpers.parseCsvDelimiter(request.csvDelimiter)
            ?: run {
                stderr("Error: --csv-delimiter must be a single character, got '${request.csvDelimiter}'")
                return PreparedResult.Exit(2)
            }
        val options = ExportOptions(
            encoding = charset, csvHeader = !request.csvNoHeader,
            csvDelimiter = delimiterChar, csvBom = request.csvBom, csvNullString = request.nullString,
        )
        val filter = DataExportHelpers.resolveFilter(
            parsedFilter = request.filter, dialect = config.dialect,
            sinceColumn = request.sinceColumn, since = request.since,
        )
        val pks: Map<String, List<String>> = if (!request.sinceColumn.isNullOrBlank()) {
            resolvePrimaryKeys(pool, config.dialect, tables)
        } else emptyMap()
        val fingerprint = ExportOptionsFingerprint.compute(ExportOptionsFingerprint.Input(
            format = request.format, encoding = request.encoding, csvDelimiter = request.csvDelimiter,
            csvBom = request.csvBom, csvNoHeader = request.csvNoHeader, csvNullString = request.nullString,
            filter = request.filter?.canonical, sinceColumn = request.sinceColumn, since = request.since,
            tables = tables, outputMode = canonicalOutputMode(output), outputPath = canonicalOutputPath(output),
            primaryKeysByTable = pks,
        ))
        return PreparedResult.Ok(ExportPreparedContext(
            reader = infra.reader, lister = infra.lister,
            tables = tables, output = output, options = options, filter = filter,
            factory = writerFactoryBuilder(), fingerprint = fingerprint, primaryKeysByTable = pks,
        ))
    }

    private fun canonicalOutputMode(output: ExportOutput): String = when (output) {
        is ExportOutput.Stdout -> "stdout"
        is ExportOutput.SingleFile -> "single-file"
        is ExportOutput.FilePerTable -> "file-per-table"
    }

    private fun canonicalOutputPath(output: ExportOutput): String = when (output) {
        is ExportOutput.Stdout -> "<stdout>"
        is ExportOutput.SingleFile -> output.path.toAbsolutePath().normalize().toString()
        is ExportOutput.FilePerTable -> output.directory.toAbsolutePath().normalize().toString()
    }
}
