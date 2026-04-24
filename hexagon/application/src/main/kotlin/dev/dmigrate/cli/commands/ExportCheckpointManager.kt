package dev.dmigrate.cli.commands

import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.ExportOutput
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Checkpoint and resume management for `d-migrate data export`.
 * Extracted from [DataExportRunner] to keep the runner focused on orchestration.
 *
 * Covers: checkpoint config resolution, resume context building, marker
 * resolution, initial manifest writing, callback construction, and staging setup.
 */
internal class ExportCheckpointManager(
    private val checkpointStoreFactory: ((Path) -> CheckpointStore)?,
    private val checkpointConfigResolver: (Path?) -> CheckpointConfig?,
    private val resumeCoordinator: ExportResumeCoordinator,
    private val clock: () -> Instant,
    private val progressReporter: ProgressReporter,
    private val stderr: (String) -> Unit,
) {

    fun resolveCheckpointContext(request: DataExportRequest): ExportCheckpointContext? {
        val fromConfig: CheckpointConfig? = try {
            checkpointConfigResolver(request.cliConfigPath)
        } catch (e: Throwable) {
            stderr("Error: Failed to resolve pipeline.checkpoint config: ${e.message}")
            return null
        }
        val merged = CheckpointConfig.merge(cliDirectory = request.checkpointDir, config = fromConfig)
        val dir = merged.directory
        val store = checkpointStoreFactory?.let { factory -> dir?.let { factory(it) } }
        return ExportCheckpointContext(store, dir)
    }

    fun resolveResumeContext(
        request: DataExportRequest, ctx: ExportPreparedContext, output: ExportOutput,
        checkpoint: ExportCheckpointContext, tables: List<String>,
    ): ExportResumeResult {
        if (!request.resume.isNullOrBlank()) {
            val store = checkpoint.store ?: run {
                stderr("Error: --resume requires a checkpoint directory; set --checkpoint-dir or pipeline.checkpoint.directory.")
                return ExportResumeResult.Exit(7)
            }
            val resolvedOpId = resumeCoordinator.resolveResumeReference(request.resume, checkpoint.dir!!) ?: run {
                stderr("Error: --resume path must be inside the effective checkpoint directory '${checkpoint.dir}'.")
                return ExportResumeResult.Exit(7)
            }
            val manifest: CheckpointManifest? = try {
                store.load(resolvedOpId)
            } catch (e: UnsupportedCheckpointVersionException) {
                stderr("Error: ${e.message}"); return ExportResumeResult.Exit(7)
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to load checkpoint: ${e.message}"); return ExportResumeResult.Exit(7)
            }
            if (manifest == null) { stderr("Error: Checkpoint not found: '${request.resume}'"); return ExportResumeResult.Exit(7) }
            if (manifest.operationType != CheckpointOperationType.EXPORT) {
                stderr("Error: Checkpoint type mismatch: expected EXPORT, got ${manifest.operationType}.")
                return ExportResumeResult.Exit(3)
            }
            if (manifest.optionsFingerprint != ctx.fingerprint) {
                // Formal detection via schemaVersion:
                // v1 checkpoints used raw --filter text for fingerprinting.
                // v2 (0.9.3+) uses DSL canonical form. The formats are
                // incompatible, so a v1 checkpoint with a --filter always
                // mismatches against a v2 fingerprint.
                if (manifest.schemaVersion < 2 && request.filter != null) {
                    stderr(
                        "Error: Checkpoint was created with schema version ${manifest.schemaVersion} " +
                            "(pre-0.9.3), which used raw SQL for the --filter fingerprint. " +
                            "Since 0.9.3 (schema version 2), --filter uses a DSL canonical form. " +
                            "Start a new export without --resume, or delete the old checkpoint."
                    )
                    return ExportResumeResult.Exit(2)
                }
                stderr("Error: Checkpoint options do not match the current request (fingerprint mismatch); refuse to resume.")
                return ExportResumeResult.Exit(3)
            }
            val manifestTables = manifest.tableSlices.map { it.table }
            if (manifestTables != tables) {
                stderr("Error: Checkpoint table list does not match the current request: manifest=$manifestTables, current=$tables.")
                return ExportResumeResult.Exit(3)
            }
            val skipped = manifest.tableSlices.filter { it.status == CheckpointSliceStatus.COMPLETED }.map { it.table }.toSet()
            if (output is ExportOutput.SingleFile && tables.isNotEmpty() && tables.all { it in skipped }) {
                stderr(
                    "Error: single-file resume has no pending table; the previous run is already completed. " +
                        "Remove --resume or choose a different output."
                )
                return ExportResumeResult.Exit(3)
            }
            return ExportResumeResult.Ok(ExportResumeContext(
                operationId = manifest.operationId, resuming = true,
                skippedTables = skipped, initialSlices = manifest.tableSlices.associateBy { it.table },
            ))
        }
        return ExportResumeResult.Ok(ExportResumeContext(
            operationId = java.util.UUID.randomUUID().toString(), resuming = false,
            skippedTables = emptySet(),
            initialSlices = tables.associateWith { CheckpointTableSlice(table = it, status = CheckpointSliceStatus.PENDING) },
        ))
    }

    fun resolveMarkers(
        request: DataExportRequest, resume: ExportResumeContext,
        primaryKeysByTable: Map<String, List<String>>, tables: List<String>,
    ): Map<String, ResumeMarker>? {
        return try {
            tables.associateWith { table ->
                resumeCoordinator.resolveTableResumeMarker(
                    request.sinceColumn, table, resume.initialSlices[table]?.resumePosition, primaryKeysByTable[table].orEmpty(),
                )
            }.filterValues { it != null }.mapValues { it.value!! }
        } catch (e: TableResumeMismatchException) {
            stderr("Error: ${e.message}"); null
        }
    }

    /** Write initial manifest for fresh runs. Returns exit code on failure, null on success. */
    fun writeInitialManifest(
        request: DataExportRequest, resume: ExportResumeContext, store: CheckpointStore?,
        fingerprint: String, tables: List<String>,
    ): Int? {
        if (store == null || resume.resuming) return null
        val created = clock()
        return try {
            store.save(CheckpointManifest(
                operationId = resume.operationId, operationType = CheckpointOperationType.EXPORT,
                createdAt = created, updatedAt = created, format = request.format,
                chunkSize = request.chunkSize,
                tableSlices = tables.map { table ->
                    resume.initialSlices[table] ?: CheckpointTableSlice(
                        table = table,
                        status = CheckpointSliceStatus.PENDING,
                    )
                },
                optionsFingerprint = fingerprint,
            ))
            null // success
        } catch (e: CheckpointStoreException) {
            stderr("Error: Failed to initialize checkpoint: ${e.message}")
            7
        }
    }

    fun buildCallbacks(
        request: DataExportRequest, resume: ExportResumeContext, store: CheckpointStore?,
        fingerprint: String, tables: List<String>, markers: Map<String, ResumeMarker>,
    ): ExportCallbacks {
        val operationId = resume.operationId
        val tableStates = LinkedHashMap(resume.initialSlices)
        val now = { clock() }
        val warningKeys = ConcurrentHashMap.newKeySet<String>()

        fun warnOnce(key: String, message: String) {
            if (warningKeys.add(key)) {
                stderr("Warning: $message")
            }
        }

        val createdAt: Instant = if (resume.resuming && store != null) {
            try {
                store.load(operationId)?.createdAt ?: now()
            } catch (e: Throwable) {
                warnOnce("checkpoint-created-at", "Failed to reload checkpoint metadata: ${e.message ?: e::class.simpleName}")
                now()
            }
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
            } catch (e: CheckpointStoreException) {
                warnOnce("checkpoint-save", "Failed to update export checkpoint during the run: ${e.message}")
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
        return ExportCallbacks(
            progressReporter = effectiveReporter,
            onTableCompleted = onTableCompleted,
            onChunkProcessed = onChunkProcessed,
            warningSink = stderr,
        )
    }

    fun setupStaging(output: ExportOutput, checkpoint: ExportCheckpointContext, operationId: String): StagingRedirect? {
        if (output is ExportOutput.SingleFile && checkpoint.store != null && checkpoint.dir != null) {
            return StagingRedirect(target = output.path, staging = checkpoint.dir.resolve("$operationId.single-file.staging"))
        }
        return null
    }
}
