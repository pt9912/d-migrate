package dev.dmigrate.server.ports

/**
 * Dispatcht einen `credentialRef` am **Scheme-Prefix** an den zuständigen [CredentialProvider]
 * (ADR 0035). Der wiederverwendbare, **principal-freie** Kern der O4-Naht: CLI ruft ihn direkt,
 * der MCP-`ConnectionSecretResolver`-Wrapper delegiert an ihn nach der Principal-Autorisierung.
 *
 * Dispatch am Scheme hält das bestehende `env:`-Modell bei; `providerRef` ist (noch) **nicht**
 * dispatch-relevant (ADR 0035 D2). Ein unbekanntes/fehlendes Scheme ist **fail-closed**:
 * [CredentialResolution.Failure] mit [CredentialResolution.REASON_PROVIDER_MISSING], niemals ein
 * stiller Degrade.
 */
class CredentialProviderRegistry(private val providers: List<CredentialProvider>) {

    init {
        val schemes = providers.map { it.scheme }
        require(schemes.toSet().size == schemes.size) {
            "duplicate CredentialProvider schemes: ${schemes.sorted()}"
        }
        require(schemes.all { it.isNotBlank() }) { "CredentialProvider scheme must not be blank" }
        // Prefix-Shadowing ausschließen: wäre ein Scheme Prefix eines anderen (z. B. "env:" vs
        // "env:sub:"), würde der First-Match-Dispatch reihenfolge-abhängig. Diese Invariante macht
        // das „Reihenfolge egal"-Versprechen erzwingbar (Security-Review F2).
        for (outer in schemes) {
            for (inner in schemes) {
                require(outer == inner || !inner.startsWith(outer)) {
                    "CredentialProvider scheme '$outer' is a prefix of '$inner' — " +
                        "schemes must be disjoint for order-independent dispatch"
                }
            }
        }
    }

    /**
     * Wählt den Provider, dessen [CredentialProvider.scheme] ein Prefix von [credentialRef] ist,
     * und delegiert. Kein passendes Scheme → [CredentialResolution.Failure] (PROVIDER_MISSING).
     * Die Fehlermeldung nennt die unterstützten Schemes, **nicht** den `credentialRef`-Wert.
     */
    fun resolve(credentialRef: String): CredentialResolution {
        val provider = providers.firstOrNull { credentialRef.startsWith(it.scheme) }
            ?: return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_PROVIDER_MISSING,
                detail = "credentialRef uses an unsupported provider scheme; supported schemes: " +
                    supportedSchemes().joinToString(", "),
            )
        return provider.resolve(credentialRef)
    }

    /** Die registrierten Schemes (sortiert), z. B. für Fehlermeldungen/Diagnose. */
    fun supportedSchemes(): List<String> = providers.map { it.scheme }.sorted()
}
