package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect

/**
 * Extrahiert die dialekt-eigenen SSL-Params (LN-026) aus der rohen Query-Param-Map
 * in ein neutrales [SslSettings] und liefert die **restlichen** Params ohne die
 * verbrauchten SSL-Keys zurück (Single Source of Truth — die `JdbcUrlBuilder`
 * emittieren SSL ausschließlich aus `config.ssl`, nicht mehr aus `params`).
 *
 * Ungültige Modus-Werte werfen [IllegalArgumentException] mit einer via
 * [ConnectionSecretMasker] gescrubbten URL. „Modell im Hexagon, Parsing im
 * Adapter": das neutrale Modell (`SslMode`/`SslSettings`) lebt in ports-common,
 * das dialekt-abhängige Parsen hier bei [ConnectionUrlParser].
 */
internal object SslSettingsParser {

    data class Extracted(val ssl: SslSettings, val remainingParams: Map<String, String>)

    fun extract(dialect: DatabaseDialect, params: Map<String, String>, url: String): Extracted =
        when (dialect) {
            DatabaseDialect.POSTGRESQL -> extractPg(params, url)
            DatabaseDialect.MYSQL -> extractMysql(params, url)
            DatabaseDialect.SQLITE -> Extracted(SslSettings(), params) // kein Netz-SSL — unberührt
        }

    private fun extractPg(params: Map<String, String>, url: String): Extracted {
        // Kanonische (lowercase) Schluessel: filterConsumed entfernt ALLE Case-Varianten
        // eines verbrauchten Keys (Befund 9, CWE-178) — sonst ueberlebte ein
        // case-abweichendes Duplikat (`sslMode` neben `sslmode`) in den remainingParams
        // und koennte den validierten Modus in der emittierten URL ueberschreiben.
        val consumed = mutableSetOf<String>()
        val mode = findKey(params, "sslmode")?.let { (_, v) -> consumed += "sslmode"; parsePgMode(v, url) }
        val rootCert = findKey(params, "sslrootcert")?.let { (_, v) -> consumed += "sslrootcert"; v }
        return Extracted(SslSettings(mode, rootCert), filterConsumed(params, consumed))
    }

    private fun extractMysql(params: Map<String, String>, url: String): Extracted {
        val consumed = mutableSetOf<String>()
        val sslMode = findKey(params, "sslMode")
        val ssl = findKey(params, "ssl")
        // `sslMode` gewinnt; `ssl` (Legacy-Bool) nur als Fallback — beide werden
        // konsumiert, wenn `sslMode` vorhanden ist.
        val mode = when {
            sslMode != null -> {
                consumed += "sslmode"
                if (ssl != null) consumed += "ssl"
                parseMysqlMode(sslMode.second, url)
            }
            ssl != null -> {
                consumed += "ssl"
                parseMysqlSslBool(ssl.second, url)
            }
            else -> null
        }
        return Extracted(SslSettings(mode, null), filterConsumed(params, consumed))
    }

    /** Entfernt jeden Param, dessen Schluessel (case-insensitiv) einen verbrauchten kanonischen Key trifft. */
    private fun filterConsumed(params: Map<String, String>, consumedLower: Set<String>): Map<String, String> =
        params.filterKeys { it.lowercase() !in consumedLower }

    private fun parsePgMode(value: String, url: String): SslMode = when (value.lowercase()) {
        "disable" -> SslMode.DISABLE
        "allow" -> SslMode.ALLOW
        "prefer" -> SslMode.PREFER
        "require" -> SslMode.REQUIRE
        "verify-ca" -> SslMode.VERIFY_CA
        "verify-full" -> SslMode.VERIFY_FULL
        else -> throw invalid("sslmode", value, url, "disable|allow|prefer|require|verify-ca|verify-full")
    }

    private fun parseMysqlMode(value: String, url: String): SslMode = when (value.uppercase()) {
        "DISABLED" -> SslMode.DISABLE
        "PREFERRED" -> SslMode.PREFER
        "REQUIRED" -> SslMode.REQUIRE
        "VERIFY_CA" -> SslMode.VERIFY_CA
        "VERIFY_IDENTITY" -> SslMode.VERIFY_FULL
        else -> throw invalid("sslMode", value, url, "DISABLED|PREFERRED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY")
    }

    // Legacy `ssl=true/false` ist opportunistisch (≈ altes useSSL) → PREFER, NICHT
    // REQUIRE (Review 1: würde Nicht-TLS-Server brechen; ssl ist unter Connector/J 9.x
    // ohnehin ein No-Op).
    private fun parseMysqlSslBool(value: String, url: String): SslMode = when (value.lowercase()) {
        "true", "1", "yes", "on" -> SslMode.PREFER
        "false", "0", "no", "off" -> SslMode.DISABLE
        else -> throw invalid("ssl", value, url, "true|false")
    }

    private fun findKey(params: Map<String, String>, key: String): Pair<String, String>? =
        params.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let { it.key to it.value }

    private fun invalid(key: String, value: String, url: String, allowed: String): IllegalArgumentException =
        IllegalArgumentException(
            "Invalid $key value '$value' in ${ConnectionSecretMasker.mask(url)}. Allowed: $allowed",
        )
}
