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
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointOperationType
import dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import dev.dmigrate.streaming.checkpoint.UnsupportedCheckpointVersionException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale

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
/** Grouped infrastructure for import execution. */
data class ImportExecutionContext(
    val pool: ConnectionPool,
    val input: ImportInput,
)

/** Grouped import options and format configuration. */
data class ImportExecutionOptions(
    val format: DataExportFormat,
    val options: ImportOptions,
    val readOptions: FormatReadOptions,
    val config: PipelineConfig,
)

/** Grouped resume state for import. */
data class ImportResumeState(
    val operationId: String?,
    val resuming: Boolean,
    val skippedTables: Set<String>,
    val resumeStateByTable: Map<String, dev.dmigrate.streaming.ImportTableResumeState>,
)

/** Grouped callbacks for import progress and lifecycle. */
data class ImportCallbacks(
    val progressReporter: ProgressReporter,
    val onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit,
    val onChunkCommitted: (dev.dmigrate.streaming.ImportChunkCommit) -> Unit,
    val onTableCompleted: (dev.dmigrate.streaming.TableImportSummary) -> Unit,
)

/**
 * Thin seam over the streaming import, allowing the Runner to be tested
 * without a real [StreamingImporter][dev.dmigrate.streaming.StreamingImporter].
 * The production implementation is wired in the CLI module.
 */
fun interface ImportExecutor {
    fun execute(
        context: ImportExecutionContext,
        options: ImportExecutionOptions,
        resume: ImportResumeState,
        callbacks: ImportCallbacks,
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

    // ─── Internal DTOs for step results ─────────────────────────

    /** Prepared import options, format configuration, and schema callback. */
    private data class ImportPreparedOptions(
        val importOptions: ImportOptions,
        val formatReadOptions: FormatReadOptions,
        val pipelineConfig: PipelineConfig,
        val onTableOpened: (String, List<TargetColumn>) -> Unit,
    )

    /** Result of scanning the input directory/file and computing the fingerprint. */
    private data class InputContext(
        val effectiveTables: List<String>,
        val inputFilesByTable: Map<String, String>,
        val fingerprint: String,
    )

    private sealed class InputContextResult {
        data class Ok(val value: InputContext) : InputContextResult()
        data class Exit(val code: Int) : InputContextResult()
    }

    /** Checkpoint store and directory resolved from CLI + config. */
    private data class CheckpointContext(
        val store: CheckpointStore?,
        val dir: Path?,
    )

    private sealed class ResumeResult {
        data class Ok(val value: ResumeContext) : ResumeResult()
        data class Exit(val code: Int) : ResumeResult()
    }

    private sealed class StreamingResult {
        data class Ok(val value: ImportResult) : StreamingResult()
        data class Exit(val code: Int) : StreamingResult()
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
        resolveWriter(connectionConfig) ?: return 7
        val opts = buildImportOptions(request, charset, preparedImport)
        val inputCtxResult = resolveInputContext(request, connectionConfig, resolvedUrl, format, preparedImport)
        if (inputCtxResult is InputContextResult.Exit) return inputCtxResult.code
        val inputCtx = (inputCtxResult as InputContextResult.Ok).value
        val checkpoint = resolveCheckpointContext(request) ?: return 7
        val resumeResult = resolveResumeContext(request, checkpoint, inputCtx)
        if (resumeResult is ResumeResult.Exit) return resumeResult.code
        val resumeCtx = (resumeResult as ResumeResult.Ok).value
        val callbacks = buildCallbacks(request, format, resumeCtx, checkpoint.store, inputCtx)
            ?: return 7
        val streamingResult = executeStreaming(request, format, pool, preparedImport, opts, resumeCtx, callbacks)
        if (streamingResult is StreamingResult.Exit) return streamingResult.code
        val result = (streamingResult as StreamingResult.Ok).value
        return finalizeAndReport(request, result, checkpoint.store, resumeCtx.operationId)
    }

    // ─── Step functions ──────────────────────────────────────────

    /** Step 1: Verify the writer for the target dialect exists. */
    private fun resolveWriter(connectionConfig: ConnectionConfig): Unit? {
        return try {
            writerLookup(connectionConfig.dialect)
            Unit
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            null
        }
    }

    /** Step 2: Build ImportOptions, FormatReadOptions, PipelineConfig, and onTableOpened callback. */
    private fun buildImportOptions(
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

    /** Step 3: Directory scan, effective tables, fingerprint. */
    private fun resolveInputContext(
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

    /** Step 4: Merge CLI override and config default for checkpoint directory + store. */
    private fun resolveCheckpointContext(request: DataImportRequest): CheckpointContext? {
        val fromConfig: CheckpointConfig? = try {
            checkpointConfigResolver(request.cliConfigPath)
        } catch (e: Throwable) {
            stderr("Error: Failed to resolve pipeline.checkpoint config: ${e.message}")
            return null
        }
        val mergedCheckpointConfig = CheckpointConfig.merge(
            cliDirectory = request.checkpointDir,
            config = fromConfig,
        )
        val checkpointDir: Path? = mergedCheckpointConfig.directory
        val store: CheckpointStore? = checkpointStoreFactory?.let { factory ->
            checkpointDir?.let { factory(it) }
        }
        return CheckpointContext(store, checkpointDir)
    }

    /** Step 5: Load, validate, and build the resume context (or create a fresh one). */
    private fun resolveResumeContext(
        request: DataImportRequest,
        checkpoint: CheckpointContext,
        inputCtx: InputContext,
    ): ResumeResult {
        val effectiveTables = inputCtx.effectiveTables
        val inputFilesByTable = inputCtx.inputFilesByTable
        val fingerprint = inputCtx.fingerprint

        if (!request.resume.isNullOrBlank()) {
            val store = checkpoint.store ?: run {
                stderr(
                    "Error: --resume requires a checkpoint directory; set " +
                        "--checkpoint-dir or pipeline.checkpoint.directory."
                )
                return ResumeResult.Exit(7)
            }
            val resolvedOpId = resolveResumeReference(request.resume, checkpoint.dir!!)
                ?: run {
                    stderr(
                        "Error: --resume path must be inside the effective " +
                            "checkpoint directory '${checkpoint.dir}'."
                    )
                    return ResumeResult.Exit(7)
                }
            val manifest: CheckpointManifest? = try {
                store.load(resolvedOpId)
            } catch (e: UnsupportedCheckpointVersionException) {
                stderr("Error: ${e.message}")
                return ResumeResult.Exit(7)
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to load checkpoint: ${e.message}")
                return ResumeResult.Exit(7)
            }
            if (manifest == null) {
                stderr("Error: Checkpoint not found: '${request.resume}'")
                return ResumeResult.Exit(7)
            }
            if (manifest.operationType != CheckpointOperationType.IMPORT) {
                stderr(
                    "Error: Checkpoint type mismatch: expected IMPORT, got " +
                        "${manifest.operationType}."
                )
                return ResumeResult.Exit(3)
            }
            if (manifest.optionsFingerprint != fingerprint) {
                stderr(
                    "Error: Checkpoint options do not match the current request " +
                        "(fingerprint mismatch); refuse to resume."
                )
                return ResumeResult.Exit(3)
            }
            val manifestTables = manifest.tableSlices.map { it.table }
            if (manifestTables != effectiveTables) {
                stderr(
                    "Error: Checkpoint table list does not match the current " +
                        "request: manifest=$manifestTables, current=$effectiveTables."
                )
                return ResumeResult.Exit(3)
            }
            // Per-slice `table -> inputFile` binding must match the current
            // directory scan. The fingerprint usually catches this, but a
            // manifest slice without `inputFile` must not be silently
            // reinterpreted against a new scan.
            if (inputFilesByTable.isNotEmpty()) {
                val mismatch = manifest.tableSlices.firstOrNull { slice ->
                    val expected = inputFilesByTable[slice.table]
                    slice.inputFile != expected
                }
                if (mismatch != null) {
                    stderr(
                        "Error: Checkpoint input-file binding for table " +
                            "'${mismatch.table}' does not match the current directory " +
                            "scan (manifest=${mismatch.inputFile ?: "<none>"}, " +
                            "current=${inputFilesByTable[mismatch.table] ?: "<none>"})."
                    )
                    return ResumeResult.Exit(3)
                }
            }
            val skipped = manifest.tableSlices
                .filter { it.status == CheckpointSliceStatus.COMPLETED }
                .map { it.table }
                .toSet()
            // Derive per-table resume state. Slices that are neither
            // `COMPLETED` nor at 0 chunks get an entry -- the importer
            // then skips already-committed chunks and suppresses `truncate`.
            val resumeStates = manifest.tableSlices
                .filter { it.status != CheckpointSliceStatus.COMPLETED && it.chunksProcessed > 0L }
                .associate { slice ->
                    slice.table to dev.dmigrate.streaming.ImportTableResumeState(
                        committedChunks = slice.chunksProcessed,
                    )
                }
            return ResumeResult.Ok(ResumeContext(
                operationId = manifest.operationId,
                resuming = true,
                skippedTables = skipped,
                resumeStateByTable = resumeStates,
                initialSlices = manifest.tableSlices.associateBy { it.table },
            ))
        }
        return ResumeResult.Ok(ResumeContext(
            operationId = java.util.UUID.randomUUID().toString(),
            resuming = false,
            skippedTables = emptySet(),
            resumeStateByTable = emptyMap(),
            initialSlices = effectiveTables.associateWith { table ->
                CheckpointTableSlice(
                    table = table,
                    status = CheckpointSliceStatus.PENDING,
                    // Directory imports populate `inputFile`; stdin / single-file
                    // leaves the field `null`.
                    inputFile = inputFilesByTable[table],
                )
            },
        ))
    }

    /** Step 6+7: Write the initial manifest for fresh runs, then build chunk/table callbacks. */
    private fun buildCallbacks(
        request: DataImportRequest,
        format: DataExportFormat,
        resumeCtx: ResumeContext,
        store: CheckpointStore?,
        inputCtx: InputContext,
    ): ImportCallbacks? {
        val operationId = resumeCtx.operationId
        val effectiveTables = inputCtx.effectiveTables
        val inputFilesByTable = inputCtx.inputFilesByTable
        val fingerprint = inputCtx.fingerprint

        // Initial-Manifest-Schreibpfad: bei neuem Lauf anlegen, damit
        // ein spaeterer Abbruch einen gueltigen Resume-Anker hat.
        // `createdAt`/`updatedAt` werden beim Resume-Lauf vom bereits
        // geladenen Manifest uebernommen (`complete()` raeumt am
        // Lauf-Ende auf).
        if (store != null && !resumeCtx.resuming) {
            val created = clock()
            val initial = CheckpointManifest(
                operationId = operationId,
                operationType = CheckpointOperationType.IMPORT,
                createdAt = created,
                updatedAt = created,
                format = request.format ?: format.name.lowercase(Locale.US),
                chunkSize = request.chunkSize,
                // `initialSlices` already carries the `inputFile` binding from
                // the directory scan; the initial manifest must mirror it so a
                // later resume can validate against a fresh scan.
                tableSlices = effectiveTables.map { table ->
                    resumeCtx.initialSlices[table] ?: CheckpointTableSlice(
                        table = table,
                        status = CheckpointSliceStatus.PENDING,
                    )
                },
                optionsFingerprint = fingerprint,
            )
            try {
                store.save(initial)
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to initialize checkpoint: ${e.message}")
                return null
            }
        }

        // Manifest update per chunk-commit and per table-end.
        // `createdAt` is set now for fresh runs, taken from the loaded
        // manifest on resume.
        val tableStates = LinkedHashMap(resumeCtx.initialSlices)
        val createdAt: Instant = if (resumeCtx.resuming && store != null) {
            try {
                store.load(operationId)?.createdAt ?: clock()
            } catch (_: Throwable) { clock() }
        } else {
            clock()
        }

        fun saveManifest() {
            if (store == null) return
            try {
                store.save(
                    CheckpointManifest(
                        operationId = operationId,
                        operationType = CheckpointOperationType.IMPORT,
                        createdAt = createdAt,
                        updatedAt = clock(),
                        format = request.format ?: format.name.lowercase(Locale.US),
                        chunkSize = request.chunkSize,
                        tableSlices = effectiveTables.map {
                            tableStates[it] ?: CheckpointTableSlice(
                                table = it,
                                status = CheckpointSliceStatus.PENDING,
                            )
                        },
                        optionsFingerprint = fingerprint,
                    )
                )
            } catch (_: CheckpointStoreException) {
                // Wie im Export-Pfad: ein verlorener Zwischen-Save
                // darf den Lauf nicht abbrechen; der naechste
                // Chunk versucht erneut, und `complete()` meldet
                // am Lauf-Ende ggf. den Fehler.
            }
        }

        val onChunkCommitted: (dev.dmigrate.streaming.ImportChunkCommit) -> Unit = { commit ->
            // Update and persist manifest slice. `inputFile` stays stable
            // (from initial or loaded manifest); the scan is the source of truth.
            val slice = CheckpointTableSlice(
                table = commit.table,
                status = CheckpointSliceStatus.IN_PROGRESS,
                rowsProcessed = commit.rowsProcessedTotal,
                chunksProcessed = commit.chunksCommitted,
                inputFile = tableStates[commit.table]?.inputFile
                    ?: inputFilesByTable[commit.table],
            )
            tableStates[commit.table] = slice
            saveManifest()
        }
        val onTableCompleted: (dev.dmigrate.streaming.TableImportSummary) -> Unit = { summary ->
            // failedFinish or error must not mark the table as COMPLETED --
            // the slice stays FAILED so a resume does not silently skip it.
            val status = if (summary.error == null && summary.failedFinish == null) {
                CheckpointSliceStatus.COMPLETED
            } else {
                CheckpointSliceStatus.FAILED
            }
            val slice = CheckpointTableSlice(
                table = summary.table,
                status = status,
                // Bei COMPLETED: totalRows = inserted+updated+skipped+unknown+failed.
                // Der existierende Tabellen-Summary liefert die
                // Einzelzaehler; wir aggregieren fuer die Manifest-
                // Fortschreibung.
                rowsProcessed = summary.rowsInserted + summary.rowsUpdated +
                    summary.rowsSkipped + summary.rowsUnknown + summary.rowsFailed,
                chunksProcessed = tableStates[summary.table]?.chunksProcessed ?: 0L,
                // `inputFile` is retained in the slice even on COMPLETED / FAILED
                // so a later preflight can continue to validate the binding.
                inputFile = tableStates[summary.table]?.inputFile
                    ?: inputFilesByTable[summary.table],
            )
            tableStates[summary.table] = slice
            saveManifest()
        }

        val effectiveReporter = if (request.quiet || request.noProgress)
            NoOpProgressReporter else progressReporter
        return ImportCallbacks(effectiveReporter, { _, _ -> }, onChunkCommitted, onTableCompleted)
    }

    /** Step 8: Execute the streaming import pipeline. */
    private fun executeStreaming(
        request: DataImportRequest,
        format: DataExportFormat,
        pool: ConnectionPool,
        preparedImport: SchemaPreflightResult,
        opts: ImportPreparedOptions,
        resumeCtx: ResumeContext,
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

    /** Internal resume context of a run. Carries per-table resume states for skip-ahead
     *  and truncate-guard, as well as the initial slice state for manifest updates after each chunk. */
    private data class ResumeContext(
        val operationId: String,
        val resuming: Boolean,
        val skippedTables: Set<String>,
        val resumeStateByTable: Map<String, dev.dmigrate.streaming.ImportTableResumeState>,
        val initialSlices: Map<String, CheckpointTableSlice>,
    )

    private val resumeCoordinator = ImportResumeCoordinator()

    private fun resolveResumeReference(resumeValue: String, checkpointDir: Path): String? =
        resumeCoordinator.resolveResumeReference(resumeValue, checkpointDir)

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
