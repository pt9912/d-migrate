package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parst Datenbank-Verbindungs-URLs gemäß `connection-config-spec.md` §1.
 *
 * Unterstützte Formen:
 *
 * - `postgresql://[user[:password]@]host[:port]/database[?params]`
 * - `mysql://[user[:password]@]host[:port]/database[?params]`
 * - `sqlite:///<absolute-path>[?params]`
 * - `sqlite://<relative-path>[?params]`
 * - `sqlite::memory:[?params]`
 *
 * Dialekt-Aliase werden über [DatabaseDialect.fromString] normalisiert
 * (`postgres`/`pg` → `postgresql`, `maria`/`mariadb` → `mysql`, etc.).
 *
 * URL-encodete Sonderzeichen in `user` und `password` werden dekodiert
 * (siehe spec §1.7).
 */
object ConnectionUrlParser {

    /**
     * Parst die URL und liefert eine [ConnectionConfig].
     *
     * @throws IllegalArgumentException wenn die URL leer/ungültig ist oder
     *   der Dialekt nicht unterstützt wird.
     */
    fun parse(url: String): ConnectionConfig {
        require(url.isNotBlank()) { "Connection URL must not be blank" }

        // SQLite-Sonderformen abhandeln, bevor wir auf java.net.URI gehen,
        // weil "sqlite::memory:" und "sqlite:///path" mit dem Standard-URI-Parser
        // bei Authority/Path-Splitting komische Effekte erzeugen.
        if (url.startsWith("sqlite:", ignoreCase = true) || url.startsWith("sqlite3:", ignoreCase = true)) {
            return parseSqlite(url)
        }

        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("Invalid connection URL: ${LogScrubber.maskUrl(url)} — ${e.message}", e)
        }

        val scheme = uri.scheme
            ?: throw IllegalArgumentException("Connection URL is missing a dialect scheme (expected '<dialect>://...'): ${LogScrubber.maskUrl(url)}")

        val dialect = try {
            DatabaseDialect.fromString(scheme)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown database dialect '$scheme' in URL ${LogScrubber.maskUrl(url)}. Supported: postgresql, mysql, sqlite", e)
        }

        val host = uri.host
            ?: throw IllegalArgumentException("Connection URL is missing a host: ${LogScrubber.maskUrl(url)}")
        val port = if (uri.port == -1) null else uri.port

        val database = (uri.path ?: "").removePrefix("/").also {
            require(it.isNotBlank()) {
                "Connection URL is missing a database name: ${LogScrubber.maskUrl(url)}"
            }
        }

        // userInfo enthält "user[:password]" — beide Teile potenziell URL-encoded.
        val (user, password) = parseUserInfo(uri.rawUserInfo)

        val params = parseQuery(uri.rawQuery)

        return ConnectionConfig(
            dialect = dialect,
            host = host,
            port = port,
            database = database,
            user = user,
            password = password,
            params = params,
        )
    }

    private fun parseSqlite(url: String): ConnectionConfig {
        // Schema-Prefix abschneiden — wir akzeptieren sqlite: und sqlite3:
        val withoutScheme = when {
            url.startsWith("sqlite3:", ignoreCase = true) -> url.substring("sqlite3:".length)
            url.startsWith("sqlite:", ignoreCase = true) -> url.substring("sqlite:".length)
            else -> error("unreachable") // already filtered
        }

        // Query-String abtrennen
        val queryIndex = withoutScheme.indexOf('?')
        val (rawDatabase, rawQuery) = if (queryIndex >= 0) {
            withoutScheme.substring(0, queryIndex) to withoutScheme.substring(queryIndex + 1)
        } else {
            withoutScheme to null
        }

        val database = when {
            rawDatabase == ":memory:" -> ":memory:"
            // sqlite:///absolute/path → ":///absolute/path" → behalte den führenden Slash
            rawDatabase.startsWith("///") -> rawDatabase.substring(2) // → "/absolute/path"
            // sqlite://relative/path → "//relative/path" → relative/path
            rawDatabase.startsWith("//") -> rawDatabase.substring(2)
            // Defensive: erlaubt auch "sqlite:./local.db" für Bequemlichkeit
            else -> rawDatabase
        }

        require(database.isNotBlank()) {
            "SQLite connection URL is missing a database path: $url"
        }

        return ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null,
            port = null,
            database = database,
            user = null,
            password = null,
            params = parseQuery(rawQuery),
        )
    }

    private fun parseUserInfo(rawUserInfo: String?): Pair<String?, String?> {
        if (rawUserInfo.isNullOrEmpty()) return null to null
        val colonIndex = rawUserInfo.indexOf(':')
        return if (colonIndex < 0) {
            decode(rawUserInfo) to null
        } else {
            decode(rawUserInfo.substring(0, colonIndex)) to decode(rawUserInfo.substring(colonIndex + 1))
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val eq = pair.indexOf('=')
            if (eq < 0) decode(pair) to "" else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
        }.toMap()
    }

    private fun decode(s: String): String =
        URLDecoder.decode(s, StandardCharsets.UTF_8)
}
