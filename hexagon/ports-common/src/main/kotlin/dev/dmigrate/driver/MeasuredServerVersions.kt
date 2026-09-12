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
 * **„Unbekannt" heisst nicht bei jeder Faehigkeit dasselbe.** Die Begruendung
 * oben traegt nur die eine Haelfte, und beim Einsammeln der uebrigen
 * versionsabhaengigen Stellen fiel die andere auf. Es sind zwei Klassen:
 *
 * | Klasse | Beispiel | Unbekannt heisst |
 * | --- | --- | --- |
 * | Die Faehigkeit entscheidet, **wie die Angabe des Autors gerendert wird** | `supportsVirtualComputedColumns` (`stored: false`) | **optimistisch** — konservativ hiesse, etwas anderes zu rendern als das Geschriebene |
 * | Die Faehigkeit entscheidet eine **Bequemlichkeit oder eine Verweigerung** | `supportsDropIfExists`, `supportsComputedExpressionInPlace` | **konservativ** — optimistisch erzeugte einen Fehler fuer etwas, das niemand verlangt hat, bzw. eine geratene Zusage |
 *
 * Der Pin hier gilt fuer die erste Klasse. Die zweite fragt ihn nicht: dort
 * steht `version?.<praedikat> ?: false` an der Faehigkeit selbst, und die
 * jeweilige KDoc sagt, warum.
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
