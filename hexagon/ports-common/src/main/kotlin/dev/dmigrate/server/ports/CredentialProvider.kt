package dev.dmigrate.server.ports

/**
 * Ein Credential-Provider löst `credentialRef`-Werte **eines** Schemes zu einer vollständigen
 * Connect-URL auf (ADR 0035). Beispiele: `env:` (Umgebungsvariable), `file:` (Datei-Inhalt),
 * künftig `keychain:` / `vault:`.
 *
 * Principal-frei — die Autorisierung (MCP `allowedPrincipalIds`/`allowedScopes`) sitzt im
 * `ConnectionSecretResolver`-Wrapper, nicht hier. Dadurch teilen CLI **und** MCP denselben
 * Provider-Layer über die [CredentialProviderRegistry].
 *
 * Implementierungen dürfen weder den Secret-Wert noch die aufgelöste URL loggen.
 */
interface CredentialProvider {

    /** Scheme-Prefix inklusive Doppelpunkt, z. B. `"env:"` oder `"file:"`. */
    val scheme: String

    /**
     * Löst [credentialRef] (beginnt mit [scheme]) zu einer vollständigen Connect-URL auf.
     * Liefert [CredentialResolution.Failure] mit stabilem `reason`-Code statt zu werfen.
     */
    fun resolve(credentialRef: String): CredentialResolution
}
