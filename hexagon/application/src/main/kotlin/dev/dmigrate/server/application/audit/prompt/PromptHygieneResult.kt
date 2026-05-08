package dev.dmigrate.server.application.audit.prompt

import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * Phase G § 5.3 + § 7.3 (G.4) — Ergebnis einer
 * [PromptHygieneService.sanitize]-Anfrage.
 *
 * Tool-Handler (G.6) verwerten:
 *
 * - [Allow] → Caller darf den Provider-Aufruf machen. Die
 *   `sanitizedPrompt` und `sanitizedPayloadJson` sind die einzigen
 *   Eingaben, die in [dev.dmigrate.server.application.ai.AiProviderRequest]
 *   und in den `payloadFingerprint` einfließen dürfen — der Tool-
 *   Handler darf keine eigene Modifikation mehr drauflegen.
 * - [Block] → der Handler antwortet sofort mit
 *   `PROMPT_HYGIENE_BLOCKED` (Plan §7.3) und schreibt den
 *   strukturierten [Block.reason] ins Audit-Event. Der Caller
 *   sieht eine generische Begründung über [Block.publicMessage]
 *   (kein Echo des Eingabetextes — Plan §6 G.4 Akzeptanz:
 *   "Fehlerdetails enthalten keine Secrets").
 */
sealed interface PromptHygieneResult {

    data class Allow(
        val sanitizedPrompt: String,
        val sanitizedPayloadJson: String,
        val promptFingerprint: String,
        val payloadFingerprint: String,
        val allowedRefs: List<ServerResourceUri>,
    ) : PromptHygieneResult

    /**
     * @param reason maschinenlesbare Klasse für Audit + Metriken.
     * @param publicMessage scrub-sichere Beschreibung für den
     *   Caller. Keine Secrets, keine konkreten Pattern-Matches,
     *   keine Hostnamen.
     * @param detectedClasses welche Secret-Klassen die Hygiene
     *   gefunden hat. Wird ins Audit-Event geschrieben (Klassen
     *   sind harmlos — nur das Vorhandensein, nicht der Wert).
     */
    data class Block(
        val reason: PromptHygieneBlockReason,
        val publicMessage: String,
        val detectedClasses: Set<DetectedSecretClass>,
    ) : PromptHygieneResult {
        init {
            require(publicMessage.isNotBlank()) { "publicMessage must not be blank" }
        }
    }
}

/**
 * Plan §6 G.4: maschinenlesbare Block-Gründe für Audit + Metrik.
 */
enum class PromptHygieneBlockReason {
    /** Eines oder mehrere Secret-Pattern haben gematcht. */
    SECRET_DETECTED,

    /** Block-Form für PEM-/SSH-Privatschlüssel. */
    PRIVATE_KEY_DETECTED,

    /** Inline-Bulk-Daten (CSV/JSON/SQL) über der Plausibilitätsgrenze. */
    BULK_DATA_DETECTED,

    /** Prompt überschreitet [PromptHygieneRequest.maxPromptBytes]. */
    PROMPT_TOO_LARGE,

    /** Payload überschreitet [PromptHygieneRequest.maxPayloadBytes]. */
    PAYLOAD_TOO_LARGE,

    /**
     * Der Prompt enthält eine `dmigrate://`-Resource-Ref, die nicht
     * in [PromptHygieneRequest.allowedResourceRefs] steht.
     */
    UNAUTHORIZED_REF,

    /**
     * Der Prompt enthält einen externen URL (`http://`/`https://`),
     * der weder Resource-Ref noch Provider-Endpoint ist. Plan §4.6
     * fordert Ressourcen statt freier Externals im Modellkontext.
     */
    EXTERNAL_URL_DETECTED,
}

/**
 * Strukturierte Markierung dessen, was die Hygiene gefunden hat.
 * Plan §5.3 Akzeptanz: "erkannte und entfernte Secret-Klassen" —
 * der Wert selbst landet **nie** im Audit, nur die Klasse.
 */
enum class DetectedSecretClass {
    JDBC_AUTHORITY_PASSWORD,
    QUERY_PARAM_PASSWORD,
    QUERY_PARAM_API_KEY,
    QUERY_PARAM_TOKEN,
    BEARER_TOKEN,
    APPROVAL_TOKEN,
    PEM_PRIVATE_KEY,
    SSH_PRIVATE_KEY,
    AWS_ACCESS_KEY,
    GENERIC_API_KEY_HINT,
}
