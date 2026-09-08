package dev.dmigrate.driver

/**
 * Ob ein Routinen-Rumpf (Funktion, Prozedur, Trigger) aus einem **fremden**
 * Dialekt stammt und deshalb nicht gerendert werden darf.
 *
 * d-migrate uebersetzt Routinen-Ruempfe nicht ([ADR 0054]): PL/pgSQL, T-SQL,
 * PL/SQL und MySQLs Prozedursprache teilen weder Blockstruktur noch
 * Fehlerbehandlung noch Variablendeklaration. Entschieden wird deshalb an der
 * **Herkunft**, nicht am Inhalt — anders als beim Sichten-Rumpf, den
 * [ViewQueryTransformer.assessPortability] inhaltlich beurteilt, weil
 * `SELECT`-Dialekte sich weit ueberlappen.
 *
 * Die Herkunft wird ueber [DatabaseDialect.fromString] aufgeloest, genau wie
 * dort: `postgres`, `pg`, `maria`, `mariadb`, `sqlite3`, `sqlserver` und jede
 * Grossschreibung meinen den Dialekt, den sie nennen. Der reine Zeichenvergleich,
 * der an den fuenf Dialekt-Stellen vorher stand, verwarf einen Rumpf, der fuer
 * genau dieses Ziel geschrieben war: `source_dialect: postgres` gegen ein
 * PostgreSQL-Ziel fiel mit `E053` weg, `source_dialect: postgresql` nicht.
 *
 * Ein Rumpf **ohne** Herkunft gilt als fuer das Ziel geschrieben — das ist die
 * dokumentierte Art, eine Routine von Hand zu fuehren, und ihn abzulehnen
 * machte jedes handgeschriebene Schema unbrauchbar. Ein **unbekannter** Wert
 * bleibt dagegen fremd: geraten wird nicht.
 */
object RoutineBodyOrigin {

    fun isForeign(sourceDialect: String?, target: DatabaseDialect): Boolean {
        if (sourceDialect == null) return false
        return runCatching { DatabaseDialect.fromString(sourceDialect) }.getOrNull() != target
    }
}
