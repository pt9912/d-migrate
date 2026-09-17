package dev.dmigrate.driver.sqlite

/**
 * Die Namensvergabe fuer SQLite-Constraints beim Reverse — **schemaweit**.
 *
 * SQLite fuehrt im Katalog keine Constraint-Namen: `PRAGMA foreign_key_list`
 * nummeriert je Tabelle durch, und ein UNIQUE-Autoindex heisst
 * `sqlite_autoindex_<tabelle>_<n>`. Der Reverse vergab daraufhin `fk_0`,
 * `uq_0` **je Tabelle** — in einem Schema mit zwei Tabellen also zweimal
 * denselben Namen. SQL Server und PostgreSQL verlangen Constraint-Namen
 * schemaweit eindeutig und lehnten die erzeugte DDL ab (`Msg 2714`,
 * `relation "uq_0" already exists`, beides in der Compare-Matrix gemessen).
 *
 * Diese Registry vergibt deshalb ueber alle Tabellen hinweg:
 *
 * - **Ein echter Name gewinnt.** Er steht im `CREATE TABLE`-Text und wird
 *   vorab reserviert — ein gebildeter Name weicht ihm aus, auch wenn er in
 *   einer spaeter gelesenen Tabelle steht.
 * - **Ein gebildeter Name folgt dem Vorbild des Generators**
 *   (`fk_<tabelle>_<spalte>`, `DdlGenerationSupport`), ist auf die kleinste
 *   Bezeichnergrenze der Ziele gekuerzt und bekommt bei einer Kollision einen
 *   Zaehler.
 * - **Die Vergabe ist deterministisch:** dieselbe Datenbank ergibt in zwei
 *   Reverses dieselben Namen, weil Tabellen und Klauseln in fester Reihenfolge
 *   gelesen werden.
 *
 * Verglichen wird ohne Ruecksicht auf Gross-/Kleinschreibung: PostgreSQL faltet
 * einen unquotierten Namen, und zwei Namen, die sich nur darin unterscheiden,
 * waeren dort derselbe.
 */
internal class SqliteConstraintNames(reserved: Collection<String>) {

    private val taken: MutableSet<String> = reserved.mapTo(mutableSetOf()) { it.lowercase() }

    /** Ein Name aus dem DDL-Text: er gilt, wie er dasteht. */
    fun real(name: String): String {
        taken += name.lowercase()
        return name
    }

    /**
     * Ein gebildeter Name aus [parts] (`fk`, Tabelle, Spalten …), gekuerzt und
     * bei Kollision gezaehlt.
     */
    fun generated(vararg parts: String): String {
        val base = parts.joinToString("_") { it.replace(' ', '_') }.take(MAX_IDENTIFIER)
        if (base.lowercase() !in taken) {
            taken += base.lowercase()
            return base
        }
        var counter = 2
        while (true) {
            val suffix = "_$counter"
            val candidate = base.take(MAX_IDENTIFIER - suffix.length) + suffix
            if (candidate.lowercase() !in taken) {
                taken += candidate.lowercase()
                return candidate
            }
            counter++
        }
    }

    private companion object {
        /**
         * Die kleinste Bezeichnergrenze der fuenf Ziele: PostgreSQL kuerzt bei
         * 63 Zeichen (MySQL 64, SQL Server und Oracle 128, SQLite kennt keine).
         * Ein hier gebildeter Name soll auf jedem Ziel unveraendert ankommen —
         * gekuerzt wuerden sonst zwei verschiedene Namen dort derselbe.
         */
        const val MAX_IDENTIFIER = 63
    }
}
