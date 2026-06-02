package dev.dmigrate.server.application.ai

/**
 * LF-017 / LF-024 / LN-030 / LN-031— Provider-Antwort als sealed
 * Success/Failure-Vertrag.
 *
 * Der Adapter normalisiert provider-spezifische Throwables in
 * [Failure] und reicht **nie** den Stacktrace weiter (LF-017 / LF-024 / LN-030 / LN-031 +
 * §7.2: keine Secrets, keine Endpunkt-Hinweise im Audit/Wire-Pfad).
 */
sealed interface AiProviderResult {

    /**
     * Provider hat einen verwertbaren Output geliefert.
     *
     * @param output Provider-Antwort als UTF-8-String. Bereits
     *   gegen `maxOutputBytes` aus [AiProviderRequest] geprüft.
     *   Der Tool-Handler ruft auf dieser Antwort die
     *   Output-Hygiene auf (LF-017 / LF-024 / LN-030 / LN-031) bevor er ein Artefakt
     *   publiziert.
     * @param outputFingerprint hex-codierter SHA-256 über
     *   [output] (UTF-8-Bytes). Geht in die KI-Artefakt-Provenance
     *   ein (`outputFingerprint` in `AiArtifactMetadata`).
     * @param providerMeta Provider-Identität für Audit + Provenance.
     */
    data class Success(
        val output: String,
        val outputFingerprint: String,
        val providerMeta: ProviderMeta,
    ) : AiProviderResult {
        init {
            require(output.isNotEmpty()) { "output must not be empty" }
            require(outputFingerprint.length == FINGERPRINT_HEX_LENGTH) {
                "outputFingerprint must be a $FINGERPRINT_HEX_LENGTH-char hex SHA-256"
            }
        }
    }

    /**
     * Provider hat einen typisierten Fehler signalisiert. Der
     * Tool-Handler mappt [error] auf einen `ToolErrorCode` (Plan
     * §7.2) und nutzt [retryable] für die `AiToolOutcomeStore`-
     * Entscheidung (`FAILED_TERMINAL` vs. `FAILED_RETRYABLE`).
     *
     * @param message kurze, scrub-sichere Beschreibung des
     *   Fehlers. **Nie** Provider-Stacktraces, Endpunkt-URLs,
     *   API-Keys, Secrets. Wird durch [Failure.message] in den
     *   Wire-Envelope geschrieben.
     */
    data class Failure(
        val error: AiProviderError,
        val message: String,
        val retryable: Boolean = error.defaultRetryable,
    ) : AiProviderResult {
        init {
            require(message.isNotBlank()) { "message must not be blank" }
        }
    }

    private companion object {
        const val FINGERPRINT_HEX_LENGTH: Int = 64
    }
}
