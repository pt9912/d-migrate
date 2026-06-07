package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.OperationCancelledException
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
    /** Parquet Cut A S6 (AP12 §4.2): disables checkpoint reads/writes for the
     *  current run. Mutually exclusive with [resume] (Exit 2 in
     *  `validateCliFlags`). When `true`, the Phase-1-Hook gets
     *  `computeContentSha256 = false`, and the [ImportCheckpointManager]
     *  returns a null store so the run never touches the on-disk manifest. */
    val noCheckpoint: Boolean = false,
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
    /** Parquet Cut A S6: parquet-freier Phase-1-Hook. Identity-Default fuer
     *  Nicht-Parquet-Pfade; CLI verdrahtet die Parquet-Implementierung. */
    private val phase1Hook: ImportInputPhase1Hook = ImportInputPhase1Hook.IDENTITY,
    /** Parquet Cut A S6: parquet-freier Phase-2-Hook, der vor
     *  [ImportExecutionPlanner.prepare] laeuft. Identity-Default; CLI
     *  verdrahtet die Parquet-Implementierung. */
    private val phase2Hook: ImportInputPhase2Hook = ImportInputPhase2Hook.IDENTITY,
) {
    private val userFacingErrors = UserFacingErrors()
    private val userFacingStderr = userFacingErrors.stderrSink(stderr)

    private val preflightValidator = ImportPreflightValidator(
        writerLookup = writerLookup,
        schemaTargetValidator = schemaTargetValidator,
        stderr = userFacingStderr,
    )

    private val checkpointManager = ImportCheckpointManager(
        checkpointStoreFactory = checkpointStoreFactory,
        checkpointConfigResolver = checkpointConfigResolver,
        clock = clock,
        progressReporter = progressReporter,
        stderr = userFacingStderr,
    )

    private val preflightResolver = ImportPreflightResolver(
        targetResolver = targetResolver,
        urlParser = urlParser,
        schemaPreflight = schemaPreflight,
        stdinProvider = stdinProvider,
        stderr = userFacingStderr,
        phase1Hook = phase1Hook,
    )

    private val executionPlanner = ImportExecutionPlanner(
        preflightValidator = preflightValidator,
        checkpointManager = checkpointManager,
    )

    private val streamingInvoker = ImportStreamingInvoker(
        importExecutor = importExecutor,
        stderr = userFacingStderr,
    )

    fun execute(
        request: DataImportRequest,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): Int {
        return try {
            executeWithCancel(request, cancellationToken)
        } catch (_: OperationCancelledException) {
            // LF-008 / LF-009 / LF-013 — Cancel maps to CLI exit 130, never to the generic
            // 5 (import failure) path that the inner catch-Throwable produces.
            CANCELLED_EXIT_CODE
        }
    }

    private fun executeWithCancel(
        request: DataImportRequest,
        cancellationToken: CancellationToken,
    ): Int {
        val ctx = when (val result = resolveRequest(request)) {
            is ImportPreflightResolution.Ok -> result.value
            is ImportPreflightResolution.Exit -> return result.code
        }

        cancellationToken.throwIfCancellationRequested()
        val pool = connect(ctx.connectionConfig) ?: return 4

        return try {
            runImport(request, ctx, pool, cancellationToken)
        } finally {
            runCatching { pool.close() }
        }
    }

    private fun resolveRequest(request: DataImportRequest): ImportPreflightResolution =
        preflightResolver.resolve(request)

    private fun connect(connectionConfig: ConnectionConfig): ConnectionPool? {
        return try {
            poolFactory(connectionConfig)
        } catch (e: Throwable) {
            userFacingStderr("Error: Failed to connect to database: ${e.message}")
            null
        }
    }

    private fun runImport(
        request: DataImportRequest,
        context: ImportPreflightContext,
        pool: ConnectionPool,
        cancellationToken: CancellationToken,
    ): Int {
        // Parquet Cut A S6: Phase-2-Hook laeuft **vor** ImportExecutionPlanner.prepare,
        // damit InputContext, Fingerprint, Resume-Context und Initialmanifest
        // gegen den finalisierten Input rechnen. resumeExpectedSha256 ist in
        // S6 immer null; der non-null-Pfad kommt mit S8 (SingleFileCheckpointSpecifics).
        //
        // Exit-Code-Mapping (symmetrisch zu ImportPreflightResolver):
        //  - OperationCancelledException wird re-thrown → outer
        //    executeWithCancel-Catch → CANCELLED_EXIT_CODE (130).
        //  - IllegalArgumentException → Exit 2 (Hook-Input-Validierung).
        //  - Andere RuntimeException → Exit 3 (Preflight-Failure,
        //    z.B. PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT).
        val finalizedInput = try {
            phase2Hook.finalize(context.preparedImport.input, resumeExpectedSha256 = null)
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: IllegalArgumentException) {
            userFacingStderr("Error: ${e.message}")
            return 2
        } catch (e: RuntimeException) {
            userFacingStderr("Error: ${e.message}")
            return 3
        }
        val preparedImport = if (finalizedInput === context.preparedImport.input) {
            context.preparedImport
        } else {
            context.preparedImport.copy(input = finalizedInput)
        }
        val executionPlan = when (
            val result = executionPlanner.prepare(
                request = request,
                connectionConfig = context.connectionConfig,
                resolvedUrl = context.resolvedUrl,
                charset = context.charset,
                format = context.format,
                preparedImport = preparedImport,
            )
        ) {
            is ImportExecutionPlanResult.Ok -> result.value
            is ImportExecutionPlanResult.Exit -> return result.code
        }
        val result = when (
            val r = streamingInvoker.execute(
                context.format,
                pool,
                preparedImport,
                executionPlan,
                cancellationToken,
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
            stderr = userFacingStderr,
        )
    }

    companion object {
        /** CLI exit code for cooperative cancellation per `spec/job-contract.md`. */
        const val CANCELLED_EXIT_CODE = 130

        fun inferFormatFromExtension(path: Path): String? = DataImportHelpers.inferFormatFromExtension(path)

        fun formatProgressSummary(result: ImportResult): String = ImportCompletionSupport.formatProgressSummary(result)
    }
}
