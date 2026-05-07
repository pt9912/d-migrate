package dev.dmigrate.server.application.ai

import dev.dmigrate.server.core.error.ToolErrorCode

/**
 * Phase G § 6 G.6 (G.6.c) — Ergebnis von
 * [AiToolOrchestrator.dispatch].
 *
 * Tool-Handler (G.6.d/e/f) übersetzen das Outcome in den
 * tool-spezifischen Wire-Envelope:
 *
 * - [WireSuccess] → `summary` + `findings` + `resultRef`-Slot
 *   (planRef / targetArtifactId / testdataPlanArtifactId, je
 *   nach Tool) + `providerMeta` + `executionMeta`.
 * - [WireFailure] → strukturierter Fehler-Envelope mit
 *   `error.code = toolErrorCode`.
 *
 * Der Orchestrator selbst kennt die tool-spezifische Wire-Form
 * nicht — `WireSuccess` trägt die generischen Felder, der Handler
 * macht das Mapping.
 */
sealed interface AiToolDispatchOutcome {

    data class WireSuccess(
        val resultRef: String,
        val outputFingerprint: String,
        val providerName: String,
        val model: String,
        val providerRequestId: String?,
        val replayed: Boolean,
    ) : AiToolDispatchOutcome

    data class WireFailure(
        val toolErrorCode: ToolErrorCode,
        val scrubbedMessage: String,
        val replayed: Boolean,
        val retryable: Boolean,
    ) : AiToolDispatchOutcome
}
