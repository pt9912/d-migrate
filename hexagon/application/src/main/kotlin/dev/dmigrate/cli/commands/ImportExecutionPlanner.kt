package dev.dmigrate.cli.commands

import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.format.data.DataExportFormat
import java.nio.charset.Charset

/**
 * Prepares all collaborator-driven state required before the streaming import
 * can start. This keeps [DataImportRunner] focused on orchestration and the
 * final execution hand-off.
 */
internal class ImportExecutionPlanner(
    private val preflightValidator: ImportPreflightValidator,
    private val checkpointManager: ImportCheckpointManager,
) {

    fun prepare(
        request: DataImportRequest,
        connectionConfig: ConnectionConfig,
        resolvedUrl: String,
        charset: Charset?,
        format: DataExportFormat,
        preparedImport: SchemaPreflightResult,
    ): ImportExecutionPlanResult {
        preflightValidator.resolveWriter(connectionConfig) ?: return ImportExecutionPlanResult.Exit(7)

        val options = preflightValidator.buildImportOptions(request, charset, preparedImport)
        val inputContext = when (
            val result = preflightValidator.resolveInputContext(
                request,
                connectionConfig,
                resolvedUrl,
                format,
                preparedImport,
            )
        ) {
            is InputContextResult.Ok -> result.value
            is InputContextResult.Exit -> return ImportExecutionPlanResult.Exit(result.code)
        }

        val checkpoint = checkpointManager.resolveCheckpointContext(request)
            ?: return ImportExecutionPlanResult.Exit(7)
        val resumeContext = when (
            val result = checkpointManager.resolveResumeContext(request, checkpoint, inputContext)
        ) {
            is ImportResumeResult.Ok -> result.value
            is ImportResumeResult.Exit -> return ImportExecutionPlanResult.Exit(result.code)
        }

        val manifestExit = checkpointManager.writeInitialManifest(
            request = request,
            format = format,
            resumeCtx = resumeContext,
            store = checkpoint.store,
            inputCtx = inputContext,
        )
        if (manifestExit != null) return ImportExecutionPlanResult.Exit(manifestExit)

        val callbacks = checkpointManager.buildCallbacks(
            request = request,
            format = format,
            resumeCtx = resumeContext,
            store = checkpoint.store,
            inputCtx = inputContext,
        )

        return ImportExecutionPlanResult.Ok(
            ImportExecutionPlan(
                options = options,
                checkpointStore = checkpoint.store,
                resumeContext = resumeContext,
                callbacks = callbacks,
            )
        )
    }
}
