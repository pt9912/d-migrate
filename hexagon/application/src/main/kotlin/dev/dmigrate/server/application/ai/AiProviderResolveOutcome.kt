package dev.dmigrate.server.application.ai

/**
 * LF-017 / LF-024 / LN-030 / LN-031— Ergebnis einer [AiProviderRegistry.resolve]-
 * Anfrage.
 *
 * Tool-Handler mappen die Outcomes auf die LF-017 / LF-024 / LN-030 / LN-031-
 * Fehlercodes:
 *
 * - [Resolved] → der Caller bekommt den Provider-Port; weitere
 *   Pflichten (Policy, Hygiene, Outcome-Reservation) liegen beim
 *   Handler.
 * - [NotConfigured] → Provider-ID existiert nicht in der Registry.
 *   LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: Tool-Handler antwortet mit
 *   `FORBIDDEN_PRINCIPAL` (Caller darf den unbekannten Provider
 *   nicht nutzen) oder `POLICY_DENIED`.
 * - [Disabled] → Provider existiert, ist aber `enabled=false`.
 *   Mapping wie [NotConfigured] (von Caller-Seite nicht
 *   unterscheidbar — LF-017 / LF-024 / LN-030 / LN-031: keine Server-Config-Details
 *   leaken).
 * - [UnknownModel] → Provider okay, Modell nicht in
 *   `allowedModels`. LF-017 / LF-024 / LN-030 / LN-031: `VALIDATION_ERROR` mit
 *   Feldverweis `model`.
 * - [ServerMisconfigured] → Default-Provider fehlt oder Registry
 *   wurde mit invalider Config gestartet. LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz:
 *   `INTERNAL_AGENT_ERROR`.
 */
sealed interface AiProviderResolveOutcome {

    data class Resolved(
        val port: AiProviderPort,
        val config: AiProviderConfig,
    ) : AiProviderResolveOutcome

    data class NotConfigured(val requested: AiProviderId) : AiProviderResolveOutcome

    data class Disabled(val requested: AiProviderId) : AiProviderResolveOutcome

    /**
     * Provider ist konfiguriert, aber das angefragte Modell ist
     * nicht in `allowedModels` whitelisted.
     *
     * @param allowedModels die in der Config eingetragenen Werte —
     *   der Tool-Handler darf sie an den Caller weiterreichen
     *   (LF-017 / LF-024 / LN-030 / LN-031: Modell-Whitelist ist serverseitiger
     *   Discovery-Inhalt, nicht geheim).
     */
    data class UnknownModel(
        val requested: AiProviderId,
        val requestedModel: String,
        val allowedModels: Set<String>,
    ) : AiProviderResolveOutcome

    /**
     * Strukturierter Server-Konfigurationsfehler. Caller sieht
     * nur einen generischen `INTERNAL_AGENT_ERROR`-Envelope; die
     * [reason]-Details landen ins Audit/Server-Log, nicht in die
     * Tool-Antwort.
     */
    data class ServerMisconfigured(val reason: String) : AiProviderResolveOutcome
}
