package dev.dmigrate.driver

/**
 * Die neueste Version je Dialekt, gegen die d-migrate seine **Faehigkeiten**
 * gemessen hat.
 *
 * Wozu der Pin da ist: [DialectCapabilities.forTarget] beantwortet „kann das
 * Ziel das?" nach der Version. Ist die unbekannt — ein Dateiziel hat keine —,
 * gilt die **aktuellste bekannte**, nicht die konservativste. Der Grund steht
 * im Plan `faehigkeiten-sind-versionsabhaengig`: „konservativ" hiesse hier
 * nicht „nichts tun", sondern **etwas anderes rendern, als der Autor
 * geschrieben hat**. Die optimistische Wahl erzeugt dagegen ein Skript, das
 * entweder laeuft oder auf einem aelteren Server laut scheitert. Lautes
 * Scheitern schlaegt stille Degradation.
 *
 * **Der Pin traegt die Testmatrix, statt von ihr abzuhaengen.** „Aktuellste
 * bekannte" heisst genau: die Version, gegen die die Integrationssuite laeuft.
 * Damit das keine zwei Wahrheiten werden, haelt eine Spec die Werte hier gegen
 * die Bilder in `TestImages`. Wer ein Bild anhebt, hebt hier mit an — oder
 * faellt auf.
 *
 * SQL Server und SQLite fehlen, weil es fuer sie noch keinen
 * [ServerVersion]-Typ gibt. Ihre versionsabhaengigen Stellen stehen heute als
 * Ad-hoc-Schwellen in den Renderern; sie einzusammeln ist eine eigene Scheibe.
 */
object MeasuredServerVersions {

    val POSTGRESQL: PostgresServerVersion = PostgresServerVersion(major = 18, minor = 6)

    val MYSQL: MysqlServerVersion = MysqlServerVersion(major = 9, minor = 7, patch = 2)

    val ORACLE: OracleServerVersion = OracleServerVersion(major = 23, raw = "23.0.0.0.0")

    /** Der Pin des Dialekts — oder `null`, wo es noch keinen Versionstyp gibt. */
    fun of(dialect: DatabaseDialect): ServerVersion? = when (dialect) {
        DatabaseDialect.POSTGRESQL -> POSTGRESQL
        DatabaseDialect.MYSQL -> MYSQL
        DatabaseDialect.ORACLE -> ORACLE
        DatabaseDialect.MSSQL, DatabaseDialect.SQLITE -> null
    }
}
