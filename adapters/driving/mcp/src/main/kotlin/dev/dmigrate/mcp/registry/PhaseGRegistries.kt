package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.cursor.McpCursorCodec
import dev.dmigrate.mcp.prompts.DefaultPromptRegistry
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.ai.DefaultAiProviderRegistry
import dev.dmigrate.server.application.ai.AiToolOrchestrator
import dev.dmigrate.server.application.audit.AuditScope

/**
 * Phase G § 6 G.6 (G.6.g) — Tool-Registry-Overlay parallel zu
 * [PhaseERegistries].
 *
 * Plan §7.6 line 1132 sinngemäß: "Tool-Registry von Unsupported-
 * Handlern auf produktive Handler umstellen". Phase G überschreibt
 * exakt die drei KI-nahen Slots
 * (`procedure_transform_plan`, `procedure_transform_execute`,
 * `testdata_plan`); `testdata_execute` bleibt bewusst auf
 * [UnsupportedToolHandler] (Plan §3.2 Carve-out — separate
 * Daten-Schreiboperation, nicht in 0.9.6).
 *
 * Layering: nimmt eine bereits gebaute Phase-E-Registry (die
 * Phase-B/C/D/E-Tools verdrahtet) und überschreibt nur die drei
 * G-Slots. Phase-A bis F bleibt unverändert.
 *
 * Bootstrap-Vertrag:
 *
 * - **Eine** [AiToolOrchestrator]-Instanz für alle drei Handler,
 *   damit Single-Writer-Lease und Reclaim über denselben Store
 *   laufen.
 * - **Eine** [AiProviderRegistry] mit garantiertem NoOp-Default
 *   (Plan §4.1).
 * - **Ein** [PromptHygieneService], der Input + Output prüft
 *   (Plan §7.4).
 *
 * Beide Transports (stdio + HTTP) MÜSSEN dieselbe Registry-Instanz
 * teilen — Plan-§-6.1-Akzeptanz parallel zu Phase C.
 */
object PhaseGRegistries {

    fun defaultToolRegistry(
        gWiring: PhaseGWiring,
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    ): ToolRegistry = defaultToolRegistry(
        gWiring = gWiring,
        scopeMapping = scopeMapping,
        capabilitiesHandler = capabilitiesHandler(gWiring, scopeMapping),
    )

    fun defaultComponents(
        gWiring: PhaseGWiring,
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    ): PhaseCRegistries.McpServiceComponents {
        val capabilitiesHandler = capabilitiesHandler(gWiring, scopeMapping)
        return PhaseCRegistries.McpServiceComponents(
            toolRegistry = defaultToolRegistry(gWiring, scopeMapping, capabilitiesHandler),
            responseLimitEnforcer = PhaseCRegistries.defaultResponseLimitEnforcer(gWiring.phaseEWiring.phaseCWiring),
            auditScope = gWiring.phaseEWiring.phaseCWiring.auditSink?.let {
                AuditScope(it, gWiring.phaseEWiring.phaseCWiring.clock)
            },
            capabilitiesProvider = capabilitiesHandler::staticPayload,
            cursorCodec = McpCursorCodec(
                keyring = gWiring.phaseEWiring.phaseCWiring.cursorKeyring,
                clock = gWiring.phaseEWiring.phaseCWiring.clock,
            ),
        )
    }

    private fun defaultToolRegistry(
        gWiring: PhaseGWiring,
        scopeMapping: Map<String, Set<String>>,
        capabilitiesHandler: CapabilitiesListReadOnlyHandler,
    ): ToolRegistry {
        val phaseE = gWiring.phaseEWiring
        val phaseC = phaseE.phaseCWiring
        val clock = phaseC.clock

        // Plan §6 G.6: ein gemeinsamer Orchestrator über die drei
        // Handler, damit das Acquire/Replay/Commit über denselben
        // AiToolOutcomeStore läuft.
        val orchestrator = AiToolOrchestrator(
            outcomeStore = gWiring.aiToolOutcomeStore,
            leaseDuration = gWiring.aiToolLeaseDuration,
        )

        val baseRegistry = PhaseERegistries.defaultToolRegistry(phaseE, scopeMapping)
        val builder = ToolRegistry.builder()
        for (descriptor in baseRegistry.all()) {
            val handler = when (descriptor.name) {
                "capabilities_list" -> capabilitiesHandler
                ProcedureTransformPlanHandler.TOOL_NAME -> ProcedureTransformPlanHandler(
                    orchestrator = orchestrator,
                    artifactStore = phaseC.artifactStore,
                    artifactContentStore = phaseC.artifactContentStore,
                    schemaStore = phaseC.schemaStore,
                    aiArtifactMetadataStore = gWiring.aiArtifactMetadataStore,
                    providerRegistry = gWiring.aiProviderRegistry,
                    hygieneService = gWiring.promptHygieneService,
                    policyService = phaseE.policyService,
                    approvalGrantService = gWiring.approvalGrantService,
                    quotaService = phaseC.quotaService,
                    clock = clock,
                )
                ProcedureTransformExecuteHandler.TOOL_NAME -> ProcedureTransformExecuteHandler(
                    orchestrator = orchestrator,
                    artifactStore = phaseC.artifactStore,
                    artifactContentStore = phaseC.artifactContentStore,
                    aiArtifactMetadataStore = gWiring.aiArtifactMetadataStore,
                    providerRegistry = gWiring.aiProviderRegistry,
                    hygieneService = gWiring.promptHygieneService,
                    policyService = phaseE.policyService,
                    approvalGrantService = gWiring.approvalGrantService,
                    quotaService = phaseC.quotaService,
                    clock = clock,
                )
                TestdataPlanHandler.TOOL_NAME -> TestdataPlanHandler(
                    orchestrator = orchestrator,
                    artifactStore = phaseC.artifactStore,
                    artifactContentStore = phaseC.artifactContentStore,
                    schemaStore = phaseC.schemaStore,
                    profileStore = phaseC.profileStore,
                    aiArtifactMetadataStore = gWiring.aiArtifactMetadataStore,
                    providerRegistry = gWiring.aiProviderRegistry,
                    hygieneService = gWiring.promptHygieneService,
                    policyService = phaseE.policyService,
                    approvalGrantService = gWiring.approvalGrantService,
                    quotaService = phaseC.quotaService,
                    clock = clock,
                )
                // Follow-up AP 3: testdata_execute jetzt produktiv (Plan §5).
                TestdataExecuteHandler.TOOL_NAME -> TestdataExecuteHandler(
                    orchestrator = orchestrator,
                    artifactStore = phaseC.artifactStore,
                    artifactContentStore = phaseC.artifactContentStore,
                    aiArtifactMetadataStore = gWiring.aiArtifactMetadataStore,
                    providerRegistry = gWiring.aiProviderRegistry,
                    hygieneService = gWiring.promptHygieneService,
                    policyService = phaseE.policyService,
                    approvalGrantService = gWiring.approvalGrantService,
                    quotaService = phaseC.quotaService,
                    clock = clock,
                )
                else -> baseRegistry.findHandler(descriptor.name)!!
            }
            builder.register(descriptor, handler)
        }
        return builder.build()
    }

    private fun capabilitiesHandler(
        gWiring: PhaseGWiring,
        scopeMapping: Map<String, Set<String>>,
    ): CapabilitiesListReadOnlyHandler {
        val toolRegistry = PhaseERegistries.defaultToolRegistry(gWiring.phaseEWiring, scopeMapping)
        val providerDescriptions =
            (gWiring.aiProviderRegistry as? DefaultAiProviderRegistry)?.describe().orEmpty()
        val prompts = DefaultPromptRegistry.mandatory().list()
        return CapabilitiesListReadOnlyHandler(
            tools = toolRegistry.all(),
            scopeMapping = scopeMapping,
            limits = gWiring.phaseEWiring.phaseCWiring.limits,
            aiProviders = providerDescriptions,
            prompts = prompts,
        )
    }
}
