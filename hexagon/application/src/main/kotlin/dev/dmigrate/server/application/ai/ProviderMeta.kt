package dev.dmigrate.server.application.ai

/**
 * LF-017 / LF-024 / LN-030 / LN-031— Provider-/Modell-Identität für Audit und KI-
 * Artefakt-Provenance.
 *
 * Wird durch den Provider-Adapter beim erfolgreichen Aufruf
 * gefüllt; der Tool-Handler legt [providerName], [model] und
 * [modelVersion] in `AiArtifactMetadata` ab und schreibt sie ins
 * Audit-Event (`AuditFields.providerMeta`-Slot in LF-017 / LF-024 / LN-030 / LN-031).
 *
 * **Verboten in jedem Feld**: Endpunkt-URLs, API-Keys,
 * `secretRef`-Werte, Tenant-/Principal-IDs, freie SQL-Strings.
 * LF-017 / LF-024 / LN-030 / LN-031: Audit speichert Fingerprints, keine Secrets.
 *
 * @param providerName stabile Provider-Identität wie `noop`,
 *   `ollama`, `lm-studio`, `openai`, `anthropic`. Passt zum
 *   `AiProviderRegistry`-Schlüssel aus LF-017 / LF-024 / LN-030 / LN-031.
 * @param model logische Modellbezeichnung wie `llama3.1:8b`,
 *   `claude-opus-4-7`, `gpt-4o`. Direkt aus [AiProviderRequest.model].
 * @param modelVersion optional eine Provider-spezifische
 *   Versionskennung. `null` wenn der Provider keine Versionierung
 *   liefert (etwa lokale Provider).
 * @param requestId Provider-seitige Request-ID, sofern verfügbar.
 *   `null` für lokale Provider ohne stabile Korrelation.
 */
data class ProviderMeta(
    val providerName: String,
    val model: String,
    val modelVersion: String?,
    val requestId: String?,
) {
    init {
        require(providerName.isNotBlank()) { "providerName must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(modelVersion?.isNotBlank() != false) {
            "modelVersion must be non-blank or null"
        }
        require(requestId?.isNotBlank() != false) {
            "requestId must be non-blank or null"
        }
    }
}
