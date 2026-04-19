package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.streaming.ExportOutput
import dev.dmigrate.streaming.ExportResult
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointOperationType
import dev.dmigrate.streaming.checkpoint.CheckpointResumePosition
import dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import dev.dmigrate.streaming.checkpoint.UnsupportedCheckpointVersionException
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Instant

/**
 * Thin seam over the streaming export, allowing the Runner to be tested
 * without a real [StreamingExporter][dev.dmigrate.streaming.StreamingExporter].
 * The production implementation is wired in the CLI module.
 */
/** Grouped infrastructure for export execution. */
data class ExportExecutionContext(
    val pool: ConnectionPool,
    val reader: DataReader,
    val lister: TableLister,
    val factory: DataChunkWriterFactory,
)

/** Grouped export options and I/O configuration. */
data class ExportExecutionOptions(
    val tables: List<String>,
    val output: ExportOutput,
    val format: DataExportFormat,
    val options: ExportOptions,
    val config: PipelineConfig,
    val filter: DataFilter?,
)

/** Grouped resume state for export. */
data class ExportResumeState(
    val operationId: String?,
    val resuming: Boolean,
    val skippedTables: Set<String>,
    val resumeMarkers: Map<String, ResumeMarker>,
)

/** Grouped callbacks for export progress and lifecycle. */
data class ExportCallbacks(
    val progressReporter: ProgressReporter,
    val onTableCompleted: (dev.dmigrate.streaming.TableExportSummary) -> Unit,
    val onChunkProcessed: (dev.dmigrate.streaming.TableChunkProgress) -> Unit,
)

/**
 * Thin seam over the streaming export, allowing the Runner to be tested
 * without a real [StreamingExporter][dev.dmigrate.streaming.StreamingExporter].
 * The production implementation is wired in the CLI module.
 */
fun interface ExportExecutor {
    fun execute(
        context: ExportExecutionContext,
        options: ExportExecutionOptions,
        resume: ExportResumeState,
        callbacks: ExportCallbacks,
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
    /** Explicit resume entry point. Value is a checkpoint ID or path to a manifest. */
    val resume: String? = null,
    /** Optional checkpoint directory. Overrides `pipeline.checkpoint.directory` from config. */
    val checkpointDir: Path? = null,
)

/**
 * Core logic for `d-migrate data export`. All external collaborators are
 * constructor-injected so every branch (including error paths and exit
 * codes) is unit-testable without a real database or CLI framework.
 *
 * Exit codes:
 * - 0 success
 * - 2 CLI validation error (incl. `--resume` on stdout export)
 * - 3 resume preflight failure — semantically incompatible checkpoint
 *   (e.g. table list changed, schema divergent)
 * - 4 connection / table-lister error
 * - 5 export streaming error
 * - 7 config / URL / registry error (incl. unreadable checkpoint file or
 *   unparseable manifest)
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
    /** Factory for the checkpoint store. Receives the effective checkpoint directory.
     *  CLI wires the file-based adapter; tests inject an in-memory store.
     *  `null` disables resume support (for tests that need no manifest interaction). */
    private val checkpointStoreFactory: ((Path) -> CheckpointStore)? = null,
    /** Reads the `pipeline.checkpoint.*` block from the effective `.d-migrate.yaml`.
     *  The Runner merges CLI override (`--checkpoint-dir`) and config default via
     *  [CheckpointConfig.merge]. Default `{ null }` keeps tests source-compatible. */
    private val checkpointConfigResolver: (Path?) -> CheckpointConfig? = { null },
    /** Clock for manifest `createdAt`/`updatedAt`. Separately injectable for deterministic tests. */
    private val clock: () -> Instant = Instant::now,
    /** Lookup for primary-key columns of a table. Only needed for mid-table resume —
     *  runs without `--since-column` never call this. Production wiring injects a
     *  memoized SchemaReader call; tests inject the mapping directly.
     *  Default `{ emptyList() }` simulates "no PK found" (conservative fallback). */
    private val primaryKeyLookup: (ConnectionPool, DatabaseDialect, String) -> List<String> =
        { _, _, _ -> emptyList() },
) {

    private val resumeCoordinator = ExportResumeCoordinator(primaryKeyLookup, stderr)

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

        // Resume CLI preflight: stdout export cannot be resumed (stream
        // is not re-openable).
        if (!request.resume.isNullOrBlank() && request.output == null) {
            stderr(
                "Error: --resume is not supported for stdout export; " +
                    "set --output <file-or-dir> or drop --resume."
            )
            return 2
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

    // ─── Internal DTOs for step results ─────────────────────────

    private data class ResumeContext(
        val operationId: String,
        val resuming: Boolean,
        val skippedTables: Set<String>,
        val initialSlices: Map<String, CheckpointTableSlice>,
    )

    private data class ExportInfra(
        val reader: DataReader,
        val lister: TableLister,
    )

    private data class ExportPreparedContext(
        val reader: DataReader,
        val lister: TableLister,
        val tables: List<String>,
        val output: ExportOutput,
        val options: ExportOptions,
        val filter: DataFilter?,
        val factory: DataChunkWriterFactory,
        val fingerprint: String,
        val primaryKeysByTable: Map<String, List<String>>,
    )

    private data class CheckpointContext(
        val store: CheckpointStore?,
        val dir: Path?,
    )

    private fun executeWithPool(
        request: DataExportRequest,
        connectionConfig: ConnectionConfig,
        charset: Charset,
        pool: ConnectionPool,
    ): Int {
        val infra = resolveInfrastructure(connectionConfig) ?: return 7
        val tables = resolveTables(request, infra.lister, pool) ?: return -1 // sentinel
        if (tables is TablesResult.Exit) return tables.code
        val effectiveTables = (tables as TablesResult.Ok).tables
        val output = resolveOutput(request, effectiveTables) ?: return 2
        val prepared = buildExportContext(request, connectionConfig, charset, pool, effectiveTables, output, infra)
            ?: return -1 // sentinel handled inline
        if (prepared is PreparedResult.Exit) return prepared.code
        val ctx = (prepared as PreparedResult.Ok).value
        val checkpoint = resolveCheckpointContext(request) ?: return 7
        val resume = resolveResumeContext(request, ctx, output, checkpoint, effectiveTables)
            ?: return -1
        if (resume is ResumeResult.Exit) return resume.code
        val resumeCtx = (resume as ResumeResult.Ok).value
        val markers = resolveMarkers(request, resumeCtx, ctx.primaryKeysByTable, effectiveTables) ?: return 3
        val callbacks = buildCallbacks(request, resumeCtx, checkpoint.store, ctx.fingerprint, effectiveTables, markers)
        val staging = setupStaging(output, checkpoint, resumeCtx.operationId)
        val result = executeStreaming(request, pool, ctx, output, staging, resumeCtx, markers, callbacks)
            ?: return 5
        return finalizeAndReport(request, result, staging, checkpoint.store, resumeCtx.operationId)
    }

    // ─── Step functions ──────────────────────────────────────────

    private fun resolveInfrastructure(config: ConnectionConfig): ExportInfra? {
        val reader = try { readerLookup(config.dialect) } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}"); return null
        }
        val lister = try { listerLookup(config.dialect) } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}"); return null
        }
        return ExportInfra(reader, lister)
    }

    private sealed class TablesResult {
        data class Ok(val tables: List<String>) : TablesResult()
        data class Exit(val code: Int) : TablesResult()
    }

    private fun resolveTables(request: DataExportRequest, lister: TableLister, pool: ConnectionPool): TablesResult {
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

    private fun resolveOutput(request: DataExportRequest, tables: List<String>): ExportOutput? {
        return try {
            ExportOutput.resolve(outputPath = request.output, splitFiles = request.splitFiles, tableCount = tables.size)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}"); null
        }
    }

    private sealed class PreparedResult {
        data class Ok(val value: ExportPreparedContext) : PreparedResult()
        data class Exit(val code: Int) : PreparedResult()
    }

    private fun buildExportContext(
        request: DataExportRequest, config: ConnectionConfig, charset: Charset,
        pool: ConnectionPool, tables: List<String>, output: ExportOutput, infra: ExportInfra,
    ): PreparedResult {
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
            rawFilter = request.filter, dialect = config.dialect,
            sinceColumn = request.sinceColumn, since = request.since,
        )
        val pks: Map<String, List<String>> = if (!request.sinceColumn.isNullOrBlank()) {
            resolvePrimaryKeys(pool, config.dialect, tables)
        } else emptyMap()
        val fingerprint = ExportOptionsFingerprint.compute(ExportOptionsFingerprint.Input(
            format = request.format, encoding = request.encoding, csvDelimiter = request.csvDelimiter,
            csvBom = request.csvBom, csvNoHeader = request.csvNoHeader, csvNullString = request.nullString,
            filter = request.filter, sinceColumn = request.sinceColumn, since = request.since,
            tables = tables, outputMode = canonicalOutputMode(output), outputPath = canonicalOutputPath(output),
            primaryKeysByTable = pks,
        ))
        return PreparedResult.Ok(ExportPreparedContext(
            reader = infra.reader, lister = infra.lister,
            tables = tables, output = output, options = options, filter = filter,
            factory = writerFactoryBuilder(), fingerprint = fingerprint, primaryKeysByTable = pks,
        ))
    }

    private fun resolveCheckpointContext(request: DataExportRequest): CheckpointContext? {
        val fromConfig: CheckpointConfig? = try {
            checkpointConfigResolver(request.cliConfigPath)
        } catch (e: Throwable) {
            stderr("Error: Failed to resolve pipeline.checkpoint config: ${e.message}")
            return null
        }
        val merged = CheckpointConfig.merge(cliDirectory = request.checkpointDir, config = fromConfig)
        val dir = merged.directory
        val store = checkpointStoreFactory?.let { factory -> dir?.let { factory(it) } }
        return CheckpointContext(store, dir)
    }

    private sealed class ResumeResult {
        data class Ok(val value: ResumeContext) : ResumeResult()
        data class Exit(val code: Int) : ResumeResult()
    }

    private fun resolveResumeContext(
        request: DataExportRequest, ctx: ExportPreparedContext, output: ExportOutput,
        checkpoint: CheckpointContext, tables: List<String>,
    ): ResumeResult {
        if (!request.resume.isNullOrBlank()) {
            val store = checkpoint.store ?: run {
                stderr("Error: --resume requires a checkpoint directory; set --checkpoint-dir or pipeline.checkpoint.directory.")
                return ResumeResult.Exit(7)
            }
            val resolvedOpId = resolveResumeReference(request.resume, checkpoint.dir!!) ?: run {
                stderr("Error: --resume path must be inside the effective checkpoint directory '${checkpoint.dir}'.")
                return ResumeResult.Exit(7)
            }
            val manifest: CheckpointManifest? = try {
                store.load(resolvedOpId)
            } catch (e: UnsupportedCheckpointVersionException) {
                stderr("Error: ${e.message}"); return ResumeResult.Exit(7)
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to load checkpoint: ${e.message}"); return ResumeResult.Exit(7)
            }
            if (manifest == null) { stderr("Error: Checkpoint not found: '${request.resume}'"); return ResumeResult.Exit(7) }
            if (manifest.operationType != CheckpointOperationType.EXPORT) {
                stderr("Error: Checkpoint type mismatch: expected EXPORT, got ${manifest.operationType}.")
                return ResumeResult.Exit(3)
            }
            if (manifest.optionsFingerprint != ctx.fingerprint) {
                stderr("Error: Checkpoint options do not match the current request (fingerprint mismatch); refuse to resume.")
                return ResumeResult.Exit(3)
            }
            val manifestTables = manifest.tableSlices.map { it.table }
            if (manifestTables != tables) {
                stderr("Error: Checkpoint table list does not match the current request: manifest=$manifestTables, current=$tables.")
                return ResumeResult.Exit(3)
            }
            val skipped = manifest.tableSlices.filter { it.status == CheckpointSliceStatus.COMPLETED }.map { it.table }.toSet()
            if (output is ExportOutput.SingleFile && tables.isNotEmpty() && tables.all { it in skipped }) {
                stderr("Error: single-file resume has no pending table; the previous run is already completed. Remove --resume or choose a different output.")
                return ResumeResult.Exit(3)
            }
            return ResumeResult.Ok(ResumeContext(
                operationId = manifest.operationId, resuming = true,
                skippedTables = skipped, initialSlices = manifest.tableSlices.associateBy { it.table },
            ))
        }
        return ResumeResult.Ok(ResumeContext(
            operationId = java.util.UUID.randomUUID().toString(), resuming = false,
            skippedTables = emptySet(),
            initialSlices = tables.associateWith { CheckpointTableSlice(table = it, status = CheckpointSliceStatus.PENDING) },
        ))
    }

    private fun resolveMarkers(
        request: DataExportRequest, resume: ResumeContext,
        primaryKeysByTable: Map<String, List<String>>, tables: List<String>,
    ): Map<String, ResumeMarker>? {
        return try {
            tables.associateWith { table ->
                resolveTableResumeMarker(request, table, resume.initialSlices[table]?.resumePosition, primaryKeysByTable[table].orEmpty())
            }.filterValues { it != null }.mapValues { it.value!! }
        } catch (e: TableResumeMismatchException) {
            stderr("Error: ${e.message}"); null
        }
    }

    private fun buildCallbacks(
        request: DataExportRequest, resume: ResumeContext, store: CheckpointStore?,
        fingerprint: String, tables: List<String>, markers: Map<String, ResumeMarker>,
    ): ExportCallbacks {
        val operationId = resume.operationId
        val tableStates = LinkedHashMap(resume.initialSlices)
        val now = { clock() }
        val createdAt: Instant = if (resume.resuming && store != null) {
            try { store.load(operationId)?.createdAt ?: now() } catch (_: Throwable) { now() }
        } else now()

        fun saveManifest() {
            if (store == null) return
            try {
                store.save(CheckpointManifest(
                    operationId = operationId, operationType = CheckpointOperationType.EXPORT,
                    createdAt = createdAt, updatedAt = now(), format = request.format,
                    chunkSize = request.chunkSize, tableSlices = tables.map { tableStates.getValue(it) },
                    optionsFingerprint = fingerprint,
                ))
            } catch (_: CheckpointStoreException) { /* silent mid-run save failure */ }
        }

        // Write initial manifest for fresh runs
        if (store != null && !resume.resuming) {
            val created = now()
            try {
                store.save(CheckpointManifest(
                    operationId = operationId, operationType = CheckpointOperationType.EXPORT,
                    createdAt = created, updatedAt = created, format = request.format,
                    chunkSize = request.chunkSize, tableSlices = tables.map { tableStates.getValue(it) },
                    optionsFingerprint = fingerprint,
                ))
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to initialize checkpoint: ${e.message}")
            }
        }

        val onTableCompleted: (dev.dmigrate.streaming.TableExportSummary) -> Unit = { summary ->
            tableStates[summary.table] = CheckpointTableSlice(
                table = summary.table,
                status = if (summary.error == null) CheckpointSliceStatus.COMPLETED else CheckpointSliceStatus.FAILED,
                rowsProcessed = summary.rows, chunksProcessed = summary.chunks, resumePosition = null,
            )
            saveManifest()
        }

        val onChunkProcessed: (dev.dmigrate.streaming.TableChunkProgress) -> Unit = { progress ->
            val marker = markers[progress.table]
            if (marker != null) {
                tableStates[progress.table] = CheckpointTableSlice(
                    table = progress.table, status = CheckpointSliceStatus.IN_PROGRESS,
                    rowsProcessed = progress.rowsProcessed, chunksProcessed = progress.chunksProcessed,
                    resumePosition = MarkerCodec.toPersisted(marker, progress.position),
                )
                saveManifest()
            }
        }

        val effectiveReporter = if (request.quiet || request.noProgress) NoOpProgressReporter else progressReporter
        return ExportCallbacks(effectiveReporter, onTableCompleted, onChunkProcessed)
    }

    private fun setupStaging(output: ExportOutput, checkpoint: CheckpointContext, operationId: String): StagingRedirect? {
        if (output is ExportOutput.SingleFile && checkpoint.store != null && checkpoint.dir != null) {
            return StagingRedirect(target = output.path, staging = checkpoint.dir.resolve("$operationId.single-file.staging"))
        }
        return null
    }

    private fun executeStreaming(
        request: DataExportRequest, pool: ConnectionPool, ctx: ExportPreparedContext,
        output: ExportOutput, staging: StagingRedirect?, resume: ResumeContext,
        markers: Map<String, ResumeMarker>, callbacks: ExportCallbacks,
    ): ExportResult? {
        val executorOutput = staging?.let { ExportOutput.SingleFile(it.staging) } ?: output
        val executorMarkers = if (output is ExportOutput.SingleFile) {
            markers.mapValues { (_, m) -> m.copy(position = null) }
        } else markers

        return try {
            val raw = exportExecutor.execute(
                context = ExportExecutionContext(pool, ctx.reader, ctx.lister, ctx.factory),
                options = ExportExecutionOptions(
                    ctx.tables, executorOutput, DataExportFormat.fromCli(request.format),
                    ctx.options, PipelineConfig(chunkSize = request.chunkSize), ctx.filter,
                ),
                resume = ExportResumeState(resume.operationId, resume.resuming, resume.skippedTables, executorMarkers),
                callbacks = callbacks,
            )
            raw.copy(operationId = resume.operationId)
        } catch (e: Throwable) {
            stderr("Error: Export failed: ${e.message}"); null
        }
    }

    private fun finalizeAndReport(
        request: DataExportRequest, result: ExportResult, staging: StagingRedirect?,
        store: CheckpointStore?, operationId: String,
    ): Int {
        val failed = result.tables.firstOrNull { it.error != null }
        if (failed != null) {
            stderr("Error: Failed to export table '${failed.table}': ${failed.error}")
            return 5
        }
        if (staging != null) {
            try {
                try {
                    java.nio.file.Files.move(staging.staging, staging.target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(staging.staging, staging.target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Throwable) {
                stderr("Error: Failed to move staging file to target '${staging.target}': ${e.message ?: e::class.simpleName}")
                return 5
            }
        }
        if (store != null) {
            try { store.complete(operationId) } catch (e: CheckpointStoreException) {
                stderr("Warning: Failed to remove completed checkpoint: ${e.message}")
            }
        }
        if (!request.quiet) { for (line in collectWarnings()) { stderr(line) } }
        if (!request.quiet && !request.noProgress) {
            stderr(DataExportHelpers.formatProgressSummary(result))
            result.operationId?.let { stderr("Run operation id: $it") }
        }
        return 0
    }

    // ──────────────────────────────────────────────────────────────
    // Helper functions for resume-reference resolution and
    // output-mode canonicalization.
    // ──────────────────────────────────────────────────────────────

    /**
     * Resolves the `--resume <checkpoint-id|path>` value against the
     * effective checkpoint directory:
     *
     * - Path candidate (contains `/` or ends with `.checkpoint.yaml`):
     *   must normalize inside the checkpoint directory; otherwise `null`.
     *   The `operationId` is derived from the filename (suffix `.checkpoint.yaml` stripped).
     * - Otherwise: the value is a direct `operationId`.
     */
    private fun resolveResumeReference(resumeValue: String, checkpointDir: Path): String? =
        resumeCoordinator.resolveResumeReference(resumeValue, checkpointDir)

    private fun resolvePrimaryKeys(
        pool: ConnectionPool,
        dialect: DatabaseDialect,
        tables: List<String>,
    ): Map<String, List<String>> = resumeCoordinator.resolvePrimaryKeys(pool, dialect, tables)

    private fun resolveTableResumeMarker(
        request: DataExportRequest,
        table: String,
        existingPosition: CheckpointResumePosition?,
        primaryKey: List<String>,
    ): ResumeMarker? = resumeCoordinator.resolveTableResumeMarker(
        request.sinceColumn, table, existingPosition, primaryKey,
    )

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

    /** Internal DTO for the staging redirect in single-file runs. `staging` resides in the
     *  checkpoint directory; `target` is the user-requested destination path. */
    private data class StagingRedirect(
        val target: Path,
        val staging: Path,
    )

}

/** Manifest carries a mid-table `resumePosition` but the current request has no `--since-column`.
 *  The run contracts are incompatible; the Runner translates this exception into exit 3. */
internal class TableResumeMismatchException(
    val table: String,
    val markerColumn: String,
) : RuntimeException(
    "Checkpoint for table '$table' carries a mid-table marker on column " +
        "'$markerColumn', but the current request has no --since-column; refuse to resume."
)
