package dev.dmigrate.server.application.ai

/**
 * LF-017 / LF-024 / LN-030 / LN-031— produktive [AiProviderRegistry] mit
 * Validierung und Default-Garantie.
 *
 * Bootstrap-Vertrag:
 *
 * 1. Konstruktor validiert jede [AiProviderConfig] über
 *    [AiProviderConfigValidator]. Der erste Fehler lässt den
 *    Server fail-closed starten (LF-017 / LF-024 / LN-030 / LN-031: "Die Registry darf
 *    nicht mit invalider Config laufen").
 * 2. Wenn keine Config für [AiProviderId.NOOP] geliefert wurde,
 *    fügt die Registry einen NoOp-Default ein
 *    ([AiProviderConfig.noOpDefault]). Damit ist LF-017 / LF-024 / LN-030 / LN-031
 *    erfüllt: NoOp ist immer verfügbar.
 * 3. Pro Config-Eintrag muss ein passender [AiProviderPort] in
 *    [ports] vorhanden sein. Fehlt der Port, schlägt der
 *    Konstruktor mit [IllegalStateException] fehl — auch das ist
 *    fail-closed.
 *
 * @param configs Provider-Configs. Reihenfolge spielt keine Rolle
 *   — die Map-Bildung über [AiProviderId] ist eindeutig.
 *   Doppelte ProviderIds werfen [IllegalArgumentException].
 * @param ports Bindung von ProviderId → Port-Implementierung.
 *   Mindestens [AiProviderId.NOOP] muss ein Mapping haben (auf
 *   einen [NoOpAiProvider]-Instance).
 */
class DefaultAiProviderRegistry(
    configs: List<AiProviderConfig>,
    private val ports: Map<AiProviderId, AiProviderPort>,
) : AiProviderRegistry {

    private val configById: Map<AiProviderId, AiProviderConfig>

    init {
        val errors = configs.flatMap(AiProviderConfigValidator::validate)
        check(errors.isEmpty()) {
            "AiProviderRegistry got invalid configs: " +
                errors.joinToString("; ") { "${it.providerId}.${it.field}: ${it.reason}" }
        }
        val byId = mutableMapOf<AiProviderId, AiProviderConfig>()
        for (c in configs) {
            require(byId.put(c.providerId, c) == null) {
                "duplicate provider config for ${c.providerId}"
            }
        }
        // LF-017 / LF-024 / LN-030 / LN-031: NoOp ist immer verfügbar — fehlt er in der
        // gelieferten Liste, fällt die Registry auf den Default
        // zurück.
        if (AiProviderId.NOOP !in byId) {
            byId[AiProviderId.NOOP] = AiProviderConfig.noOpDefault()
        }
        configById = byId.toMap()

        // Jede Config muss einen passenden Port haben — sonst
        // wäre `resolve` für eine offiziell konfigurierte ID
        // dauerhaft im `ServerMisconfigured`-Pfad. Lieber jetzt
        // beim Start scheitern.
        for (id in configById.keys) {
            check(id in ports) {
                "AiProviderRegistry has config for $id but no port wired"
            }
        }
    }

    override fun resolve(providerId: AiProviderId, model: String): AiProviderResolveOutcome {
        val config = configById[providerId]
            ?: return AiProviderResolveOutcome.NotConfigured(providerId)
        if (!config.enabled) {
            return AiProviderResolveOutcome.Disabled(providerId)
        }
        if (model !in config.allowedModels) {
            return AiProviderResolveOutcome.UnknownModel(
                requested = providerId,
                requestedModel = model,
                allowedModels = config.allowedModels,
            )
        }
        val port = ports[providerId]
            ?: return AiProviderResolveOutcome.ServerMisconfigured(
                reason = "no port wired for provider ${providerId.value}",
            )
        return AiProviderResolveOutcome.Resolved(port, config)
    }

    /**
     * Discovery-Hilfe für `capabilities_list`-Erweiterung in LF-017 / LF-024 / LN-030 / LN-031:
     * liefert Provider-IDs + Modell-Whitelisten ohne Endpoint und
     * ohne `secretRef`. LF-017 / LF-024 / LN-030 / LN-031: Secrets nie in
     * Capabilities.
     */
    fun describe(): List<AiProviderDescription> = configById.values
        .filter { it.enabled }
        .map { c ->
            AiProviderDescription(
                providerId = c.providerId,
                kind = c.kind,
                allowedModels = c.allowedModels.toSortedSet(),
                maxPromptBytes = c.maxPromptBytes,
                maxOutputBytes = c.maxOutputBytes,
            )
        }
        .sortedBy { it.providerId.value }

    companion object {

        /**
         * LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: Tests und CI nutzen einen
         * Bootstrap, der **nur** den NoOp-Provider kennt — keine
         * externen Provider, keine Secrets. Bequemer Factory-
         * Helfer, damit Tests nicht jeweils die Default-Config
         * neu bauen müssen.
         */
        fun noOpOnly(noOpPort: AiProviderPort = NoOpAiProvider()): DefaultAiProviderRegistry =
            DefaultAiProviderRegistry(
                configs = listOf(AiProviderConfig.noOpDefault()),
                ports = mapOf(AiProviderId.NOOP to noOpPort),
            )
    }
}

/**
 * LF-017 / LF-024 / LN-030 / LN-031: secret-freie Provider-Beschreibung für
 * `capabilities_list` (LF-017 / LF-024 / LN-030 / LN-031). Endpoint und `secretRef` werden
 * **nicht** projeziert.
 */
data class AiProviderDescription(
    val providerId: AiProviderId,
    val kind: AiProviderKind,
    val allowedModels: Set<String>,
    val maxPromptBytes: Int,
    val maxOutputBytes: Int,
)
