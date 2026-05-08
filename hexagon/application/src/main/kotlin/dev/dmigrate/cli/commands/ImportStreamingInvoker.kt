package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.format.data.DataExportFormat

/**
 * Executes the streaming import hand-off once all preflight and checkpoint
 * state has been prepared.
 */
internal class ImportStreamingInvoker(
    private val importExecutor: ImportExecutor,
    private val stderr: (String) -> Unit,
) {

    fun execute(
        format: DataExportFormat,
        pool: ConnectionPool,
        preparedImport: SchemaPreflightResult,
        executionPlan: ImportExecutionPlan,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): StreamingResult {
        val options = executionPlan.options
        val resumeContext = executionPlan.resumeContext
        val callbacks = executionPlan.callbacks

        return try {
            val rawResult = importExecutor.execute(
                context = ImportExecutionContext(
                    pool = pool,
                    input = preparedImport.input,
                    cancellationToken = cancellationToken,
                ),
                options = ImportExecutionOptions(
                    format = format,
                    options = options.importOptions,
                    readOptions = options.formatReadOptions,
                    config = options.pipelineConfig,
                ),
                resume = ImportResumeState(
                    operationId = resumeContext.operationId,
                    resuming = resumeContext.resuming,
                    skippedTables = resumeContext.skippedTables,
                    resumeStateByTable = resumeContext.resumeStateByTable,
                ),
                callbacks = ImportCallbacks(
                    progressReporter = callbacks.progressReporter,
                    onTableOpened = options.onTableOpened,
                    onChunkCommitted = callbacks.onChunkCommitted,
                    onTableCompleted = callbacks.onTableCompleted,
                ),
            )
            StreamingResult.Ok(rawResult.copy(operationId = resumeContext.operationId))
        } catch (e: OperationCancelledException) {
            // Plan §4.5: Cancel must travel through this catch-all boundary
            // unmodified so the runner can map it to exit 130 instead of the
            // generic 5 (import failure) path.
            throw e
        } catch (e: UnsupportedTriggerModeException) {
            stderr("Error: ${e.message}")
            StreamingResult.Exit(2)
        } catch (e: ImportSchemaMismatchException) {
            stderr("Error: ${e.message}")
            StreamingResult.Exit(3)
        } catch (e: Throwable) {
            stderr("Error: Import failed: ${e.message}")
            StreamingResult.Exit(5)
        }
    }
}
