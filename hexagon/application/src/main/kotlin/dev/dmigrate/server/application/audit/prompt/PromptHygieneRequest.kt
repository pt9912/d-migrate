package dev.dmigrate.server.application.audit.prompt

import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * Phase G § 5.3 (G.4) — Eingabe für den [PromptHygieneService].
 *
 * Bündelt alles, was der Hygiene-Service prüft:
 *
 * - die fachlichen Tool-Argumente (`payloadJson`) — werden für den
 *   Payload-Fingerprint und die Bulk-Daten-Heuristik gescannt,
 * - der bereits zusammengebaute Prompt-Text (`promptText`) — wird
 *   gegen Secret-Pattern geprüft und in den Prompt-Fingerprint
 *   gehasht,
 * - die Liste der erlaubten Resource-/Artifact-Refs — Refs im
 *   Prompt, die nicht in der Liste stehen, werden geblockt
 *   (Plan §4.6: "Ressourcen statt Rohdaten im Modellkontext"),
 * - der Zielprovider — Hygiene darf für externe Provider strenger
 *   sein als für `noop` (Plan §4.3 Z. 263-265),
 * - Limits — überschreitet Prompt oder Payload das Cap, blockt
 *   die Hygiene ohne Provider-Aufruf.
 *
 * **Bewusst NICHT enthalten**: `approvalKey`, `approvalToken`,
 * `idempotencyKey`, `requestId`. Diese Control-Felder werden vor
 * dem Hygiene-Aufruf vom Tool-Handler entfernt (Plan §6 G.6
 * Z. 1016-1019).
 */
data class PromptHygieneRequest(
    val toolName: String,
    val tenantId: TenantId,
    val principalId: PrincipalId,
    val allowedResourceRefs: List<ServerResourceUri>,
    val payloadJson: String,
    val promptText: String,
    val providerId: AiProviderId,
    val maxPromptBytes: Int,
    val maxPayloadBytes: Int,
) {
    init {
        require(toolName.isNotBlank()) { "toolName must not be blank" }
        require(payloadJson.isNotEmpty()) { "payloadJson must not be empty" }
        require(promptText.isNotBlank()) { "promptText must not be blank" }
        require(maxPromptBytes > 0) { "maxPromptBytes must be > 0" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be > 0" }
    }
}
