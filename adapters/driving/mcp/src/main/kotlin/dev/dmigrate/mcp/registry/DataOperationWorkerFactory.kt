package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.job.JobWorkerFactory
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome

/**
 * Phase-F Default-Factory fuer Datenoperationen.
 *
 * `data_import` und `data_transfer` duerfen im produktiven Default
 * nicht ueber den generischen Passthrough-Worker als erfolgreich
 * erscheinen: beide Operationen brauchen einen echten Runner, der
 * Connection-Secrets materialisiert und den JDBC-/Streaming-Pfad
 * ausfuehrt. Solange ein Bootstrap keinen solchen Worker injiziert,
 * failen diese Jobs geschlossen und sichtbar.
 */
internal class PhaseFDataOperationWorkerFactory(
    private val fallback: JobWorkerFactory,
    private val dataImportWorkerFactory: JobWorkerFactory? = null,
    private val dataTransferWorkerFactory: JobWorkerFactory? = null,
    private val dataRunnerDependencies: PhaseFDataRunnerDependencies? = null,
) : JobWorkerFactory {

    override fun create(record: JobRecord, request: JobStartRequest): JobWorker? =
        when (record.managedJob.operation) {
            DataImportStartHandler.OPERATION ->
                dataImportWorkerFactory?.create(record, request)
                    ?: dataRunnerDependencies?.let {
                        McpDataImportJobWorker(request.payload, request.principalContext, it)
                    }
                    ?: missingRunnerWorker(record.managedJob.operation)
            DataTransferStartHandler.OPERATION ->
                dataTransferWorkerFactory?.create(record, request)
                    ?: dataRunnerDependencies?.let {
                        McpDataTransferJobWorker(request.payload, request.principalContext, it)
                    }
                    ?: missingRunnerWorker(record.managedJob.operation)
            else -> fallback.create(record, request)
        }

    private fun missingRunnerWorker(operation: String): JobWorker =
        JobWorker { _, _ ->
            JobWorkerOutcome.Failed(
                errorCode = ERROR_CODE_DATA_RUNNER_NOT_CONFIGURED,
                errorMessage = "No MCP data runner worker configured for $operation",
            )
        }

    companion object {
        const val ERROR_CODE_DATA_RUNNER_NOT_CONFIGURED: String = "MCP_DATA_RUNNER_NOT_CONFIGURED"
    }
}
