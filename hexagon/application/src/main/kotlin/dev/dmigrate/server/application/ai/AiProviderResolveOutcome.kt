package dev.dmigrate.server.application.ai

/**
 * Phase G § 5.2 — Ergebnis einer [AiProviderRegistry.resolve]-
 * Anfrage.
 *
 * Tool-Handler (G.6) mappen die Outcomes auf die Plan-§-7.2-
 * Fehlercodes:
 *
 * - [Resolved] → der Caller bekommt den Provider-Port; weitere
 *   Pflichten (Policy, Hygiene, Outcome-Reservation) liegen beim
 *   Handler.
 * - [NotConfigured] → Provider-ID existiert nicht in der Registry.
 *   Plan §6 G.3 Akzeptanz: Tool-Handler antwortet mit
 *   `FORBIDDEN_PRINCIPAL` (Caller darf den unbekannten Provider
 *   nicht nutzen) oder `POLICY_DENIED`.
 * - [Disabled] → Provider existiert, ist aber `enabled=false`.
 *   Mapping wie [NotConfigured] (von Caller-Seite nicht
 *   unterscheidbar — Plan §4.8: keine Server-Config-Details
 *   leaken).
 * - [UnknownModel] → Provider okay, Modell nicht in
 *   `allowedModels`. Plan §6 G.5: `VALIDATION_ERROR` mit
 *   Feldverweis `model`.
 * - [ServerMisconfigured] → Default-Provider fehlt oder Registry
 *   wurde mit invalider Config gestartet. Plan §6 G.3 Akzeptanz:
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
     *   (Plan §5.2: Modell-Whitelist ist serverseitiger
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
