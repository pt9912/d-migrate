package dev.dmigrate.driver.connection

/**
 * Driven-Port für den lokalen, verschlüsselten Credential-Store (LN-025, „O2" aus ADR 0034).
 *
 * Zugangsdaten werden passphrase-verschlüsselt in einer Datei abgelegt; der Schlüssel wird aus einem
 * Master-Secret abgeleitet und **nicht** als Datei gespeichert. Slice 1 baut den Store plus die
 * `config credentials set`/`list`-Kommandos; das Konsumieren in der `--source`-Auflösung (Stufe 4 der
 * Prioritätskette) folgt als eigener Schnitt (Slice 2, O4-Naht).
 */
interface CredentialStorePort {

    /** Legt Zugangsdaten unter [name] ab (upsert). Der Store wird danach neu verschlüsselt geschrieben. */
    fun put(name: String, user: String, password: CharArray)

    /** Namen aller gespeicherten Einträge — **nie** Werte oder Passwörter. Leerer Store → leere Liste. */
    fun listNames(): List<String>

    /** Zugangsdaten zu [name], oder `null` wenn kein Eintrag existiert. */
    fun resolve(name: String): StoredCredential?

    /**
     * Ob der Store bereits existiert (Datei vorhanden) — **ohne** Entschlüsselung, braucht kein
     * Master-Secret. Steuert die Confirm-Doppel-Eingabe bei Neuanlage.
     */
    fun isInitialized(): Boolean
}

/**
 * Zugangsdaten aus dem Store. **Bewusst keine `data class`:** die generierte `toString()`/`equals`
 * würden das Passwort exponieren, und ein `CharArray` bricht die `data class`-`equals`/`hashCode`.
 * Das Passwort-Array wird nach Gebrauch [wipe]-t.
 */
class StoredCredential(val user: String, private val secret: CharArray) {

    /** Kopie des Passworts. Der Aufrufer ist für das Wipen der Kopie zuständig. */
    fun password(): CharArray = secret.copyOf()

    /** Überschreibt das interne Passwort-Array (Best-Effort-Speicherbereinigung). */
    fun wipe() {
        secret.fill(' ')
    }

    override fun toString(): String = "StoredCredential(user=***, password=***)"
}

/**
 * Fehler beim Lesen/Schreiben/Entschlüsseln des Credential-Stores (falsches Master-Secret, beschädigte
 * oder manipulierte Datei, I/O-Fehler). Die [message] ist **secret-frei** und für Operatoren gedacht;
 * der Aufrufer mappt sie typischerweise auf Exit 7.
 */
class CredentialStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
