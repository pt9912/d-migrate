package dev.dmigrate.server.ports

/**
 * Ergebnis der Auflösung eines `credentialRef` durch einen [CredentialProvider] bzw. die
 * [CredentialProviderRegistry] (ADR 0035). [Success.url] ist eine **vollständige** Connect-URL
 * (World-B-Parität), die der Aufrufer an `ConnectionUrlParser.parse` reicht.
 *
 * Dies ist die **principal-freie** Ebene der O4-Naht — der Provider-Layer, den CLI und MCP teilen.
 * Der MCP-`ConnectionSecretResolver`-Wrapper mappt [Failure] verbatim auf
 * [ResolvedConnection.Failure] (die `reason`-Strings sind bewusst identisch, s. u.).
 *
 * Implementierungen dürfen die aufgelöste URL / den Secret-Wert **nicht** auf INFO/DEBUG loggen und
 * halten [Failure.detail] frei von aufgelösten Secret-Fragmenten.
 */
sealed interface CredentialResolution {

    /** Auflösung erfolgreich; [url] ist die vollständige Connect-URL (trägt nach Expansion das Secret). */
    data class Success(val url: String) : CredentialResolution

    /**
     * Auflösung fehlgeschlagen. [reason] ist einer der `REASON_*`-Codes; [detail] ist eine
     * operator-taugliche, **secret-freie** Meldung.
     */
    data class Failure(val reason: String, val detail: String) : CredentialResolution

    companion object {
        // Single Source of Truth = ResolvedConnection: der MCP-Wrapper reicht den reason verbatim
        // durch, bestehende MCP-Konsumenten prüfen die ResolvedConnection-Strings. Referenzieren
        // statt duplizieren → kein String-Drift möglich (Security-Review F1/F3).
        /** Kein Provider für das Scheme des `credentialRef` registriert (unbekannt/fehlend). */
        const val REASON_PROVIDER_MISSING: String = ResolvedConnection.REASON_PROVIDER_MISSING

        /** `env:`-Provider: Umgebungsvariable nicht gesetzt. */
        const val REASON_ENV_NOT_SET: String = ResolvedConnection.REASON_ENV_NOT_SET

        /** `file:`-Provider: Pfad fehlt oder ist kein reguläres File. */
        const val REASON_FILE_NOT_FOUND: String = ResolvedConnection.REASON_FILE_NOT_FOUND

        /** `file:`-Provider: Datei nicht lesbar / I/O-Fehler / zu groß. */
        const val REASON_FILE_UNREADABLE: String = ResolvedConnection.REASON_FILE_UNREADABLE

        /** Aufgelöster Wert ist leer/whitespace-only (nach Trim). */
        const val REASON_EMPTY_VALUE: String = ResolvedConnection.REASON_EMPTY_VALUE
    }
}
