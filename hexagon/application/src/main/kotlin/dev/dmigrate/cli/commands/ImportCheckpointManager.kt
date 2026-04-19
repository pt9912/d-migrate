package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointOperationType
import dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import dev.dmigrate.streaming.checkpoint.UnsupportedCheckpointVersionException
import java.nio.file.Path
import java.time.Instant
import java.util.Locale

/**
 * Checkpoint/resume lifecycle for `d-migrate data import`.
 * Extracted from [DataImportRunner] to keep the runner focused on orchestration.
 */
internal class ImportCheckpointManager(
    private val checkpointStoreFactory: ((Path) -> CheckpointStore)?,
    private val checkpointConfigResolver: (Path?) -> CheckpointConfig?,
    private val clock: () -> Instant,
    private val progressReporter: ProgressReporter,
    private val stderr: (String) -> Unit,
) {

    private val resumeCoordinator = ImportResumeCoordinator()

    /** Merge CLI override and config default for checkpoint directory + store. */
    fun resolveCheckpointContext(request: DataImportRequest): ImportCheckpointContext? {
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
        return ImportCheckpointContext(store, checkpointDir)
    }

    /** Load, validate, and build the resume context (or create a fresh one). */
    fun resolveResumeContext(
        request: DataImportRequest,
        checkpoint: ImportCheckpointContext,
        inputCtx: InputContext,
    ): ImportResumeResult {
        val effectiveTables = inputCtx.effectiveTables
        val inputFilesByTable = inputCtx.inputFilesByTable
        val fingerprint = inputCtx.fingerprint

        if (!request.resume.isNullOrBlank()) {
            val store = checkpoint.store ?: run {
                stderr(
                    "Error: --resume requires a checkpoint directory; set " +
                        "--checkpoint-dir or pipeline.checkpoint.directory."
                )
                return ImportResumeResult.Exit(7)
            }
            val resolvedOpId = resumeCoordinator.resolveResumeReference(request.resume, checkpoint.dir!!)
                ?: run {
                    stderr(
                        "Error: --resume path must be inside the effective " +
                            "checkpoint directory '${checkpoint.dir}'."
                    )
                    return ImportResumeResult.Exit(7)
                }
            val manifest: CheckpointManifest? = try {
                store.load(resolvedOpId)
            } catch (e: UnsupportedCheckpointVersionException) {
                stderr("Error: ${e.message}")
                return ImportResumeResult.Exit(7)
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to load checkpoint: ${e.message}")
                return ImportResumeResult.Exit(7)
            }
            if (manifest == null) {
                stderr("Error: Checkpoint not found: '${request.resume}'")
                return ImportResumeResult.Exit(7)
            }
            if (manifest.operationType != CheckpointOperationType.IMPORT) {
                stderr(
                    "Error: Checkpoint type mismatch: expected IMPORT, got " +
                        "${manifest.operationType}."
                )
                return ImportResumeResult.Exit(3)
            }
            if (manifest.optionsFingerprint != fingerprint) {
                stderr(
                    "Error: Checkpoint options do not match the current request " +
                        "(fingerprint mismatch); refuse to resume."
                )
                return ImportResumeResult.Exit(3)
            }
            val manifestTables = manifest.tableSlices.map { it.table }
            if (manifestTables != effectiveTables) {
                stderr(
                    "Error: Checkpoint table list does not match the current " +
                        "request: manifest=$manifestTables, current=$effectiveTables."
                )
                return ImportResumeResult.Exit(3)
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
                    return ImportResumeResult.Exit(3)
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
            return ImportResumeResult.Ok(ImportResumeContext(
                operationId = manifest.operationId,
                resuming = true,
                skippedTables = skipped,
                resumeStateByTable = resumeStates,
                initialSlices = manifest.tableSlices.associateBy { it.table },
            ))
        }
        return ImportResumeResult.Ok(ImportResumeContext(
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

    /** Write initial manifest for fresh runs. Returns exit code on failure, null on success. */
    fun writeInitialManifest(
        request: DataImportRequest,
        format: DataExportFormat,
        resumeCtx: ImportResumeContext,
        store: CheckpointStore?,
        inputCtx: InputContext,
    ): Int? {
        if (store == null || resumeCtx.resuming) return null
        val created = clock()
        return try {
            store.save(CheckpointManifest(
                operationId = resumeCtx.operationId,
                operationType = CheckpointOperationType.IMPORT,
                createdAt = created, updatedAt = created,
                format = request.format ?: format.name.lowercase(Locale.US),
                chunkSize = request.chunkSize,
                tableSlices = inputCtx.effectiveTables.map { table ->
                    resumeCtx.initialSlices[table] ?: CheckpointTableSlice(table = table, status = CheckpointSliceStatus.PENDING)
                },
                optionsFingerprint = inputCtx.fingerprint,
            ))
            null // success
        } catch (e: CheckpointStoreException) {
            stderr("Error: Failed to initialize checkpoint: ${e.message}")
            7
        }
    }

    /** Build chunk-commit and table-completed callbacks. */
    fun buildCallbacks(
        request: DataImportRequest,
        format: DataExportFormat,
        resumeCtx: ImportResumeContext,
        store: CheckpointStore?,
        inputCtx: InputContext,
    ): ImportCallbacks {
        val operationId = resumeCtx.operationId
        val effectiveTables = inputCtx.effectiveTables
        val inputFilesByTable = inputCtx.inputFilesByTable
        val fingerprint = inputCtx.fingerprint

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
}
