package dev.dmigrate.server.application.ai

import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail

/**
 * LF-017 / LF-024 / LN-030 / LN-031— Ergebnis von
 * [AiToolOrchestrator.dispatch].
 *
 * Tool-Handler (LF-017 / LF-024 / LN-030 / LN-031/f) übersetzen das Outcome in den
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
        val promptFingerprint: String? = null,
        val payloadFingerprint: String? = null,
        val modelVersion: String? = null,
        val replayed: Boolean,
    ) : AiToolDispatchOutcome

    data class WireFailure(
        val toolErrorCode: ToolErrorCode,
        val scrubbedMessage: String,
        val replayed: Boolean,
        val retryable: Boolean,
        val details: List<ToolErrorDetail> = emptyList(),
    ) : AiToolDispatchOutcome
}
