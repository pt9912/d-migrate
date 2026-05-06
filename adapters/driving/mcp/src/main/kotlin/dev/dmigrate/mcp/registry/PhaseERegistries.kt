package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.job.JobStartOrchestrator

/**
 * Phase-E Tool-Registry-Overlay parallel zu [PhaseCRegistries].
 *
 * Umstellung gemaess Plan §7.6 line 1132 ("Tool-Registry von
 * Unsupported-Handlern auf produktive Handler umstellen") fuer die
 * drei Phase-E Start-Tools — `job_cancel` und Runner-Integration
 * folgen in AP E.7 / E.8.
 *
 * Layering: nimmt eine bereits gebaute Phase-C-Registry und
 * ueberschreibt nur die drei E-Slots. Phase-B/C-Tools bleiben
 * unveraendert.
 */
object PhaseERegistries {

    fun defaultToolRegistry(
        eWiring: PhaseEWiring,
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    ): ToolRegistry {
        val orchestrator = JobStartOrchestrator(
            idempotencyStore = eWiring.idempotencyStore,
            jobStartTransaction = eWiring.jobStartTransaction,
            workerHandleRegistry = eWiring.workerHandleRegistry,
            approvalGrantStore = eWiring.approvalGrantStore,
            approvedRetryService = eWiring.approvedRetryService,
            policyService = eWiring.policyService,
            payloadFingerprintService = eWiring.payloadFingerprintService,
            jobIdFactory = eWiring.jobIdFactory,
            cancellationSourceFactory = eWiring.cancellationSourceFactory,
            quotaService = eWiring.ownerAwareQuotaService,
            jobDispatcher = eWiring.jobDispatcher,
            jobWorkerFactory = eWiring.jobWorkerFactory,
            // Phase E3 § 3.5 + § 6.5 (E3.5): admission ans Pre-commit-Gate;
            // jobStore an markExecutorSetupFailed (post-commit Setup-
            // Failure -> pollbares FAILED).
            dispatchAdmission = eWiring.executorBundle.admission,
            jobStore = eWiring.phaseCWiring.jobStore,
        )
        val clock = eWiring.phaseCWiring.clock

        // Phase-C-Basis nimmt die Phase-B/C-Handler. Wir ueberschreiben nur
        // die drei E-Slots mit produktiven Handlern; alles andere bleibt
        // wie von PhaseCRegistries gewired.
        val baseRegistry = PhaseCRegistries.defaultToolRegistry(
            wiring = eWiring.phaseCWiring,
            scopeMapping = scopeMapping,
        )
        val cancelHandler = JobCancelHandler(eWiring.jobCancelService, clock)
        val builder = ToolRegistry.builder()
        for (descriptor in baseRegistry.all()) {
            val handler = when (descriptor.name) {
                SchemaReverseStartHandler.TOOL_NAME ->
                    SchemaReverseStartHandler(orchestrator, clock)
                DataProfileStartHandler.TOOL_NAME ->
                    DataProfileStartHandler(orchestrator, clock)
                SchemaCompareStartHandler.TOOL_NAME ->
                    SchemaCompareStartHandler(orchestrator, clock)
                // Phase F § 8.7 (F.7 5/5): produktiver
                // `data_import_start`-Handler. Reaktiviert den
                // bisherigen UnsupportedToolHandler-Slot mit
                // ArtifactStore-/ConnectionReferenceStore-/
                // SchemaStore-Resolution + Phase-E-Job-Pipeline.
                DataImportStartHandler.TOOL_NAME ->
                    DataImportStartHandler(
                        orchestrator = orchestrator,
                        artifactStore = eWiring.phaseCWiring.artifactStore,
                        connectionStore = eWiring.phaseCWiring.connectionStore,
                        schemaStore = eWiring.phaseCWiring.schemaStore,
                        clock = clock,
                    )
                // Phase F § 8.8 (F.8 4/4): produktiver
                // `data_transfer_start`-Handler. Tenant-scoped
                // Lookup fuer Source + Target ConnectionRef ueber
                // den geteilten ConnectionReferenceStore.
                DataTransferStartHandler.TOOL_NAME ->
                    DataTransferStartHandler(
                        orchestrator = orchestrator,
                        connectionStore = eWiring.phaseCWiring.connectionStore,
                        clock = clock,
                    )
                JobCancelHandler.TOOL_NAME ->
                    cancelHandler
                else ->
                    baseRegistry.findHandler(descriptor.name)!!
            }
            builder.register(descriptor, handler)
        }
        return builder.build()
    }
}
