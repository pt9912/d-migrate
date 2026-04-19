package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale

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
    /** Explicit resume entry point for `data import`. Only valid for file- or
     *  directory-based sources (`source != "-"`). Stdin import cannot be resumed (exit 2). */
    val resume: String? = null,
    /** Optional checkpoint directory. Overrides `pipeline.checkpoint.directory` from config. */
    val checkpointDir: Path? = null,
)

/**
 * Core logic for `d-migrate data import`. All external collaborators are
 * constructor-injected so every branch (including error paths and exit
 * codes) is unit-testable without a real database or CLI framework.
 *
 * Exit codes:
 * - 0 success
 * - 1 unexpected internal error
 * - 2 CLI validation error (incl. `--resume` on stdin import)
 * - 3 pre-flight failure (header/schema mismatch, strict trigger,
 *   semantically incompatible resume reference)
 * - 4 connection error
 * - 5 import streaming error (with --on-error abort) or post-chunk finalization
 * - 7 config / URL / registry error (incl. unreadable checkpoint file or
 *   unparseable manifest)
 */
class DataImportRunner(
    private val targetResolver: (target: String?, configPath: Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val writerLookup: (DatabaseDialect) -> DataWriter,
    private val schemaPreflight: (schemaPath: Path, input: ImportInput, format: DataExportFormat) -> SchemaPreflightResult =
        { _, input, _ -> SchemaPreflightResult(input) },
    private val schemaTargetValidator: (schema: SchemaDefinition, table: String, targetColumns: List<TargetColumn>) -> Unit =
        { _, _, _ -> },
    private val importExecutor: ImportExecutor,
    private val progressReporter: ProgressReporter = NoOpProgressReporter,
    private val stdinProvider: () -> InputStream = { System.`in` },
    private val stderr: (String) -> Unit = { System.err.println(it) },
    /** Factory for the checkpoint store. Receives the effective checkpoint directory.
     *  CLI wires the file-based adapter; tests inject an in-memory store.
     *  `null` disables resume support (for legacy tests that need no manifest interaction). */
    private val checkpointStoreFactory: ((Path) -> CheckpointStore)? = null,
    /** Reads the `pipeline.checkpoint.*` block from the effective `.d-migrate.yaml`.
     *  The Runner merges CLI override (`--checkpoint-dir`) and config default via
     *  [CheckpointConfig.merge] — symmetric to the export path. */
    private val checkpointConfigResolver: (Path?) -> CheckpointConfig? = { null },
    /** Clock for manifest `createdAt`/`updatedAt`. Separately injectable for deterministic tests. */
    private val clock: () -> Instant = Instant::now,
) {

    private val preflightValidator = ImportPreflightValidator(
        writerLookup = writerLookup,
        schemaTargetValidator = schemaTargetValidator,
        stderr = stderr,
    )

    private val checkpointManager = ImportCheckpointManager(
        checkpointStoreFactory = checkpointStoreFactory,
        checkpointConfigResolver = checkpointConfigResolver,
        clock = clock,
        progressReporter = progressReporter,
        stderr = stderr,
    )

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

        // Resume CLI preflight: stdin import is inherently non-resumable —
        // the calling process cannot re-provide the stream. Semantic
        // preflight against the manifest happens later in `executeWithPool`.
        if (!request.resume.isNullOrBlank() && request.source == "-") {
            stderr(
                "Error: --resume is not supported for stdin import; " +
                    "provide a file or directory source or drop --resume."
            )
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
            executeWithPool(request, connectionConfig, resolvedUrl, charset, format, preparedImport, pool)
        } finally {
            try { pool.close() } catch (_: Throwable) {}
        }
    }

    private fun executeWithPool(
        request: DataImportRequest,
        connectionConfig: ConnectionConfig,
        resolvedUrl: String,
        charset: Charset?,
        format: DataExportFormat,
        preparedImport: SchemaPreflightResult,
        pool: ConnectionPool,
    ): Int {
        preflightValidator.resolveWriter(connectionConfig) ?: return 7
        val opts = preflightValidator.buildImportOptions(request, charset, preparedImport)
        val inputCtx = when (val r = preflightValidator.resolveInputContext(request, connectionConfig, resolvedUrl, format, preparedImport)) {
            is InputContextResult.Ok -> r.value
            is InputContextResult.Exit -> return r.code
        }
        val checkpoint = checkpointManager.resolveCheckpointContext(request) ?: return 7
        val resumeCtx = when (val r = checkpointManager.resolveResumeContext(request, checkpoint, inputCtx)) {
            is ImportResumeResult.Ok -> r.value
            is ImportResumeResult.Exit -> return r.code
        }
        val initExit = checkpointManager.writeInitialManifest(request, format, resumeCtx, checkpoint.store, inputCtx)
        if (initExit != null) return initExit
        val callbacks = checkpointManager.buildCallbacks(request, format, resumeCtx, checkpoint.store, inputCtx)
        val result = when (val r = executeStreaming(request, format, pool, preparedImport, opts, resumeCtx, callbacks)) {
            is StreamingResult.Ok -> r.value
            is StreamingResult.Exit -> return r.code
        }
        return finalizeAndReport(request, result, checkpoint.store, resumeCtx.operationId)
    }

    // ─── Step functions ──────────────────────────────────────────

    /** Step 8: Execute the streaming import pipeline. */
    private fun executeStreaming(
        request: DataImportRequest,
        format: DataExportFormat,
        pool: ConnectionPool,
        preparedImport: SchemaPreflightResult,
        opts: ImportPreparedOptions,
        resumeCtx: ImportResumeContext,
        callbacks: ImportCallbacks,
    ): StreamingResult {
        val operationId = resumeCtx.operationId
        return try {
            val rawResult = importExecutor.execute(
                context = ImportExecutionContext(
                    pool = pool,
                    input = preparedImport.input,
                ),
                options = ImportExecutionOptions(
                    format = format,
                    options = opts.importOptions,
                    readOptions = opts.formatReadOptions,
                    config = opts.pipelineConfig,
                ),
                resume = ImportResumeState(
                    operationId = operationId,
                    resuming = resumeCtx.resuming,
                    skippedTables = resumeCtx.skippedTables,
                    resumeStateByTable = resumeCtx.resumeStateByTable,
                ),
                callbacks = ImportCallbacks(
                    progressReporter = callbacks.progressReporter,
                    onTableOpened = opts.onTableOpened,
                    onChunkCommitted = callbacks.onChunkCommitted,
                    onTableCompleted = callbacks.onTableCompleted,
                ),
            )
            StreamingResult.Ok(rawResult.copy(operationId = operationId))
        } catch (e: UnsupportedTriggerModeException) {
            stderr("Error: ${e.message}")
            StreamingResult.Exit(2)
        } catch (e: ImportSchemaMismatchException) {
            stderr("Error: ${e.message}")
            StreamingResult.Exit(3)
        } catch (e: Throwable) {
            stderr("Error: Import failed: ${e.message}")
            StreamingResult.Exit(5)
        }
    }

    /** Step 9: Evaluate the result, clean up the manifest, and print the summary. */
    private fun finalizeAndReport(
        request: DataImportRequest,
        result: ImportResult,
        store: CheckpointStore?,
        operationId: String,
    ): Int {
        // Per-Tabelle-Fehler mit error -> Exit 5
        val failedTable = result.tables.firstOrNull { it.error != null }
        if (failedTable != null) {
            stderr("Error: Failed to import table '${failedTable.table}': ${failedTable.error}")
            return 5
        }

        // failedFinish -> Exit 5
        val failedFinish = result.tables.firstOrNull { it.failedFinish != null }
        if (failedFinish != null) {
            stderr(
                "Error: Post-import finalization failed for table '${failedFinish.table}': " +
                    "${failedFinish.failedFinish!!.causeMessage}. " +
                    "Data was committed — manual post-import fix may be needed."
            )
            return 5
        }

        // Successful run -> remove manifest. Errors during complete() are
        // reported as warnings, not as exit -- the import was logically successful.
        if (store != null) {
            try {
                store.complete(operationId)
            } catch (e: CheckpointStoreException) {
                stderr("Warning: Failed to remove completed checkpoint: ${e.message}")
            }
        }

        // ProgressSummary
        val suppressProgress = request.quiet || request.noProgress
        if (!suppressProgress) {
            stderr(formatProgressSummary(result))
            // operationId is printed in the stderr summary so that
            // operators, logs, and a later resume manifest all reference
            // the same run.
            result.operationId?.let { stderr("Run operation id: $it") }
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
            return "Imported $tableCount table(s) ($rowsSummary) in ${"%.1f".format(Locale.US, durationSec)} s$seqInfo"
        }
    }
}
