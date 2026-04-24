package dev.dmigrate.driver.connection

/**
 * Maskiert sensitive Bestandteile in Verbindungs-URLs
 * für die Ausgabe in Logs und Reports.
 *
 * Gemäß `connection-config-spec.md` §4.3 dürfen Passwörter und vollständige
 * Connection-URLs nie unmaskiert geloggt werden. Diese Klasse stellt einen
 * zentralen Maskierungspfad bereit, der vor jedem Log-Aufruf benutzt werden
 * MUSS, der eine URL enthält.
 */
object LogScrubber {

    /**
     * Maskiert Secrets in Connection-URLs:
     * - Authority-Passwörter in `<scheme>://[user[:password]@]host[:port]/...`
     * - sensitive Query-/DSN-Parameter wie `password=...`, `token=...`,
     *   `secret=...`, `api_key=...` oder `api-key=...`
     *
     * Beispiele:
     * - `postgresql://admin:secret@localhost/mydb` → `postgresql://admin:***@localhost/mydb`
     * - `postgresql://admin@localhost/mydb` → unverändert (kein Passwort vorhanden)
     * - `jdbc:postgresql://host/db?user=admin&password=secret` →
     *   `jdbc:postgresql://host/db?user=admin&password=***`
     * - `https://example.invalid?api_key=secret` →
     *   `https://example.invalid?api_key=***`
     * - `sqlite:///tmp/test.db` → unverändert (kein Authority-Block)
     *
     * Robust gegen URL-encoded Sonderzeichen im Passwort (`p%40ss` etc.) —
     * der gesamte Bereich zwischen `:` und `@` wird ersetzt.
     *
     * @return Eine Kopie der URL mit maskiertem Passwort, oder die Original-URL
     *   falls sie kein Passwort enthält oder kein erkennbares URL-Format hat.
     */
    fun maskUrl(url: String): String {
        return ConnectionSecretMasker.mask(url)
    }
}
