package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import java.nio.file.Path

// ─── Public exception types ────────────────────────────────────────

class CliUsageException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class ImportPreflightException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

// ─── Public DTOs ───────────────────────────────────────────────────

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

// ─── Internal step-result types (extracted from DataImportRunner) ──

/** Prepared import options, format configuration, and schema callback. */
internal data class ImportPreparedOptions(
    val importOptions: ImportOptions,
    val formatReadOptions: FormatReadOptions,
    val pipelineConfig: PipelineConfig,
    val onTableOpened: (String, List<TargetColumn>) -> Unit,
)

/** Result of scanning the input directory/file and computing the fingerprint. */
internal data class InputContext(
    val effectiveTables: List<String>,
    val inputFilesByTable: Map<String, String>,
    val fingerprint: String,
)

internal sealed class InputContextResult {
    data class Ok(val value: InputContext) : InputContextResult()
    data class Exit(val code: Int) : InputContextResult()
}

/** Checkpoint store and directory resolved from CLI + config. */
internal data class ImportCheckpointContext(
    val store: CheckpointStore?,
    val dir: Path?,
)

internal sealed class ImportResumeResult {
    data class Ok(val value: ImportResumeContext) : ImportResumeResult()
    data class Exit(val code: Int) : ImportResumeResult()
}

internal sealed class StreamingResult {
    data class Ok(val value: ImportResult) : StreamingResult()
    data class Exit(val code: Int) : StreamingResult()
}

/** Internal resume context of a run. Carries per-table resume states for skip-ahead
 *  and truncate-guard, as well as the initial slice state for manifest updates after each chunk. */
internal data class ImportResumeContext(
    val operationId: String,
    val resuming: Boolean,
    val skippedTables: Set<String>,
    val resumeStateByTable: Map<String, dev.dmigrate.streaming.ImportTableResumeState>,
    val initialSlices: Map<String, CheckpointTableSlice>,
)
