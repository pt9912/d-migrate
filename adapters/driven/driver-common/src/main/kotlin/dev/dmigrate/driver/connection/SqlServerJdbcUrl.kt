package dev.dmigrate.driver.connection

/**
 * Gemeinsame `jdbc:sqlserver:`-URL-Assemblierung für den produktiven
 * `MssqlJdbcUrlBuilder` (driver-mssql) und den [FallbackJdbcUrlBuilder]:
 * mssql-jdbc erwartet Semikolon-getrennte Properties
 * (`jdbc:sqlserver://host:port;databaseName=db;key=value`) statt der
 * `?k=v&`-Query-Form des Interface-Defaults. [assemble] und [sslParams]
 * liegen hier, damit beide Builder identisch mergen und mappen — eine
 * Divergenz (z. B. Fallback ohne SSL-Mapping) war ein Review-Befund.
 */
object SqlServerJdbcUrl {

    const val DEFAULT_PORT: Int = 1433

    /** Merge-Präzedenz wie der Interface-Default: defaults < ssl < params. */
    fun assemble(config: ConnectionConfig, defaults: Map<String, String>): String {
        val params = LinkedHashMap<String, String>()
        params.putAll(defaults)
        params.putAll(sslParams(config.ssl))
        params.putAll(config.params)
        return append(base(config.host, config.port, config.database), params)
    }

    /**
     * Neutraler [SslMode] auf mssql-jdbc-Properties. Der Treiber kennt keine
     * opportunistische Stufe: ALLOW/PREFER runden auf verschlüsselt-aber-
     * unverifiziert auf (wie REQUIRE); VERIFY_CA rundet auf volle
     * Verifikation auf, weil der Treiber mit Kettenvalidierung immer auch
     * den Hostnamen prüft. `rootCert` (Truststore) ist wie beim
     * MySQL-Builder out of scope.
     */
    fun sslParams(ssl: SslSettings): Map<String, String> = when (ssl.mode) {
        null -> emptyMap()
        SslMode.DISABLE -> mapOf("encrypt" to "false")
        SslMode.ALLOW, SslMode.PREFER, SslMode.REQUIRE ->
            mapOf("encrypt" to "true", "trustServerCertificate" to "true")
        SslMode.VERIFY_CA, SslMode.VERIFY_FULL ->
            mapOf("encrypt" to "true", "trustServerCertificate" to "false")
    }

    fun base(host: String?, port: Int?, database: String?): String {
        val effectivePort = port ?: DEFAULT_PORT
        val databaseProperty = database
            ?.takeIf { it.isNotEmpty() }
            ?.let { ";databaseName=${escapeValue(it)}" }
            ?: ""
        return "jdbc:sqlserver://$host:$effectivePort$databaseProperty"
    }

    fun append(base: String, params: Map<String, String>): String =
        params.entries.fold(base) { acc, (key, value) ->
            // Property-KEYS kennen kein Brace-Escaping — ein `;`/`=` im Key
            // würde die Semikolon-Liste korrumpieren (Property-Smuggling).
            require(key.none { it in ";={}" }) {
                "Invalid JDBC property key '$key' for jdbc:sqlserver URLs"
            }
            "$acc;$key=${escapeValue(value)}"
        }

    /**
     * mssql-jdbc-Property-Escaping: Werte mit `;`/`=`/Klammern oder
     * Randleerzeichen müssen in `{}` stehen; innerhalb der Klammern ist nur
     * `}` (als `}}`) zu escapen.
     */
    fun escapeValue(value: String): String =
        if (value.any { it in ";={}" } || value != value.trim()) {
            "{${value.replace("}", "}}")}}"
        } else {
            value
        }
}
