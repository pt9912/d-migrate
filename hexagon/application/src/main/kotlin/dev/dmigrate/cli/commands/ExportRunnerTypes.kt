package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.streaming.ExportOutput
import dev.dmigrate.streaming.ExportResult
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import java.nio.file.Path

// ─── Public Executor DTOs ───────────────────────────────────────

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
    val warningSink: (String) -> Unit = {},
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

// ─── Internal step-result types ─────────────────────────────────

internal data class ExportResumeContext(
    val operationId: String,
    val resuming: Boolean,
    val skippedTables: Set<String>,
    val initialSlices: Map<String, CheckpointTableSlice>,
)

internal data class ExportInfra(
    val reader: DataReader,
    val lister: TableLister,
)

internal data class ExportPreparedContext(
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

internal data class ExportCheckpointContext(
    val store: CheckpointStore?,
    val dir: Path?,
)

internal sealed class TablesResult {
    data class Ok(val tables: List<String>) : TablesResult()
    data class Exit(val code: Int) : TablesResult()
}

internal sealed class PreparedResult {
    data class Ok(val value: ExportPreparedContext) : PreparedResult()
    data class Exit(val code: Int) : PreparedResult()
}

internal sealed class ExportResumeResult {
    data class Ok(val value: ExportResumeContext) : ExportResumeResult()
    data class Exit(val code: Int) : ExportResumeResult()
}

/** Internal DTO for the staging redirect in single-file runs. `staging` resides in the
 *  checkpoint directory; `target` is the user-requested destination path. */
internal data class StagingRedirect(
    val target: Path,
    val staging: Path,
)

/** Manifest carries a mid-table `resumePosition` but the current request has no `--since-column`.
 *  The run contracts are incompatible; the Runner translates this exception into exit 3. */
internal class TableResumeMismatchException(
    val table: String,
    val markerColumn: String,
) : RuntimeException(
    "Checkpoint for table '$table' carries a mid-table marker on column " +
        "'$markerColumn', but the current request has no --since-column; refuse to resume."
)
