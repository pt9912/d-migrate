package dev.dmigrate.server.application.ai

/**
 * LF-017 / LF-024 / LN-030 / LN-031— adapterneutrale Provider-Registry.
 *
 * Bootstrap baut **eine** Instanz pro Server-Prozess und reicht sie
 * an die KI-Tool-Handler in LF-017 / LF-024 / LN-030 / LN-031. Tool-Handler rufen
 * [resolve] mit dem vom Caller gewünschten (oder default-gemappten)
 * Provider und Modell auf; das Outcome entscheidet, ob der Caller
 * weiter darf (siehe [AiProviderResolveOutcome]).
 *
 * Die Registry hält **keinen** Tenant-/Principal-Kontext —
 * Tenant-Scope und Policy-Entscheidung liegen im Handler. Die
 * Registry ist ein reines Server-Konfig-Store mit Provider-Port-
 * Bindung.
 */
fun interface AiProviderRegistry {

    /**
     * Resolved den Provider für [providerId] und prüft, ob
     * [model] in der `allowedModels`-Liste liegt.
     *
     * Outcome-Uebersicht (LF-017 / LF-024 / LN-030 / LN-031 Mapping fuer Tool-Handler):
     *
     * | Outcome | Ursache | Handler-Mapping |
     * |---|---|---|
     * | `Resolved(port, config)` | Provider + Modell erlaubt | weiter |
     * | `NotConfigured(id)` | unbekannte ProviderID | `FORBIDDEN_PRINCIPAL`/`POLICY_DENIED` |
     * | `Disabled(id)` | Config existiert, aber `enabled=false` | wie `NotConfigured` |
     * | `UnknownModel(id, model, allowed)` | Modell nicht whitelisted | `VALIDATION_ERROR(model)` |
     * | `ServerMisconfigured(reason)` | Default-Provider fehlt o. Ä. | `INTERNAL_AGENT_ERROR` |
     */
    fun resolve(providerId: AiProviderId, model: String): AiProviderResolveOutcome
}
