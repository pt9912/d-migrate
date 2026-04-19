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
        /** Stable operation ID for the run. Set by the Runner (UUID or from manifest),
         *  passed through to `StreamingExporter.export` and `ProgressEvent.RunStarted`. */
        operationId: String?,
        /** `true` when resuming from an existing manifest; `false` for a fresh run.
         *  The `ProgressRenderer` uses this flag for the "Starting run ..." vs "Resuming run ..." label. */
        resuming: Boolean,
        /** Tables already marked `COMPLETED` in the manifest. Populated by the Runner
         *  from the loaded manifest; empty for a fresh run. */
        skippedTables: Set<String>,
        /** Callback per completed table. The Runner uses it to update the checkpoint manifest. */
        onTableCompleted: (dev.dmigrate.streaming.TableExportSummary) -> Unit,
        /** Per-table optional [ResumeMarker]. Missing entry -> legacy path without marker-based
         *  ordering. Present entry activates mid-table resume (fresh track or resume-from-position
         *  depending on `ResumeMarker.position`). */
        resumeMarkers: Map<String, ResumeMarker>,
        /** Chunk-granular progress callback. The Runner updates the manifest with each
         *  invocation — only tables with an active [ResumeMarker] trigger this callback. */
        onChunkProcessed: (dev.dmigrate.streaming.TableChunkProgress) -> Unit,
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

        // ─── 8b. Resume-Preflight + Manifest-Lifecycle ─────────────
        // PK signature is only read when `--since-column` is set — without
        // a marker contract there is no mid-table resume and no reason to
        // touch the schema reader.
        val primaryKeysByTable: Map<String, List<String>> =
            if (!request.sinceColumn.isNullOrBlank()) {
                resolvePrimaryKeys(pool, connectionConfig.dialect, effectiveTables)
            } else {
                emptyMap()
            }
        val fingerprint = ExportOptionsFingerprint.compute(
            ExportOptionsFingerprint.Input(
                format = request.format,
                encoding = request.encoding,
                csvDelimiter = request.csvDelimiter,
                csvBom = request.csvBom,
                csvNoHeader = request.csvNoHeader,
                csvNullString = request.nullString,
                filter = request.filter,
                sinceColumn = request.sinceColumn,
                since = request.since,
                tables = effectiveTables,
                outputMode = canonicalOutputMode(exportOutput),
                outputPath = canonicalOutputPath(exportOutput),
                primaryKeysByTable = primaryKeysByTable,
            )
        )

        // Merge CLI override (`--checkpoint-dir`) and config default
        // (`pipeline.checkpoint.*`). CLI wins over config, config wins
        // over runtime default.
        val fromConfig: CheckpointConfig? = try {
            checkpointConfigResolver(request.cliConfigPath)
        } catch (e: Throwable) {
            stderr("Error: Failed to resolve pipeline.checkpoint config: ${e.message}")
            return 7
        }
        val mergedCheckpointConfig = CheckpointConfig.merge(
            cliDirectory = request.checkpointDir,
            config = fromConfig,
        )
        val checkpointDir: Path? = mergedCheckpointConfig.directory
        val store: CheckpointStore? = checkpointStoreFactory?.let { factory ->
            checkpointDir?.let { factory(it) }
        }

        // Resume-Pfad vorbereiten
        data class ResumeContext(
            val operationId: String,
            val resuming: Boolean,
            val skippedTables: Set<String>,
            val initialSlices: Map<String, CheckpointTableSlice>,
        )

        val resume: ResumeContext = if (!request.resume.isNullOrBlank()) {
            if (store == null) {
                stderr(
                    "Error: --resume requires a checkpoint directory; set " +
                        "--checkpoint-dir or pipeline.checkpoint.directory."
                )
                return 7
            }
            val resolvedOpId = resolveResumeReference(request.resume, checkpointDir!!)
                ?: run {
                    stderr(
                        "Error: --resume path must be inside the effective " +
                            "checkpoint directory '$checkpointDir'."
                    )
                    return 7
                }
            val manifest: CheckpointManifest? = try {
                store.load(resolvedOpId)
            } catch (e: UnsupportedCheckpointVersionException) {
                stderr("Error: ${e.message}")
                return 7
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to load checkpoint: ${e.message}")
                return 7
            }
            if (manifest == null) {
                stderr("Error: Checkpoint not found: '${request.resume}'")
                return 7
            }
            if (manifest.operationType != CheckpointOperationType.EXPORT) {
                stderr(
                    "Error: Checkpoint type mismatch: expected EXPORT, got " +
                        "${manifest.operationType}."
                )
                return 3
            }
            if (manifest.optionsFingerprint != fingerprint) {
                stderr(
                    "Error: Checkpoint options do not match the current request " +
                        "(fingerprint mismatch); refuse to resume."
                )
                return 3
            }
            val manifestTables = manifest.tableSlices.map { it.table }
            if (manifestTables != effectiveTables) {
                stderr(
                    "Error: Checkpoint table list does not match the current " +
                        "request: manifest=$manifestTables, current=$effectiveTables."
                )
                return 3
            }
            val skipped = manifest.tableSlices
                .filter { it.status == CheckpointSliceStatus.COMPLETED }
                .map { it.table }
                .toSet()
            // Single-file resume requires exactly one pending table.
            // If the table is already COMPLETED, the run is finished —
            // exit 3 instead of silent success so automation can detect it.
            if (exportOutput is ExportOutput.SingleFile &&
                effectiveTables.isNotEmpty() &&
                effectiveTables.all { it in skipped }
            ) {
                stderr(
                    "Error: single-file resume has no pending table; the " +
                        "previous run is already completed. Remove --resume " +
                        "or choose a different output."
                )
                return 3
            }
            ResumeContext(
                operationId = manifest.operationId,
                resuming = true,
                skippedTables = skipped,
                initialSlices = manifest.tableSlices.associateBy { it.table },
            )
        } else {
            ResumeContext(
                operationId = java.util.UUID.randomUUID().toString(),
                resuming = false,
                skippedTables = emptySet(),
                initialSlices = effectiveTables.associateWith { table ->
                    CheckpointTableSlice(
                        table = table,
                        status = CheckpointSliceStatus.PENDING,
                    )
                },
            )
        }

        val operationId = resume.operationId
        val tableStates = LinkedHashMap(resume.initialSlices)
        val now = { clock() }

        // Initialer Manifest-Schreibpfad: bei neuem Lauf anlegen,
        // damit ein spaeterer Abbruch einen gueltigen Resume-Anker hat.
        if (store != null && !resume.resuming) {
            val created = now()
            val initial = CheckpointManifest(
                operationId = operationId,
                operationType = CheckpointOperationType.EXPORT,
                createdAt = created,
                updatedAt = created,
                format = request.format,
                chunkSize = request.chunkSize,
                tableSlices = effectiveTables.map { tableStates.getValue(it) },
                optionsFingerprint = fingerprint,
            )
            try {
                store.save(initial)
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to initialize checkpoint: ${e.message}")
                return 7
            }
        }

        // Per-table update callback: `createdAt` is set now for a fresh
        // run, or taken from the already-loaded manifest on resume.
        val createdAt: Instant = if (resume.resuming && store != null) {
            try {
                store.load(operationId)?.createdAt ?: now()
            } catch (_: Throwable) { now() }
        } else {
            now()
        }

        val onTableCompleted: (dev.dmigrate.streaming.TableExportSummary) -> Unit = { summary ->
            val slice = CheckpointTableSlice(
                table = summary.table,
                status = if (summary.error == null)
                    CheckpointSliceStatus.COMPLETED else CheckpointSliceStatus.FAILED,
                rowsProcessed = summary.rows,
                chunksProcessed = summary.chunks,
                // On COMPLETED the resume position is intentionally omitted —
                // the table is done and a subsequent resume skips it entirely.
                resumePosition = null,
            )
            tableStates[summary.table] = slice
            if (store != null) {
                try {
                    store.save(
                        CheckpointManifest(
                            operationId = operationId,
                            operationType = CheckpointOperationType.EXPORT,
                            createdAt = createdAt,
                            updatedAt = now(),
                            format = request.format,
                            chunkSize = request.chunkSize,
                            tableSlices = effectiveTables.map { tableStates.getValue(it) },
                            optionsFingerprint = fingerprint,
                        )
                    )
                } catch (_: CheckpointStoreException) {
                    // Fortsetzung darf an einem fehlgeschlagenen
                    // Zwischen-Save nicht abbrechen; der finale Lauf
                    // meldet dann ggf. den Fehler. Stderr-Hinweis
                    // wuerde den Progress-Output zerreissen — wir
                    // lassen den Write stillschweigend fallen und
                    // fangen ihn am Lauf-Ende nochmal (complete()).
                }
            }
        }

        // Per-table ResumeMarker based on three case distinctions.
        // The loop may abort with exit 3 (case 3). An empty result
        // (no markers) preserves the legacy path.
        val resumeMarkers: Map<String, ResumeMarker> = try {
            effectiveTables.associateWith { table ->
                resolveTableResumeMarker(
                    request = request,
                    table = table,
                    existingPosition = resume.initialSlices[table]?.resumePosition,
                    primaryKey = primaryKeysByTable[table].orEmpty(),
                )
            }.filterValues { it != null }.mapValues { it.value!! }
        } catch (e: TableResumeMismatchException) {
            stderr("Error: ${e.message}")
            return 3
        }

        // onChunkProcessed persists per-chunk progress. The table
        // transitions to `IN_PROGRESS` so a re-run can find the position.
        val onChunkProcessed: (dev.dmigrate.streaming.TableChunkProgress) -> Unit = { progress ->
            val marker = resumeMarkers[progress.table]
            if (marker != null) {
                val slice = CheckpointTableSlice(
                    table = progress.table,
                    status = CheckpointSliceStatus.IN_PROGRESS,
                    rowsProcessed = progress.rowsProcessed,
                    chunksProcessed = progress.chunksProcessed,
                    resumePosition = MarkerCodec.toPersisted(marker, progress.position),
                )
                tableStates[progress.table] = slice
                if (store != null) {
                    try {
                        store.save(
                            CheckpointManifest(
                                operationId = operationId,
                                operationType = CheckpointOperationType.EXPORT,
                                createdAt = createdAt,
                                updatedAt = now(),
                                format = request.format,
                                chunkSize = request.chunkSize,
                                tableSlices = effectiveTables.map { tableStates.getValue(it) },
                                optionsFingerprint = fingerprint,
                            )
                        )
                    } catch (_: CheckpointStoreException) {
                        // Gleiches Prinzip wie in `onTableCompleted`: ein
                        // verlorener Zwischen-Save darf den Lauf nicht
                        // abbrechen; beim naechsten Chunk versucht der
                        // Runner es erneut.
                    }
                }
            }
        }

        // ─── 8d. Single-File-Staging-Pfad ────────────────────────────
        // Single-table-single-file is never written directly to the target
        // when a checkpoint store is configured — otherwise an abort could
        // leave a half-valid container file (JSON without `]`, YAML/CSV
        // without terminator) at the target path. Instead the run writes
        // to a staging file in the checkpoint directory; the target is
        // replaced via atomic rename at run end.
        //
        // Mid-table resume is intentionally **not** active for single-file
        // runs: structured format writers (JSON array, YAML sequence) cannot
        // be cleanly continued mid-stream without append/rebuild infra.
        // An aborted single-file resume re-exports the table from scratch
        // into a fresh staging file. The `optionsFingerprint` stays valid,
        // the manifest slice transitions to `COMPLETED` afterwards. Any
        // stored `resumePosition` from a previous run is ignored; we clear
        // it from the marker so the exporter does a fresh track.
        val stagingRedirect: StagingRedirect? =
            if (exportOutput is ExportOutput.SingleFile && store != null && checkpointDir != null) {
                val stagingPath = checkpointDir.resolve("$operationId.single-file.staging")
                StagingRedirect(
                    target = exportOutput.path,
                    staging = stagingPath,
                )
            } else {
                null
            }
        val executorOutput: ExportOutput = stagingRedirect?.let {
            ExportOutput.SingleFile(it.staging)
        } ?: exportOutput
        val executorMarkers: Map<String, ResumeMarker> =
            if (exportOutput is ExportOutput.SingleFile) {
                resumeMarkers.mapValues { (_, marker) -> marker.copy(position = null) }
            } else {
                resumeMarkers
            }

        // ─── 9. Streaming ─────────────────────────────────────
        val rawResult: ExportResult = try {
            val effectiveReporter = if (request.quiet || request.noProgress)
                NoOpProgressReporter else progressReporter
            exportExecutor.execute(
                pool = pool,
                reader = reader,
                lister = tableLister,
                factory = factory,
                tables = effectiveTables,
                output = executorOutput,
                format = DataExportFormat.fromCli(request.format),
                options = exportOptions,
                config = PipelineConfig(chunkSize = request.chunkSize),
                filter = effectiveFilter,
                progressReporter = effectiveReporter,
                operationId = operationId,
                resuming = resume.resuming,
                skippedTables = resume.skippedTables,
                onTableCompleted = onTableCompleted,
                resumeMarkers = executorMarkers,
                onChunkProcessed = onChunkProcessed,
            )
        } catch (e: Throwable) {
            stderr("Error: Export failed: ${e.message}")
            return 5
        }
        val result: ExportResult = rawResult.copy(operationId = operationId)

        // ─── 10. Pro-Tabelle-Fehler → Exit 5 ──────────────────
        val failed = result.tables.firstOrNull { it.error != null }
        if (failed != null) {
            stderr("Error: Failed to export table '${failed.table}': ${failed.error}")
            return 5
        }

        // Staging -> target via atomic rename. A failed rename leaves the
        // staging file in the checkpoint directory; exit 5 because the
        // export is not logically complete (target file missing or stale).
        if (stagingRedirect != null) {
            try {
                try {
                    java.nio.file.Files.move(
                        stagingRedirect.staging,
                        stagingRedirect.target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        stagingRedirect.staging,
                        stagingRedirect.target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (e: Throwable) {
                stderr(
                    "Error: Failed to move staging file to target " +
                        "'${stagingRedirect.target}': ${e.message ?: e::class.simpleName}"
                )
                return 5
            }
        }

        // Successful run -> remove manifest. Errors during complete() are
        // reported as warnings, not as exit — the export was logically successful.
        if (store != null) {
            try {
                store.complete(operationId)
            } catch (e: CheckpointStoreException) {
                stderr("Warning: Failed to remove completed checkpoint: ${e.message}")
            }
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
            // operationId is printed in the stderr summary so that
            // operators, logs, and a later resume manifest all reference
            // the same run.
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
