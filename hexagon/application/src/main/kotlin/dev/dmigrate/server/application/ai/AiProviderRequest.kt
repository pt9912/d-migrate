package dev.dmigrate.server.application.ai

import java.time.Duration

/**
 * LF-017 / LF-024 / LN-030 / LN-031— eingehender Provider-Aufruf.
 *
 * Der Aufruf trägt **nur das, was der Provider sehen darf**: das
 * bereits hygienisierte Prompt, eine erlaubte Modellbezeichnung
 * und Limits. Tool-spezifische Felder (`approvalKey`,
 * `approvalToken`, Tool-Name, `idempotencyKey`, `requestId`,
 * `principal`-Refs, JDBC-/Connection-Strings) sind hier **nicht**
 * vorhanden — der Tool-Handler entfernt sie vor dem
 * Provider-Request-Building (LF-017 / LF-024 / LN-030 / LN-031).
 *
 * Die beiden Fingerprint-Felder werden vom Tool-Handler aus dem
 * LF-017 / LF-024 / LN-030 / LN-031-Vertrag berechnet:
 *
 * - [promptFingerprint] — über das hygienisierte `prompt` plus
 *   Modell-/Provider-/Limit-Metadaten. Stabil bei identischem
 *   Prompt-Text.
 * - [payloadFingerprint] — über die fachlichen Eingabefelder des
 *   Tools (Source-Refs, `targetDialect`, Optionen) ohne
 *   Control-Felder. Stabil bei identischem fachlichem Aufruf.
 *
 * @param prompt das bereits durch [PromptHygieneService] geprüfte
 *   und ggf. redigierte Prompt (siehe LF-017 / LF-024 / LN-030 / LN-031). Enthält nie Secrets,
 *   freie JDBC-Strings oder Massendaten.
 * @param model die Provider-Modellbezeichnung. Muss aus der
 *   serverseitigen Provider-Konfiguration whitelisted sein
 *   (LF-017 / LF-024 / LN-030 / LN-031); der Provider-Adapter validiert nicht — er
 *   leitet weiter.
 * @param promptFingerprint hex-codierter SHA-256 (64 Zeichen) über
 *   den hygienisierten Prompt + Modell + Limits.
 * @param payloadFingerprint hex-codierter SHA-256 über die
 *   normalisierten fachlichen Tool-Argumente (LF-017 / LF-024 / LN-030 / LN-031 ff.).
 * @param timeout LF-017 / LF-024 / LN-030 / LN-031: Provider-Timeout, der vom Adapter
 *   honoriert werden muss. Überschreitung → [AiProviderError.TIMEOUT].
 * @param maxOutputBytes Provider-seitige Output-Cap. Adapter darf
 *   einen kleineren Wert erzwingen, aber nie einen größeren.
 *   Überschreitung → [AiProviderError.OUTPUT_TOO_LARGE].
 */
data class AiProviderRequest(
    val prompt: String,
    val model: String,
    val promptFingerprint: String,
    val payloadFingerprint: String,
    val timeout: Duration,
    val maxOutputBytes: Int,
) {
    init {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(promptFingerprint.length == FINGERPRINT_HEX_LENGTH) {
            "promptFingerprint must be a $FINGERPRINT_HEX_LENGTH-char hex SHA-256"
        }
        require(payloadFingerprint.length == FINGERPRINT_HEX_LENGTH) {
            "payloadFingerprint must be a $FINGERPRINT_HEX_LENGTH-char hex SHA-256"
        }
        require(!timeout.isNegative && !timeout.isZero) {
            "timeout must be positive"
        }
        require(maxOutputBytes > 0) { "maxOutputBytes must be > 0" }
    }

    private companion object {
        const val FINGERPRINT_HEX_LENGTH: Int = 64
    }
}
