package dev.dmigrate.connection

/**
 * ADR 0040: austauschbares Backend für den `keychain:`-Zugriff. Der
 * [KeychainCredentialProvider] delegiert hierhin, sodass der Zugriffs-**Mechanismus**
 * (CLI-Shell-out heute; native/JNA als opt-in-Folge-Modul) ein reines Adapter-Detail
 * bleibt — ohne Rückwirkung auf Provider oder Registry.
 *
 * Fail-closed und **wirft nicht**: jeder Fehler/Timeout/Nicht-Verfügbar wird als
 * [KeychainLookup.Unavailable] zurückgegeben. Implementierungen loggen weder den
 * gefundenen Wert (Secret) noch echoen ihn in `detail`.
 */
interface KeychainBackend {

    /**
     * `true`, wenn dieses Backend in der aktuellen Umgebung überhaupt arbeiten kann
     * (passendes OS + Tool vorhanden, nicht headless). Billig und seiteneffektfrei;
     * [lookup] darf trotzdem noch [KeychainLookup.Unavailable] liefern (Prompt/Race).
     */
    fun isAvailable(): Boolean

    /** Liest den Keychain-Eintrag für [service] (+ optional [account]). */
    fun lookup(service: String, account: String?): KeychainLookup
}

/** Ergebnis eines [KeychainBackend.lookup]. */
sealed interface KeychainLookup {

    /** Eintrag gefunden; [value] ist der gespeicherte Wert (hier die vollständige Connect-URL). */
    data class Found(val value: String) : KeychainLookup

    /** Kein Eintrag für service/account. */
    data object NotFound : KeychainLookup

    /** Backend nicht nutzbar (headless / OS-Tool fehlt / Timeout / Fehler). [detail] ist **secret-frei**. */
    data class Unavailable(val detail: String) : KeychainLookup
}
