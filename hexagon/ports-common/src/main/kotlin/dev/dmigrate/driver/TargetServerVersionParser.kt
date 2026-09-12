package dev.dmigrate.driver

/** Was beim Lesen einer ausdruecklich angegebenen Zielversion herauskam. */
sealed interface TargetVersionParse {

    data class Parsed(val version: ServerVersion) : TargetVersionParse

    /**
     * Der Dialekt fuehrt (noch) keinen Versionstyp — heute SQL Server und
     * SQLite. Ihre versionsabhaengigen Stellen stehen als Ad-hoc-Schwellen in
     * den Renderern; sie einzusammeln ist eine eigene Scheibe.
     */
    data object NotVersioned : TargetVersionParse

    /** Der Text passt nicht zu der Schreibweise, in der dieser Hersteller Versionen ausweist. */
    data class Unreadable(val expected: String) : TargetVersionParse
}

/**
 * Liest die **ausdruecklich angegebene** Zielversion.
 *
 * Wozu es sie gibt: ohne Angabe antwortet [DialectCapabilities.forTarget] fuer
 * die aktuellste gemessene Version ([MeasuredServerVersions]) — optimistisch,
 * weil lautes Scheitern besser ist als stille Degradation. Wer bewusst fuer
 * einen aelteren Server erzeugt, braucht aber einen Weg, das zu sagen, statt
 * auf diesen Vorgabewert zu treffen.
 *
 * Gegen eine lebende Datenbank ist die Angabe trotzdem erlaubt und **gewinnt**:
 * sie ist eine Aussage ueber das Ziel des erzeugten Skripts, nicht ueber die
 * Verbindung, ueber die gelesen wurde.
 */
object TargetServerVersionParser {

    /** Nur Ziffern — dann fehlt die Nebenversion, und `16` meint `16.0`. */
    private val MAJOR_ONLY = Regex("""^\d+$""")

    fun parse(dialect: DatabaseDialect, raw: String): TargetVersionParse {
        val text = raw.trim()
        return when (dialect) {
            DatabaseDialect.POSTGRESQL -> read(
                PostgresServerVersion.parse(if (MAJOR_ONLY.matches(text)) "$text.0" else text),
                "a major version like 16, or major.minor like 16.4",
            )
            DatabaseDialect.MYSQL -> read(
                MysqlServerVersion.parse(text),
                "major.minor.patch, like 8.0.16",
            )
            DatabaseDialect.ORACLE -> read(
                OracleServerVersion.parse(text),
                "a release number like 23 or 23.26.3.0.0",
            )
            DatabaseDialect.MSSQL, DatabaseDialect.SQLITE -> TargetVersionParse.NotVersioned
        }
    }

    private fun read(version: ServerVersion?, expected: String): TargetVersionParse =
        version?.let { TargetVersionParse.Parsed(it) } ?: TargetVersionParse.Unreadable(expected)
}
