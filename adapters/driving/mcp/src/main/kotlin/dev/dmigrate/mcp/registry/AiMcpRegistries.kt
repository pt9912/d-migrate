package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.cursor.McpCursorCodec
import dev.dmigrate.mcp.prompts.DefaultPromptRegistry
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.ai.DefaultAiProviderRegistry
import dev.dmigrate.server.application.ai.AiToolOrchestrator
import dev.dmigrate.server.application.audit.AuditScope

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — Tool-Registry-Overlay parallel zu
 * [OperationalMcpRegistries].
 *
 * LF-012 / LN-011 / LN-017 / LN-027 line 1132 sinngemäß: "Tool-Registry von Unsupported-
 * Handlern auf produktive Handler umstellen". LF-017 / LF-024 / LN-030 / LN-031 überschreibt
 * alle vier KI-nahen Slots (`procedure_transform_plan`,
 * `procedure_transform_execute`, `testdata_plan`, `testdata_execute`) —
 * `testdata_execute` ist real verdrahtet, nicht mehr auf
 * [UnsupportedToolHandler] (Doku-Drift-Fund,
 * ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md
 * Kontext-Befund 3: dieser Kommentar behauptete das Gegenteil, während
 * die Verdrahtung unten testdata_execute längst produktiv schaltet).
 *
 * Layering: nimmt eine bereits gebaute LF-012 / LN-011 / LN-017 / LN-027-Registry (die
 * LF-012 / LN-038/C/D/E-Tools verdrahtet) und überschreibt nur die drei
 * G-Slots. base bis F bleibt unverändert.
 *
 * Bootstrap-Vertrag:
 *
 * - **Eine** [AiToolOrchestrator]-Instanz für alle drei Handler,
 *   damit Single-Writer-Lease und Reclaim über denselben Store
 *   laufen.
 * - **Eine** [AiProviderRegistry] mit garantiertem NoOp-Default
 *   (LF-017 / LF-024 / LN-030 / LN-031).
 * - **Ein** [PromptHygieneService], der Input + Output prüft
 *   (LF-017 / LF-024 / LN-030 / LN-031).
 *
 * Beide Transports (stdio + HTTP) MÜSSEN dieselbe Registry-Instanz
 * teilen — LF-017 / LF-024 / LN-030 / LN-031-Akzeptanz parallel zu LF-012 / LN-038.
 */
object AiMcpRegistries {

    fun defaultToolRegistry(
        gWiring: AiMcpWiring,
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    ): ToolRegistry = defaultToolRegistry(
        gWiring = gWiring,
        scopeMapping = scopeMapping,
        capabilitiesHandler = capabilitiesHandler(gWiring, scopeMapping),
    )

    fun defaultComponents(
        gWiring: AiMcpWiring,
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    ): McpRuntimeRegistries.McpServiceComponents {
        val capabilitiesHandler = capabilitiesHandler(gWiring, scopeMapping)
        return McpRuntimeRegistries.McpServiceComponents(
            toolRegistry = defaultToolRegistry(gWiring, scopeMapping, capabilitiesHandler),
            responseLimitEnforcer = McpRuntimeRegistries.defaultResponseLimitEnforcer(gWiring.operationalWiring.runtimeWiring),
            auditScope = gWiring.operationalWiring.runtimeWiring.auditSink?.let {
                AuditScope(it, gWiring.operationalWiring.runtimeWiring.clock)
            },
            capabilitiesProvider = capabilitiesHandler::staticPayload,
            cursorCodec = McpCursorCodec(
                keyring = gWiring.operationalWiring.runtimeWiring.cursorKeyring,
                clock = gWiring.operationalWiring.runtimeWiring.clock,
            ),
        )
    }

    private fun defaultToolRegistry(
        gWiring: AiMcpWiring,
        scopeMapping: Map<String, Set<String>>,
        capabilitiesHandler: CapabilitiesListReadOnlyHandler,
    ): ToolRegistry {
        val phaseE = gWiring.operationalWiring
        val phaseC = phaseE.runtimeWiring
        val clock = phaseC.clock

        // LF-017 / LF-024 / LN-030 / LN-031: ein gemeinsamer Orchestrator über die drei
        // Handler, damit das Acquire/Replay/Commit über denselben
        // AiToolOutcomeStore läuft.
        val orchestrator = AiToolOrchestrator(
            outcomeStore = gWiring.aiToolOutcomeStore,
            leaseDuration = gWiring.aiToolLeaseDuration,
        )

        val baseRegistry = OperationalMcpRegistries.defaultToolRegistry(phaseE, scopeMapping)
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
                // LF-017 / LF-024 / LN-030 / LN-031: testdata_execute jetzt produktiv.
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
        gWiring: AiMcpWiring,
        scopeMapping: Map<String, Set<String>>,
    ): CapabilitiesListReadOnlyHandler {
        val toolRegistry = OperationalMcpRegistries.defaultToolRegistry(gWiring.operationalWiring, scopeMapping)
        val providerDescriptions =
            (gWiring.aiProviderRegistry as? DefaultAiProviderRegistry)?.describe().orEmpty()
        val prompts = DefaultPromptRegistry.mandatory().list()
        return CapabilitiesListReadOnlyHandler(
            tools = toolRegistry.all(),
            scopeMapping = scopeMapping,
            limits = gWiring.operationalWiring.runtimeWiring.limits,
            aiProviders = providerDescriptions,
            prompts = prompts,
        )
    }
}
