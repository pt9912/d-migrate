package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.job.JobStartOrchestrator

/**
 * LF-012 / LN-011 / LN-017 / LN-027 Tool-Registry-Overlay parallel zu [McpRuntimeRegistries].
 *
 * Umstellung gemaess LF-012 / LN-011 / LN-017 / LN-027 line 1132 ("Tool-Registry von
 * Unsupported-Handlern auf produktive Handler umstellen") fuer die
 * drei LF-012 / LN-011 / LN-017 / LN-027 Start-Tools — `job_cancel` und Runner-Integration
 * folgen in LF-012 / LN-011 / LN-017 / LN-027.
 *
 * Layering: nimmt eine bereits gebaute LF-012 / LN-038-Registry und
 * ueberschreibt nur die drei E-Slots. LF-012 / LN-038/C-Tools bleiben
 * unveraendert.
 */
object OperationalMcpRegistries {

    fun defaultToolRegistry(
        eWiring: OperationalMcpWiring,
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    ): ToolRegistry {
        val orchestrator = JobStartOrchestrator(
            idempotencyStore = eWiring.idempotencyStore,
            jobStartTransaction = eWiring.jobStartTransaction,
            workerHandleRegistry = eWiring.workerHandleRegistry,
            approvedRetryService = eWiring.approvedRetryService,
            policyService = eWiring.policyService,
            payloadFingerprintService = eWiring.payloadFingerprintService,
            jobIdFactory = eWiring.jobIdFactory,
            cancellationSourceFactory = eWiring.cancellationSourceFactory,
            quotaService = eWiring.ownerAwareQuotaService,
            jobDispatcher = eWiring.jobDispatcher,
            jobWorkerFactory = eWiring.jobWorkerFactory,
            // LF-012 / LN-011 / LN-017 / LN-027: admission ans Pre-commit-Gate;
            // jobStore an markExecutorSetupFailed (post-commit Setup-
            // Failure -> pollbares FAILED).
            dispatchAdmission = eWiring.executorBundle.admission,
            jobStore = eWiring.runtimeWiring.jobStore,
        )
        val clock = eWiring.runtimeWiring.clock

        // LF-012 / LN-038-Basis nimmt die LF-012 / LN-038/C-Handler. Wir ueberschreiben nur
        // die drei E-Slots mit produktiven Handlern; alles andere bleibt
        // wie von McpRuntimeRegistries gewired.
        val baseRegistry = McpRuntimeRegistries.defaultToolRegistry(
            wiring = eWiring.runtimeWiring,
            scopeMapping = scopeMapping,
        )
        val cancelHandler = JobCancelHandler(eWiring.jobCancelService, clock)
        val builder = ToolRegistry.builder()
        for (descriptor in baseRegistry.all()) {
            val handler = when (descriptor.name) {
                ArtifactUploadInitHandler.TOOL_NAME ->
                    ArtifactUploadInitHandler(
                        sessionStore = eWiring.runtimeWiring.uploadSessionStore,
                        quotaService = eWiring.runtimeWiring.quotaService,
                        limits = eWiring.runtimeWiring.limits,
                        options = ArtifactUploadInitHandler.Options(clock = clock),
                        uploadInitOrchestrator = eWiring.uploadInitOrchestrator,
                    )
                ArtifactUploadAbortHandler.TOOL_NAME ->
                    ArtifactUploadAbortHandler(
                        sessionStore = eWiring.runtimeWiring.uploadSessionStore,
                        segmentStore = eWiring.runtimeWiring.uploadSegmentStore,
                        quotaService = eWiring.runtimeWiring.quotaService,
                        clock = clock,
                        administrativeAbortPipeline = AdministrativeAbortPipeline(
                            sessionStore = eWiring.runtimeWiring.uploadSessionStore,
                            segmentStore = eWiring.runtimeWiring.uploadSegmentStore,
                            quotaService = eWiring.runtimeWiring.quotaService,
                            syncEffectStore = eWiring.syncEffectStore,
                            abortOutcomeStore = eWiring.abortOutcomeStore,
                            abortApprovalFingerprint = eWiring.abortApprovalFingerprint,
                            policyService = eWiring.policyService,
                            clock = clock,
                        ),
                    )
                SchemaReverseStartHandler.TOOL_NAME ->
                    SchemaReverseStartHandler(orchestrator, clock)
                DataProfileStartHandler.TOOL_NAME ->
                    DataProfileStartHandler(orchestrator, clock)
                SchemaCompareStartHandler.TOOL_NAME ->
                    SchemaCompareStartHandler(orchestrator, clock)
                // LF-010 / LF-013 / LN-009 / LN-011: produktiver
                // `data_import_start`-Handler. Reaktiviert den
                // bisherigen UnsupportedToolHandler-Slot mit
                // ArtifactStore-/ConnectionReferenceStore-/
                // SchemaStore-Resolution + LF-012 / LN-011 / LN-017 / LN-027-Job-Pipeline.
                DataImportStartHandler.TOOL_NAME ->
                    DataImportStartHandler(
                        orchestrator = orchestrator,
                        artifactStore = eWiring.runtimeWiring.artifactStore,
                        artifactContentStore = eWiring.runtimeWiring.artifactContentStore,
                        connectionStore = eWiring.runtimeWiring.connectionStore,
                        schemaStore = eWiring.runtimeWiring.schemaStore,
                        clock = clock,
                    )
                // LF-010 / LF-013 / LN-009 / LN-011: produktiver
                // `data_transfer_start`-Handler. Tenant-scoped
                // Lookup fuer Source + Target ConnectionRef ueber
                // den geteilten ConnectionReferenceStore.
                DataTransferStartHandler.TOOL_NAME ->
                    DataTransferStartHandler(
                        orchestrator = orchestrator,
                        connectionStore = eWiring.runtimeWiring.connectionStore,
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
