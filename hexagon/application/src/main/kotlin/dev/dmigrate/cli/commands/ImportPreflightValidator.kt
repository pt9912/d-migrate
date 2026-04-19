package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.OnError
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.PipelineConfig
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.Locale

/**
 * Preflight checks and option building for `d-migrate data import`.
 * Extracted from [DataImportRunner] to keep the runner focused on orchestration.
 */
internal class ImportPreflightValidator(
    private val writerLookup: (DatabaseDialect) -> DataWriter,
    private val schemaTargetValidator: (schema: SchemaDefinition, table: String, targetColumns: List<TargetColumn>) -> Unit,
    private val stderr: (String) -> Unit,
) {

    /** Verify the writer for the target dialect exists. Returns `Unit` on success, `null` on failure (exit 7). */
    fun resolveWriter(connectionConfig: ConnectionConfig): Unit? {
        return try {
            writerLookup(connectionConfig.dialect)
            Unit
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            null
        }
    }

    /** Build ImportOptions, FormatReadOptions, PipelineConfig, and onTableOpened callback. */
    fun buildImportOptions(
        request: DataImportRequest,
        charset: Charset?,
        preparedImport: SchemaPreflightResult,
    ): ImportPreparedOptions {
        val triggerMode = TriggerMode.valueOf(request.triggerMode.uppercase())
        val onError = OnError.valueOf(request.onError.uppercase())
        val onConflict = if (request.onConflict != null) {
            OnConflict.valueOf(request.onConflict.uppercase())
        } else {
            OnConflict.ABORT
        }

        val formatReadOptions = FormatReadOptions(
            encoding = charset,
            csvNoHeader = request.csvNoHeader,
            csvNullString = request.csvNullString,
        )
        val importOptions = ImportOptions(
            triggerMode = triggerMode,
            reseedSequences = request.reseedSequences,
            disableFkChecks = request.disableFkChecks,
            truncate = request.truncate,
            onConflict = onConflict,
            onError = onError,
        )
        val pipelineConfig = PipelineConfig(chunkSize = request.chunkSize)
        val onTableOpened: (String, List<TargetColumn>) -> Unit =
            preparedImport.schema?.let { schema ->
                { table, targetColumns ->
                    schemaTargetValidator(schema, table, targetColumns)
                }
            } ?: { _, _ -> }

        return ImportPreparedOptions(importOptions, formatReadOptions, pipelineConfig, onTableOpened)
    }

    /** Directory scan, effective tables, fingerprint. */
    fun resolveInputContext(
        request: DataImportRequest,
        connectionConfig: ConnectionConfig,
        resolvedUrl: String,
        format: DataExportFormat,
        preparedImport: SchemaPreflightResult,
    ): InputContextResult {
        // Directory imports are scanned here before streaming -- the scan
        // yields a stable `table -> inputFile` mapping that flows into both
        // the fingerprint and the manifest.
        val directoryScan: List<DirectoryImportScanner.ScannedTable>? =
            when (val input = preparedImport.input) {
                is ImportInput.Directory -> try {
                    DirectoryImportScanner.scan(
                        directory = input.path,
                        format = format,
                        tableFilter = input.tableFilter,
                        tableOrder = input.tableOrder,
                    )
                } catch (e: IllegalArgumentException) {
                    stderr("Error: ${e.message}")
                    return InputContextResult.Exit(2)
                }
                else -> null
            }
        val effectiveTables: List<String> = when (val input = preparedImport.input) {
            is ImportInput.Stdin -> listOf(input.table)
            is ImportInput.SingleFile -> listOf(input.table)
            is ImportInput.Directory -> directoryScan!!.map { it.table }
        }
        val inputFilesByTable: Map<String, String> = directoryScan
            ?.associate { it.table to it.fileName }
            ?: emptyMap()
        val inputTopology: String = when (preparedImport.input) {
            is ImportInput.Stdin -> "stdin"
            is ImportInput.SingleFile -> "single-file"
            is ImportInput.Directory -> "directory"
        }
        val inputPath: String = when (val input = preparedImport.input) {
            is ImportInput.Stdin -> "<stdin>"
            is ImportInput.SingleFile -> input.path.toAbsolutePath().normalize().toString()
            is ImportInput.Directory -> input.path.toAbsolutePath().normalize().toString()
        }
        val fingerprint = ImportOptionsFingerprint.compute(
            ImportOptionsFingerprint.Input(
                format = request.format
                    ?: format.name.lowercase(Locale.US),
                encoding = request.encoding,
                csvNoHeader = request.csvNoHeader,
                csvNullString = request.csvNullString,
                onError = request.onError,
                onConflict = request.onConflict ?: "abort",
                triggerMode = request.triggerMode,
                truncate = request.truncate,
                disableFkChecks = request.disableFkChecks,
                reseedSequences = request.reseedSequences,
                chunkSize = request.chunkSize,
                tables = effectiveTables,
                inputTopology = inputTopology,
                inputPath = inputPath,
                targetDialect = connectionConfig.dialect.name,
                targetUrl = resolvedUrl,
                inputFilesByTable = inputFilesByTable,
            )
        )
        return InputContextResult.Ok(InputContext(effectiveTables, inputFilesByTable, fingerprint))
    }
}
