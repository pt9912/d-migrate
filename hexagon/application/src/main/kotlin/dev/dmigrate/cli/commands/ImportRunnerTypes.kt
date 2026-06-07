package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.connection.ConnectionConfig
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
import java.nio.charset.Charset
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
    val cancellationToken: CancellationToken = CancellationToken.none(),
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

/** Prepared execution state for the streaming phase of an import run. */
internal data class ImportExecutionPlan(
    val options: ImportPreparedOptions,
    val checkpointStore: CheckpointStore?,
    val resumeContext: ImportResumeContext,
    val callbacks: ImportCallbacks,
)

internal sealed class ImportExecutionPlanResult {
    data class Ok(val value: ImportExecutionPlan) : ImportExecutionPlanResult()
    data class Exit(val code: Int) : ImportExecutionPlanResult()
}

/** Resolved preflight state required before a DB connection is opened. */
internal data class ImportPreflightContext(
    val format: DataExportFormat,
    val preparedImport: SchemaPreflightResult,
    val charset: Charset?,
    val resolvedUrl: String,
    val connectionConfig: ConnectionConfig,
)

internal sealed class ImportPreflightResolution {
    data class Ok(val value: ImportPreflightContext) : ImportPreflightResolution()
    data class Exit(val code: Int) : ImportPreflightResolution()
}

/** Result of scanning the input directory/file and computing the fingerprint. */
internal data class InputContext(
    val effectiveTables: List<String>,
    val inputFilesByTable: Map<String, String>,
    val fingerprint: String,
    /**
     * S8b (AP9 §7.5): per-Tabelle SHA-256-Map fuer Parquet-Bundle-
     * Importe (Wert kann null sein, wenn der Producer den Hash nicht
     * geschrieben hat — Live-Pruefung wird dann uebersprungen). `null`
     * fuer Nicht-Bundle-Quellen, dient als „Parquet-Bundle-Lauf?"-
     * Anker beim Pre-AP8-Branch in [ImportCheckpointManager].
     */
    val bundleExpectedSha256ByTable: Map<String, String?>? = null,
    /**
     * S8b (AP11 §6.4): Content-SHA-256 fuer Parquet-Single-File-
     * Importe. `null` fuer Nicht-Single-File-Quellen oder bei
     * `--no-checkpoint`/Fresh-Run (Hash wird erst beim `--resume`
     * berechnet, siehe `ImportPreflightResolver.kt:76-79`).
     */
    val singleFileContentSha256: String? = null,
    /**
     * S8c (AP9 §4.2): Bundle-Resume-Fingerprint aus
     * `ImportInput.ResolvedBundle.resumeFingerprint`. Wird beim
     * Initial-Lauf in `BundleCheckpointSpecifics` persistiert und
     * beim `--resume` gegen den frisch berechneten verglichen.
     * `null` fuer Nicht-Bundle-Quellen.
     */
    val bundleResumeFingerprint: dev.dmigrate.streaming.BundleResumeFingerprint? = null,
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
