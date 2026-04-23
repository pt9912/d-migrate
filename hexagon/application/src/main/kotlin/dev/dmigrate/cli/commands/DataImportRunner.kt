package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Instant

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

    private val preflightResolver = ImportPreflightResolver(
        targetResolver = targetResolver,
        urlParser = urlParser,
        schemaPreflight = schemaPreflight,
        stdinProvider = stdinProvider,
        stderr = stderr,
    )

    private val executionPlanner = ImportExecutionPlanner(
        preflightValidator = preflightValidator,
        checkpointManager = checkpointManager,
    )

    private val streamingInvoker = ImportStreamingInvoker(
        importExecutor = importExecutor,
        stderr = stderr,
    )

    fun execute(request: DataImportRequest): Int {
        val ctx = when (val result = preflightResolver.resolve(request)) {
            is ImportPreflightResolution.Ok -> result.value
            is ImportPreflightResolution.Exit -> return result.code
        }

        val pool: ConnectionPool = try {
            poolFactory(ctx.connectionConfig)
        } catch (e: Throwable) {
            stderr("Error: Failed to connect to database: ${e.message}")
            return 4
        }

        return try {
            executeWithPool(request, ctx.connectionConfig, ctx.resolvedUrl, ctx.charset, ctx.format, ctx.preparedImport, pool)
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
        val executionPlan = when (
            val result = executionPlanner.prepare(
                request = request,
                connectionConfig = connectionConfig,
                resolvedUrl = resolvedUrl,
                charset = charset,
                format = format,
                preparedImport = preparedImport,
            )
        ) {
            is ImportExecutionPlanResult.Ok -> result.value
            is ImportExecutionPlanResult.Exit -> return result.code
        }
        val result = when (
            val r = streamingInvoker.execute(
                format,
                pool,
                preparedImport,
                executionPlan,
            )
        ) {
            is StreamingResult.Ok -> r.value
            is StreamingResult.Exit -> return r.code
        }
        return finalizeAndReport(
            request,
            result,
            executionPlan.checkpointStore,
            executionPlan.resumeContext.operationId,
        )
    }

    /** Step 9: Evaluate the result, clean up the manifest, and print the summary. */
    private fun finalizeAndReport(
        request: DataImportRequest,
        result: ImportResult,
        store: CheckpointStore?,
        operationId: String,
    ): Int {
        return ImportCompletionSupport.finalizeAndReport(
            request = request,
            result = result,
            store = store,
            operationId = operationId,
            stderr = stderr,
        )
    }

    companion object {
        fun inferFormatFromExtension(path: Path): String? = DataImportHelpers.inferFormatFromExtension(path)

        fun formatProgressSummary(result: ImportResult): String = ImportCompletionSupport.formatProgressSummary(result)
    }
}
