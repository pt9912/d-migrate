package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.ai.AiProviderRegistry
import dev.dmigrate.server.application.ai.DefaultAiProviderRegistry
import dev.dmigrate.server.application.approval.ApprovalGrantService
import dev.dmigrate.server.application.audit.prompt.DefaultPromptHygieneService
import dev.dmigrate.server.application.audit.prompt.PromptHygieneService
import dev.dmigrate.server.ports.AiArtifactMetadataStore
import dev.dmigrate.server.ports.AiToolOutcomeStore
import java.time.Duration

/**
 * Phase G § 6 G.6 (G.6.g) — Wiring-Bundle für die KI-nahen
 * MCP-Tools.
 *
 * Baut auf [PhaseEWiring] auf (das wiederum [PhaseCWiring] hält):
 * Tool-Handler greifen auf `phaseEWiring.phaseCWiring.artifactStore`,
 * `.artifactContentStore`, `.schemaStore`, `.profileStore`,
 * `.connectionStore`, `.clock` zurück. Phase-G ergänzt nur die
 * KI-spezifischen Stores und Services.
 *
 * Strikte Default-Vorgaben (Plan §3.2 + §4.1): ohne explizite
 * Konfiguration fährt das Wiring auf NoOp-Provider, In-Process-
 * Outcome-/Metadata-Stores und den pattern-basierten
 * [DefaultPromptHygieneService]. Externe Provider (Plan §6 G.3)
 * werden erst durch eine angereicherte [aiProviderRegistry]
 * aktiviert.
 *
 * @param phaseEWiring Phase-E + Phase-C Wiring (Job-Start-Tools,
 *   Cancel, Upload-Init, Artifact-Stores, Connection-Refs).
 * @param aiToolOutcomeStore Single-Writer-Outcome-Store mit
 *   Lease/Reclaim (G.6.a). Default = [InProcessAiToolOutcomeStore].
 * @param aiArtifactMetadataStore Provenance-Store für KI-Artefakte
 *   (G.6.b). Default = [InProcessAiArtifactMetadataStore].
 * @param aiProviderRegistry Provider-Registry mit
 *   fail-closed-Konfiguration (G.6.a/G.3). Default =
 *   [DefaultAiProviderRegistry.noOpOnly] — kein Netzwerk, kein
 *   Secret-Read, deterministischer Output.
 * @param promptHygieneService Pattern-basierter Hygiene-Service
 *   (G.4). Default = [DefaultPromptHygieneService].
 * @param aiToolLeaseDuration Lease-TTL für den
 *   [AiToolOutcomeStore]-Acquire. 60s passt zum durchschnittlichen
 *   Provider-Aufruf inkl. Hygiene + Publish; Tests können einen
 *   kurzen Wert injizieren, um Reclaim-Pfade zu pinnen.
 */
data class PhaseGWiring(
    val phaseEWiring: PhaseEWiring,
    val aiToolOutcomeStore: AiToolOutcomeStore = InProcessAiToolOutcomeStore(),
    val aiArtifactMetadataStore: AiArtifactMetadataStore = InProcessAiArtifactMetadataStore(),
    val aiProviderRegistry: AiProviderRegistry = DefaultAiProviderRegistry.noOpOnly(),
    val promptHygieneService: PromptHygieneService = DefaultPromptHygieneService(),
    val approvalGrantService: ApprovalGrantService = phaseEWiring.approvalGrantService,
    val aiToolLeaseDuration: Duration = Duration.ofSeconds(60),
)
