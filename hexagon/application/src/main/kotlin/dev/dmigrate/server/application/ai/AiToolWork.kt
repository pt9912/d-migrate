package dev.dmigrate.server.application.ai

import dev.dmigrate.server.core.ai.AiToolAcquireOutcome
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail

/**
 * Phase G § 6 G.6 (G.6.c) — Tool-spezifischer Pipeline-Schritt,
 * den der [AiToolOrchestrator] **nach** dem
 * Single-Writer-Acquire ausführt.
 *
 * Innerhalb von [perform] führt der Tool-Handler die Plan-
 * §-6-G.6-Pipeline-Schritte aus, die NICHT zur Idempotenz-
 * Schicht gehören:
 *
 * 1. Scope-Check `dmigrate:ai:execute` (sollte schon im
 *    Wire-Dispatch passiert sein, aber belt-and-braces).
 * 2. Semantische Resource-/Artifact-/Connection-Resolution
 *    (Plan §6 G.6 Z. 1014: "erst nach Scope, Idempotency-/
 *    Outcome-Replay und Policy").
 * 3. Policy-Decision.
 * 4. Quota-Reservation.
 * 5. [dev.dmigrate.server.application.audit.prompt.PromptHygieneService.sanitize].
 * 6. [AiProviderRegistry.resolve] + [AiProviderPort.invoke].
 * 7. Output-Hygiene über das Provider-Resultat (Plan §7.4).
 * 8. Artefakt-Publish (`ArtifactStore.save` +
 *    `ArtifactContentStore.write` +
 *    [dev.dmigrate.server.ports.AiArtifactMetadataStore.save]).
 *
 * Returns ein [AiToolWorkResult], das der Orchestrator in das
 * passende [dev.dmigrate.server.core.ai.AiToolOutcome] umwandelt
 * und committet.
 *
 * **Throws sind auch erlaubt**: jede unbehandelte Exception
 * wird vom Orchestrator als
 * [AiToolWorkResult.FailedTerminal] mit
 * [ToolErrorCode.INTERNAL_AGENT_ERROR] und scrub-sicherer
 * Default-Message gemappt, damit ein Bug in einem Tool-Handler
 * nicht zu einem unbegrenzt offenen Pending-Claim führt.
 */
fun interface AiToolWork {

    fun perform(claim: AiToolAcquireOutcome.Acquired): AiToolWorkResult
}

/**
 * Phase G § 6 G.6 (G.6.c) — Ergebnis der tool-spezifischen
 * Pipeline. Wird vom Orchestrator in einen
 * [dev.dmigrate.server.core.ai.AiToolOutcome] verpackt und
 * committet.
 *
 * - [Succeeded] → `AiToolOutcome.Succeeded`. Caller bekommt im
 *   Wire-Envelope `resultRef`, `outputFingerprint` und
 *   `providerMeta`.
 * - [FailedTerminal] → `AiToolOutcome.FailedTerminal`. Replay
 *   liefert deterministisch denselben Fehler.
 * - [FailedRetryable] → `AiToolOutcome.FailedRetryable`. Replay
 *   erlaubt einen neuen Versuch (Counter wird inkrementiert).
 */
sealed interface AiToolWorkResult {

    data class Succeeded(
        val resultRef: String,
        val outputFingerprint: String,
        val providerName: String,
        val model: String,
        val providerRequestId: String?,
        val promptFingerprint: String? = null,
        val payloadFingerprint: String? = null,
        val modelVersion: String? = null,
    ) : AiToolWorkResult {
        init {
            require(resultRef.isNotBlank()) { "resultRef must not be blank" }
            require(outputFingerprint.length == FP_LEN) {
                "outputFingerprint must be a $FP_LEN-char hex SHA-256"
            }
            require(providerName.isNotBlank()) { "providerName must not be blank" }
            require(model.isNotBlank()) { "model must not be blank" }
            require(providerRequestId?.isNotBlank() != false) {
                "providerRequestId must be non-blank or null"
            }
            require(promptFingerprint == null || promptFingerprint.length == FP_LEN) {
                "promptFingerprint must be null or a $FP_LEN-char hex SHA-256"
            }
            require(payloadFingerprint == null || payloadFingerprint.length == FP_LEN) {
                "payloadFingerprint must be null or a $FP_LEN-char hex SHA-256"
            }
            require(modelVersion?.isNotBlank() != false) {
                "modelVersion must be non-blank or null"
            }
        }
    }

    data class FailedTerminal(
        val toolErrorCode: ToolErrorCode,
        val scrubbedMessage: String,
        val details: List<ToolErrorDetail> = emptyList(),
        val providerName: String? = null,
        val model: String? = null,
        val modelVersion: String? = null,
        val providerRequestId: String? = null,
        val promptFingerprint: String? = null,
    ) : AiToolWorkResult {
        init {
            require(scrubbedMessage.isNotBlank()) { "scrubbedMessage must not be blank" }
            require(promptFingerprint == null || promptFingerprint.length == FP_LEN) {
                "promptFingerprint must be null or a $FP_LEN-char hex SHA-256"
            }
        }
    }

    data class FailedRetryable(
        val toolErrorCode: ToolErrorCode,
        val scrubbedMessage: String,
        val details: List<ToolErrorDetail> = emptyList(),
        val providerName: String? = null,
        val model: String? = null,
        val modelVersion: String? = null,
        val providerRequestId: String? = null,
        val promptFingerprint: String? = null,
        val approvalRequestId: String? = null,
        val correlationKind: ApprovalCorrelationKind? = null,
        val correlationKey: String? = null,
        val requiredScopes: Set<String> = emptySet(),
        val reasons: List<String> = emptyList(),
    ) : AiToolWorkResult {
        init {
            require(scrubbedMessage.isNotBlank()) { "scrubbedMessage must not be blank" }
            require(promptFingerprint == null || promptFingerprint.length == FP_LEN) {
                "promptFingerprint must be null or a $FP_LEN-char hex SHA-256"
            }
            require(approvalRequestId?.isNotBlank() != false) {
                "approvalRequestId must be non-blank or null"
            }
            require(correlationKey?.isNotBlank() != false) {
                "correlationKey must be non-blank or null"
            }
        }
    }

    private companion object {
        const val FP_LEN: Int = 64
    }
}
