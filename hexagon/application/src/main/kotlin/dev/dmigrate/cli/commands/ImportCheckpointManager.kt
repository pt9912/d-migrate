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
import java.util.concurrent.ConcurrentHashMap

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

    fun resolveResumeContext(
        request: DataImportRequest,
        checkpoint: ImportCheckpointContext,
        inputCtx: InputContext,
    ): ImportResumeResult {
        if (!request.resume.isNullOrBlank()) {
            return resolveExistingResume(request.resume, checkpoint, inputCtx)
        }
        return buildFreshContext(inputCtx)
    }

    private fun resolveExistingResume(
        resumeRef: String,
        checkpoint: ImportCheckpointContext,
        inputCtx: InputContext,
    ): ImportResumeResult {
        val store = checkpoint.store ?: run {
            stderr("Error: --resume requires a checkpoint directory; set --checkpoint-dir or pipeline.checkpoint.directory.")
            return ImportResumeResult.Exit(7)
        }
        val resolvedOpId = resumeCoordinator.resolveResumeReference(resumeRef, checkpoint.dir!!) ?: run {
            stderr("Error: --resume path must be inside the effective checkpoint directory '${checkpoint.dir}'.")
            return ImportResumeResult.Exit(7)
        }
        val manifest = loadManifest(store, resolvedOpId, resumeRef) ?: return ImportResumeResult.Exit(7)
        validateManifest(manifest, inputCtx)?.let { return it }
        return buildResumeContextFromManifest(manifest)
    }

    private fun loadManifest(store: CheckpointStore, opId: String, resumeRef: String): CheckpointManifest? {
        val manifest = try {
            store.load(opId)
        } catch (e: UnsupportedCheckpointVersionException) {
            stderr("Error: ${e.message}"); return null
        } catch (e: CheckpointStoreException) {
            stderr("Error: Failed to load checkpoint: ${e.message}"); return null
        }
        if (manifest == null) stderr("Error: Checkpoint not found: '$resumeRef'")
        return manifest
    }

    private fun validateManifest(manifest: CheckpointManifest, inputCtx: InputContext): ImportResumeResult? {
        if (manifest.operationType != CheckpointOperationType.IMPORT) {
            stderr("Error: Checkpoint type mismatch: expected IMPORT, got ${manifest.operationType}.")
            return ImportResumeResult.Exit(3)
        }
        if (manifest.optionsFingerprint != inputCtx.fingerprint) {
            stderr("Error: Checkpoint options do not match the current request (fingerprint mismatch); refuse to resume.")
            return ImportResumeResult.Exit(3)
        }
        val manifestTables = manifest.tableSlices.map { it.table }
        if (manifestTables != inputCtx.effectiveTables) {
            stderr(
                "Error: Checkpoint table list does not match the current request: " +
                    "manifest=$manifestTables, current=${inputCtx.effectiveTables}."
            )
            return ImportResumeResult.Exit(3)
        }
        if (inputCtx.inputFilesByTable.isNotEmpty()) {
            val mismatch = manifest.tableSlices.firstOrNull { slice ->
                slice.inputFile != inputCtx.inputFilesByTable[slice.table]
            }
            if (mismatch != null) {
                stderr(
                    "Error: Checkpoint input-file binding for table '${mismatch.table}' does not match " +
                        "the current directory scan (manifest=${mismatch.inputFile ?: "<none>"}, " +
                        "current=${inputCtx.inputFilesByTable[mismatch.table] ?: "<none>"})."
                )
                return ImportResumeResult.Exit(3)
            }
        }
        return null
    }

    private fun buildResumeContextFromManifest(manifest: CheckpointManifest): ImportResumeResult {
        val skipped = manifest.tableSlices
            .filter { it.status == CheckpointSliceStatus.COMPLETED }
            .map { it.table }.toSet()
        val resumeStates = manifest.tableSlices
            .filter { it.status != CheckpointSliceStatus.COMPLETED && it.chunksProcessed > 0L }
            .associate { slice ->
                slice.table to dev.dmigrate.streaming.ImportTableResumeState(committedChunks = slice.chunksProcessed)
            }
        return ImportResumeResult.Ok(ImportResumeContext(
            operationId = manifest.operationId, resuming = true,
            skippedTables = skipped, resumeStateByTable = resumeStates,
            initialSlices = manifest.tableSlices.associateBy { it.table },
        ))
    }

    private fun buildFreshContext(inputCtx: InputContext): ImportResumeResult {
        return ImportResumeResult.Ok(ImportResumeContext(
            operationId = java.util.UUID.randomUUID().toString(),
            resuming = false, skippedTables = emptySet(), resumeStateByTable = emptyMap(),
            initialSlices = inputCtx.effectiveTables.associateWith { table ->
                CheckpointTableSlice(
                    table = table, status = CheckpointSliceStatus.PENDING,
                    inputFile = inputCtx.inputFilesByTable[table],
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
        val warningKeys = ConcurrentHashMap.newKeySet<String>()

        fun warnOnce(key: String, message: String) {
            if (warningKeys.add(key)) {
                stderr("Warning: $message")
            }
        }

        val tableStates = LinkedHashMap(resumeCtx.initialSlices)
        val createdAt: Instant = if (resumeCtx.resuming && store != null) {
            try {
                store.load(operationId)?.createdAt ?: clock()
            } catch (e: Throwable) {
                warnOnce("checkpoint-created-at", "Failed to reload checkpoint metadata: ${e.message ?: e::class.simpleName}")
                clock()
            }
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
            } catch (e: CheckpointStoreException) {
                warnOnce("checkpoint-save", "Failed to update import checkpoint during the run: ${e.message}")
            }
        }

        val onChunkCommitted: (dev.dmigrate.streaming.ImportChunkCommit) -> Unit = { commit ->
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
            val status = if (summary.error == null && summary.failedFinish == null) {
                CheckpointSliceStatus.COMPLETED
            } else {
                CheckpointSliceStatus.FAILED
            }
            val slice = CheckpointTableSlice(
                table = summary.table,
                status = status,
                rowsProcessed = summary.rowsInserted + summary.rowsUpdated +
                    summary.rowsSkipped + summary.rowsUnknown + summary.rowsFailed,
                chunksProcessed = tableStates[summary.table]?.chunksProcessed ?: 0L,
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
