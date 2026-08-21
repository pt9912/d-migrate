package dev.dmigrate.driver.connection

/**
 * Gemeinsame `jdbc:sqlserver:`-URL-Assemblierung für den produktiven
 * `MssqlJdbcUrlBuilder` (driver-mssql) und den [FallbackJdbcUrlBuilder]:
 * mssql-jdbc erwartet Semikolon-getrennte Properties
 * (`jdbc:sqlserver://host:port;databaseName=db;key=value`) statt der
 * `?k=v&`-Query-Form des Interface-Defaults.
 */
object SqlServerJdbcUrl {

    const val DEFAULT_PORT: Int = 1433

    fun base(host: String?, port: Int?, database: String?): String {
        val effectivePort = port ?: DEFAULT_PORT
        val databaseProperty = database
            ?.takeIf { it.isNotEmpty() }
            ?.let { ";databaseName=${escapeValue(it)}" }
            ?: ""
        return "jdbc:sqlserver://$host:$effectivePort$databaseProperty"
    }

    fun append(base: String, params: Map<String, String>): String =
        params.entries.fold(base) { acc, (key, value) -> "$acc;$key=${escapeValue(value)}" }

    private val ESCAPE_TRIGGERS = setOf(';', '=', '{', '}')

    /**
     * mssql-jdbc-Property-Escaping: Werte mit `;`/`=`/Klammern oder
     * Randleerzeichen müssen in `{}` stehen; innerhalb der Klammern ist nur
     * `}` (als `}}`) zu escapen.
     */
    fun escapeValue(value: String): String =
        if (value.any { it in ESCAPE_TRIGGERS } || value != value.trim()) {
            "{${value.replace("}", "}}")}}"
        } else {
            value
        }
}
