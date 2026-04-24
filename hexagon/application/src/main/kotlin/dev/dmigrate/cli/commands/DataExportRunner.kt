package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ExportOutput
import dev.dmigrate.streaming.ExportResult
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Instant

/** Parsed filter representation produced by [FilterDslParser]. */
data class ParsedFilter(
    val expr: FilterExpr,
    val canonical: String,
)

data class DataExportRequest(
    val source: String,
    val format: String,
    val output: Path?,
    val tables: List<String>?,
    val filter: ParsedFilter?,
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
    private val userFacingErrors = UserFacingErrors()
    private val userFacingStderr = userFacingErrors.stderrSink(stderr)

    private val resumeCoordinator = ExportResumeCoordinator(primaryKeyLookup, userFacingStderr)

    private val preflight = ExportPreflightValidator(readerLookup, listerLookup, writerFactoryBuilder, userFacingStderr)

    private val checkpointManager = ExportCheckpointManager(
        checkpointStoreFactory, checkpointConfigResolver, resumeCoordinator, clock, progressReporter, userFacingStderr,
    )

    fun execute(request: DataExportRequest): Int {
        validateRequest(request)?.let { return it }
        val connectionConfig = resolveConnection(request) ?: return 7
        val charset = resolveCharset(request) ?: return 2
        val pool = connect(connectionConfig) ?: return 4

        return try {
            executeWithPool(request, connectionConfig, charset, pool)
        } finally {
            runCatching { pool.close() }
        }
    }

    private fun validateRequest(request: DataExportRequest): Int? {
        val hasSinceColumn = !request.sinceColumn.isNullOrBlank()
        val hasSinceValue = !request.since.isNullOrBlank()
        if (hasSinceColumn != hasSinceValue) {
            userFacingStderr("Error: --since-column and --since must be used together.")
            return 2
        }
        if (hasSinceColumn) {
            val invalidSinceColumn = DataExportHelpers.firstInvalidQualifiedIdentifier(request.sinceColumn!!)
            if (invalidSinceColumn != null) {
                userFacingStderr(
                    "Error: --since-column value '$invalidSinceColumn' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
        }
        if (!request.resume.isNullOrBlank() && request.output == null) {
            userFacingStderr(
                "Error: --resume is not supported for stdout export; " +
                    "set --output <file-or-dir> or drop --resume."
            )
            return 2
        }
        return null
    }

    private fun resolveConnection(request: DataExportRequest): ConnectionConfig? {
        val resolvedUrl = try {
            sourceResolver(request.source, request.cliConfigPath)
        } catch (e: Exception) {
            userFacingStderr("Error: ${e.message}")
            return null
        }

        return try {
            urlParser(resolvedUrl)
        } catch (e: IllegalArgumentException) {
            userFacingStderr("Error: ${e.message}")
            null
        }
    }

    private fun resolveCharset(request: DataExportRequest): Charset? {
        return try {
            Charset.forName(request.encoding)
        } catch (e: Exception) {
            userFacingStderr("Error: Unknown encoding '${request.encoding}': ${e.message}")
            null
        }
    }

    private fun connect(connectionConfig: ConnectionConfig): ConnectionPool? {
        return try {
            poolFactory(connectionConfig)
        } catch (e: Throwable) {
            userFacingStderr("Error: Failed to connect to database: ${e.message}")
            null
        }
    }

    private fun executeWithPool(
        request: DataExportRequest,
        connectionConfig: ConnectionConfig,
        charset: Charset,
        pool: ConnectionPool,
    ): Int {
        val infra = preflight.resolveInfrastructure(connectionConfig) ?: return 7
        val effectiveTables = when (val t = preflight.resolveTables(request, infra.lister, pool)) {
            is TablesResult.Ok -> t.tables
            is TablesResult.Exit -> return t.code
        }
        val output = preflight.resolveOutput(request, effectiveTables) ?: return 2
        val ctx = when (val p = preflight.buildExportContext(ExportPreflightValidator.ExportContextInput(
            request, connectionConfig, charset, pool, effectiveTables, output, infra,
            resumeCoordinator::resolvePrimaryKeys,
        ))) {
            is PreparedResult.Ok -> p.value
            is PreparedResult.Exit -> return p.code
        }
        val checkpoint = checkpointManager.resolveCheckpointContext(request) ?: return 7
        val resumeCtx = when (val r = checkpointManager.resolveResumeContext(request, ctx, output, checkpoint, effectiveTables)) {
            is ExportResumeResult.Ok -> r.value
            is ExportResumeResult.Exit -> return r.code
        }
        val markers = checkpointManager.resolveMarkers(request, resumeCtx, ctx.primaryKeysByTable, effectiveTables) ?: return 3
        val initExit = checkpointManager.writeInitialManifest(request, resumeCtx, checkpoint.store, ctx.fingerprint, effectiveTables)
        if (initExit != null) return initExit
        val callbacks = checkpointManager.buildCallbacks(request, resumeCtx, checkpoint.store, ctx.fingerprint, effectiveTables, markers)
        val staging = checkpointManager.setupStaging(output, checkpoint, resumeCtx.operationId)
        val result = executeStreaming(StreamingParams(request, pool, ctx, output, staging, resumeCtx, markers, callbacks))
            ?: return 5
        return finalizeAndReport(request, result, staging, checkpoint.store, resumeCtx.operationId)
    }

    private data class StreamingParams(
        val request: DataExportRequest, val pool: ConnectionPool, val ctx: ExportPreparedContext,
        val output: ExportOutput, val staging: StagingRedirect?, val resume: ExportResumeContext,
        val markers: Map<String, ResumeMarker>, val callbacks: ExportCallbacks,
    )

    private fun executeStreaming(params: StreamingParams): ExportResult? {
        val request = params.request; val pool = params.pool; val ctx = params.ctx
        val output = params.output; val staging = params.staging; val resume = params.resume
        val markers = params.markers; val callbacks = params.callbacks
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
            userFacingStderr("Error: Export failed: ${e.message}"); null
        }
    }

    private fun finalizeAndReport(
        request: DataExportRequest, result: ExportResult, staging: StagingRedirect?,
        store: CheckpointStore?, operationId: String,
    ): Int {
        val failed = result.tables.firstOrNull { it.error != null }
        if (failed != null) {
            userFacingStderr("Error: Failed to export table '${failed.table}': ${failed.error}")
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
                userFacingStderr(
                    "Error: Failed to move staging file to target '${staging.target}': ${e.message ?: e::class.simpleName}"
                )
                return 5
            }
        }
        if (store != null) {
            try { store.complete(operationId) } catch (e: CheckpointStoreException) {
                userFacingStderr("Warning: Failed to remove completed checkpoint: ${e.message}")
            }
        }
        if (!request.quiet) { for (line in collectWarnings()) { userFacingStderr(line) } }
        if (!request.quiet && !request.noProgress) {
            userFacingStderr(DataExportHelpers.formatProgressSummary(result))
            result.operationId?.let { userFacingStderr("Run operation id: $it") }
        }
        return 0
    }

}
