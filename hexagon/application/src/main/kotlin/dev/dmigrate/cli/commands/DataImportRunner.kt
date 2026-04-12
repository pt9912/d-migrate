package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.OnError
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.PipelineConfig
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

class CliUsageException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class ImportPreflightException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

data class SchemaPreflightResult(
    val input: ImportInput,
    val schema: SchemaDefinition? = null,
)

/**
 * Thin seam over the streaming import, allowing the Runner to be tested
 * without a real [StreamingImporter][dev.dmigrate.streaming.StreamingImporter].
 * The production implementation is wired in the CLI module.
 */
fun interface ImportExecutor {
    fun execute(
        pool: ConnectionPool,
        input: ImportInput,
        format: DataExportFormat,
        options: ImportOptions,
        config: PipelineConfig,
        onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit,
    ): ImportResult
}

/**
 * Immutable DTO with all CLI inputs for `d-migrate data import`.
 */
data class DataImportRequest(
    val target: String?,
    val source: String,
    val format: String?,
    val schema: Path?,
    val table: String?,
    val tables: List<String>?,
    val onError: String,
    val onConflict: String?,
    val triggerMode: String,
    val truncate: Boolean,
    val disableFkChecks: Boolean,
    val reseedSequences: Boolean,
    val encoding: String?,
    val csvNoHeader: Boolean,
    val csvNullString: String,
    val chunkSize: Int,
    val cliConfigPath: Path?,
    val quiet: Boolean,
    val noProgress: Boolean,
)

/**
 * Core logic for `d-migrate data import`. All external collaborators are
 * constructor-injected so every branch (including error paths and exit
 * codes) is unit-testable without a real database or CLI framework.
 *
 * Exit codes (Plan §6.11):
 * - 0 success
 * - 1 unexpected internal error
 * - 2 CLI validation error
 * - 3 pre-flight failure (header/schema mismatch, strict trigger)
 * - 4 connection error
 * - 5 import streaming error (with --on-error abort) or post-chunk finalization
 * - 7 config / URL / registry error
 */
class DataImportRunner(
    private val targetResolver: (target: String?, configPath: Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val writerLookup: (DatabaseDialect) -> DataWriter,
    private val schemaPreflight: (schemaPath: Path, input: ImportInput, format: DataExportFormat) -> SchemaPreflightResult =
        { _, input, _ -> SchemaPreflightResult(input) },
    private val importExecutor: ImportExecutor,
    private val stdinProvider: () -> InputStream = { System.`in` },
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    fun execute(request: DataImportRequest): Int {
        // ─── 1. CLI-Validierungen ───────────────────────────────

        // --table und --tables sind gegenseitig exklusiv
        if (request.table != null && !request.tables.isNullOrEmpty()) {
            stderr("Error: --table and --tables are mutually exclusive.")
            return 2
        }

        // Identifier-Validierung für --table
        if (request.table != null) {
            val invalid = DataExportHelpers.firstInvalidQualifiedIdentifier(request.table)
            if (invalid != null) {
                stderr(
                    "Error: --table value '$invalid' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
        }

        // Identifier-Validierung für --tables
        if (!request.tables.isNullOrEmpty()) {
            val invalid = DataExportHelpers.firstInvalidTableIdentifier(request.tables)
            if (invalid != null) {
                stderr(
                    "Error: --tables value '$invalid' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
        }

        // --truncate + explicit --on-conflict abort → Exit 2
        if (request.truncate && request.onConflict == "abort") {
            stderr("Error: --truncate with explicit --on-conflict abort is contradictory.")
            return 2
        }

        // ─── 2. Source-Pfad + Format auflösen ───────────────────
        val isStdin = request.source == "-"
        val sourcePath = if (!isStdin) Path.of(request.source) else null

        // Format bestimmen
        val formatName = request.format
            ?: sourcePath?.let { inferFormatFromExtension(it) }

        if (formatName == null) {
            if (isStdin) {
                stderr("Error: --format is required when reading from stdin (--source -).")
            } else {
                stderr("Error: Cannot detect format from '${request.source}'. Use --format to specify json, yaml, or csv.")
            }
            return 2
        }

        val format = try {
            DataExportFormat.fromCli(formatName)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 2
        }

        // Source-Pfad-Validierung (nicht-stdin)
        if (sourcePath != null && !Files.exists(sourcePath)) {
            stderr("Error: Source path does not exist: $sourcePath")
            return 2
        }

        // ─── 3. ImportInput ableiten ────────────────────────────
        val importInput = try {
            resolveImportInput(request, isStdin, sourcePath)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 2
        }

        // ─── 4. Optionales --schema-Preflight ───────────────────
        val preparedImport = if (request.schema != null) {
            try {
                schemaPreflight(request.schema, importInput, format)
            } catch (e: ImportPreflightException) {
                stderr("Error: ${e.message}")
                return 3
            }
        } else {
            SchemaPreflightResult(importInput)
        }

        // ─── 5. Encoding parsen ─────────────────────────────────
        val charset: Charset? = if (request.encoding != null) {
            try {
                Charset.forName(request.encoding)
            } catch (e: Exception) {
                stderr("Error: Unknown encoding '${request.encoding}': ${e.message}")
                return 2
            }
        } else {
            null // auto-detect
        }

        // ─── 6. Target auflösen → vollständige Connection-URL ───
        val resolvedUrl = try {
            targetResolver(request.target, request.cliConfigPath)
        } catch (e: CliUsageException) {
            stderr("Error: ${e.message}")
            return 2
        } catch (e: Exception) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 7. URL → ConnectionConfig ──────────────────────────
        val connectionConfig = try {
            urlParser(resolvedUrl)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }

        // --disable-fk-checks auf PG → Exit 2
        if (request.disableFkChecks && connectionConfig.dialect == DatabaseDialect.POSTGRESQL) {
            stderr(
                "Error: --disable-fk-checks is not supported for PostgreSQL. " +
                    "Use DEFERRABLE constraints or --schema-based ordering instead."
            )
            return 2
        }

        // --trigger-mode disable auf MySQL/SQLite → Exit 2
        if (request.triggerMode == "disable" &&
            connectionConfig.dialect in listOf(DatabaseDialect.MYSQL, DatabaseDialect.SQLITE)
        ) {
            stderr(
                "Error: --trigger-mode disable is not supported for dialect ${connectionConfig.dialect}."
            )
            return 2
        }

        // ─── 8. Pool öffnen ────────────────────────────────────
        val pool: ConnectionPool = try {
            poolFactory(connectionConfig)
        } catch (e: Throwable) {
            stderr("Error: Failed to connect to database: ${e.message}")
            return 4
        }

        return try {
            executeWithPool(request, connectionConfig, charset, format, preparedImport, pool)
        } finally {
            try { pool.close() } catch (_: Throwable) {}
        }
    }

    private fun executeWithPool(
        request: DataImportRequest,
        connectionConfig: ConnectionConfig,
        charset: Charset?,
        format: DataExportFormat,
        preparedImport: SchemaPreflightResult,
        pool: ConnectionPool,
    ): Int {
        // ─── 8. Writer-Lookup ──────────────────────────────────
        try {
            writerLookup(connectionConfig.dialect)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 9. ImportOptions bauen ────────────────────────────
        val triggerMode = TriggerMode.valueOf(request.triggerMode.uppercase())
        val onError = OnError.valueOf(request.onError.uppercase())
        val onConflict = if (request.onConflict != null) {
            OnConflict.valueOf(request.onConflict.uppercase())
        } else {
            OnConflict.ABORT
        }

        val importOptions = ImportOptions(
            triggerMode = triggerMode,
            csvNoHeader = request.csvNoHeader,
            csvNullString = request.csvNullString,
            encoding = charset,
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
                    DataImportSchemaPreflight.validateTargetTable(schema, table, targetColumns)
                }
            } ?: { _, _ -> }

        // ─── 10. Streaming ─────────────────────────────────────
        val result: ImportResult = try {
            importExecutor.execute(
                pool = pool,
                input = preparedImport.input,
                format = format,
                options = importOptions,
                config = pipelineConfig,
                onTableOpened = onTableOpened,
            )
        } catch (e: UnsupportedTriggerModeException) {
            stderr("Error: ${e.message}")
            return 2
        } catch (e: ImportSchemaMismatchException) {
            stderr("Error: ${e.message}")
            return 3
        } catch (e: Throwable) {
            stderr("Error: Import failed: ${e.message}")
            return 5
        }

        // ─── 11. Result auswerten ──────────────────────────────

        // Per-Tabelle-Fehler mit error → Exit 5
        val failedTable = result.tables.firstOrNull { it.error != null }
        if (failedTable != null) {
            stderr("Error: Failed to import table '${failedTable.table}': ${failedTable.error}")
            return 5
        }

        // failedFinish → Exit 5
        val failedFinish = result.tables.firstOrNull { it.failedFinish != null }
        if (failedFinish != null) {
            stderr(
                "Error: Post-import finalization failed for table '${failedFinish.table}': " +
                    "${failedFinish.failedFinish!!.causeMessage}. " +
                    "Data was committed — manual post-import fix may be needed."
            )
            return 5
        }

        // ─── 12. ProgressSummary ───────────────────────────────
        val suppressProgress = request.quiet || request.noProgress
        if (!suppressProgress) {
            stderr(formatProgressSummary(result))
        }

        return 0
    }

    private fun resolveImportInput(
        request: DataImportRequest,
        isStdin: Boolean,
        sourcePath: Path?,
    ): ImportInput {
        if (isStdin) {
            val table = request.table
                ?: throw IllegalArgumentException("--table is required when reading from stdin (--source -).")
            return ImportInput.Stdin(table, stdinProvider())
        }

        requireNotNull(sourcePath)

        if (Files.isDirectory(sourcePath)) {
            if (request.table != null) {
                throw IllegalArgumentException(
                    "--table is only supported for stdin or single-file imports. Use --tables for directory sources."
                )
            }
            return ImportInput.Directory(
                path = sourcePath,
                tableFilter = request.tables,
            )
        }

        // Einzelne Datei
        val table = request.table
            ?: throw IllegalArgumentException(
                "--table is required when importing from a single file."
            )
        return ImportInput.SingleFile(table, sourcePath)
    }

    companion object {
        private val EXTENSION_FORMAT_MAP = mapOf(
            "json" to "json",
            "yaml" to "yaml",
            "yml" to "yaml",
            "csv" to "csv",
        )

        fun inferFormatFromExtension(path: Path): String? {
            val fileName = path.fileName?.toString() ?: return null
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return EXTENSION_FORMAT_MAP[ext]
        }

        fun formatProgressSummary(result: ImportResult): String {
            val tableCount = result.tables.size
            val totalInserted = result.totalRowsInserted
            val totalUpdated = result.totalRowsUpdated
            val totalFailed = result.totalRowsFailed
            val seqCount = result.tables.flatMap { it.sequenceAdjustments }.size
            val durationSec = result.durationMs / 1000.0

            val parts = mutableListOf<String>()
            parts += "$totalInserted inserted"
            if (totalUpdated > 0) parts += "$totalUpdated updated"
            if (totalFailed > 0) parts += "$totalFailed failed"
            val rowsSummary = parts.joinToString(", ")

            val seqInfo = if (seqCount > 0) "; reseeded $seqCount sequence(s)" else ""
            return "Imported $tableCount table(s) ($rowsSummary) in ${"%.1f".format(durationSec)} s$seqInfo"
        }
    }
}
