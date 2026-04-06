package dev.dmigrate.driver.connection

/**
 * Maskiert sensitive Bestandteile (aktuell: Passwörter in Verbindungs-URLs)
 * für die Ausgabe in Logs und Reports.
 *
 * Gemäß `connection-config-spec.md` §4.3 dürfen Passwörter und vollständige
 * Connection-URLs nie unmaskiert geloggt werden. Diese Klasse stellt einen
 * zentralen Maskierungspfad bereit, der vor jedem Log-Aufruf benutzt werden
 * MUSS, der eine URL enthält.
 *
 * **API-Keys** (relevant ab 1.1.0 mit den AI-Backends) werden in 0.3.0 noch
 * nicht behandelt — die LogScrubber-API wird in 1.1.0 um eine entsprechende
 * Methode erweitert.
 */
object LogScrubber {

    /**
     * Maskiert das Passwort in einer Connection-URL der Form
     * `<scheme>://[user[:password]@]host[:port]/...` als `***`.
     *
     * Beispiele:
     * - `postgresql://admin:secret@localhost/mydb` → `postgresql://admin:***@localhost/mydb`
     * - `postgresql://admin@localhost/mydb` → unverändert (kein Passwort vorhanden)
     * - `sqlite:///tmp/test.db` → unverändert (kein Authority-Block)
     *
     * Robust gegen URL-encoded Sonderzeichen im Passwort (`p%40ss` etc.) —
     * der gesamte Bereich zwischen `:` und `@` wird ersetzt.
     *
     * @return Eine Kopie der URL mit maskiertem Passwort, oder die Original-URL
     *   falls sie kein Passwort enthält oder kein erkennbares URL-Format hat.
     */
    fun maskUrl(url: String): String {
        // Pattern: scheme://user:password@host[...]
        // Wir matchen non-greedy zwischen "scheme://" und "@", um nur den Authority-Block zu treffen.
        // Die "user:password"-Form muss mindestens ein ":" zwischen scheme:// und @ enthalten.
        val regex = Regex("""(?<scheme>[a-zA-Z][a-zA-Z0-9+\-.]*://)(?<user>[^:/@?#]*):(?<pwd>[^@/?#]*)@""")
        return regex.replace(url) { match ->
            "${match.groups["scheme"]!!.value}${match.groups["user"]!!.value}:***@"
        }
    }
}
