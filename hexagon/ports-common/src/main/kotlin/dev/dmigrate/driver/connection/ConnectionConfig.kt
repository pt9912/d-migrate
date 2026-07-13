package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect

/**
 * Geparste Datenbank-Verbindungskonfiguration.
 *
 * Wird vom [ConnectionUrlParser] aus einer URL der Form
 * `<dialect>://[user[:password]@]host[:port]/database[?params]` erzeugt
 * und vom [HikariConnectionPoolFactory] in einen [ConnectionPool] umgewandelt.
 *
 * **Sicherheitshinweis**: [password] ist sensitiv. Die [toString]-Implementierung
 * maskiert das Passwort als `***`, damit `ConnectionConfig`-Instanzen unbedenklich
 * geloggt werden können (siehe docs/archive/implementation-plan-0.3.0.md §6.11).
 */
data class ConnectionConfig(
    val dialect: DatabaseDialect,
    val host: String?,
    val port: Int?,
    val database: String,
    val user: String?,
    val password: String?,
    val params: Map<String, String> = emptyMap(),
    val pool: PoolSettings = PoolSettings(),
    /** First-Class SSL/TLS-Einstellungen (LN-026); Modus + CA-Pfad, nicht sensibel. */
    val ssl: SslSettings = SslSettings(),
    /**
     * Read-only-Absicht für reine Lese-Verbindungen (Quelle von `data profile`/
     * `export`/`transfer`). SQLite öffnet dann mit `SQLITE_OPEN_READONLY`
     * (`file:…?mode=ro`, ohne `journal_mode=wal`) — profilierbar auch bei
     * nicht-schreibbarer Quelle, ohne `-wal`/`-shm`-Nebendateien. Andere
     * Dialekte ignorieren das Flag heute (kein Datei-Schreib-Problem).
     */
    val readOnly: Boolean = false,
) {
    override fun toString(): String = buildString {
        append("ConnectionConfig(dialect=").append(dialect)
        append(", host=").append(host)
        append(", port=").append(port)
        append(", database=").append(database)
        append(", user=").append(user)
        append(", password=")
        append(if (password != null) "***" else "null")
        append(", params=").append(params)
        append(", pool=").append(pool)
        append(", ssl=").append(ssl)
        append(", readOnly=").append(readOnly)
        append(')')
    }
}
