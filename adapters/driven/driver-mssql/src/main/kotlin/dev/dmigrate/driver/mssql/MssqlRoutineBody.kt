package dev.dmigrate.driver.mssql

/**
 * Schneidet den Rumpf aus einer gespeicherten T-SQL-Routinendefinition.
 *
 * `sys.sql_modules.definition` haelt die vollstaendige `CREATE`-Anweisung. Das
 * neutrale Modell trennt dagegen Signatur und Rumpf: `parameters`, `returns`,
 * `table`/`event`/`timing` stehen als eigene Felder daneben — beim Trigger sogar
 * als Pflichtfelder. Der Rumpf ist deshalb das, was hinter dem einleitenden
 * `AS` steht.
 *
 * Gesucht wird das erste `AS` **auf oberster Ebene**. Ein blosses `indexOf`
 * genuegt dafuer nicht: T-SQL traegt `AS` in Zeichenketten, in Kommentaren, in
 * geklammerten Bezeichnern und innerhalb der Parameterliste. Der Scanner
 * ueberspringt diese vier und zaehlt die Klammertiefe mit.
 *
 * Findet er keins, gibt er `null` zurueck — der Aufrufer meldet das, statt
 * einen falschen Rumpf abzulegen.
 */
internal object MssqlRoutineBody {

    fun extract(definition: String): String? {
        val at = topLevelAsIndex(definition) ?: return null
        return definition.substring(at).trim().ifEmpty { null }
    }

    private fun topLevelAsIndex(sql: String): Int? {
        var i = 0
        var depth = 0
        while (i < sql.length) {
            val skipped = skipNonCode(sql, i)
            if (skipped > i) {
                i = skipped
                continue
            }
            when {
                sql[i] == '(' -> depth++
                sql[i] == ')' -> depth--
                depth == 0 && isAsKeywordAt(sql, i) -> return i + 2
            }
            i++
        }
        return null
    }

    /**
     * Das Ende des Kommentars, Literals oder geklammerten Bezeichners an [i],
     * oder [i] selbst, wenn dort keins beginnt.
     */
    private fun skipNonCode(sql: String, i: Int): Int = when {
        sql.startsWith("--", i) -> sql.indexOf('\n', i).takeIf { it >= 0 }?.plus(1) ?: sql.length
        sql.startsWith("/*", i) -> sql.indexOf("*/", i).takeIf { it >= 0 }?.plus(2) ?: sql.length
        sql[i] == '\'' -> skipQuoted(sql, i, '\'')
        sql[i] == '[' -> skipQuoted(sql, i, ']')
        else -> i
    }

    /** Ueberspringt ein Literal bzw. einen geklammerten Bezeichner samt Verdopplungs-Escape. */
    private fun skipQuoted(sql: String, start: Int, closing: Char): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == closing) {
                // `''` bzw. `]]` ist das entwertete Zeichen, kein Ende.
                if (i + 1 < sql.length && sql[i + 1] == closing) i += 2 else return i + 1
            } else {
                i++
            }
        }
        return sql.length
    }

    private fun isAsKeywordAt(sql: String, i: Int): Boolean {
        if (i + 2 > sql.length) return false
        if (!sql.regionMatches(i, "AS", 0, 2, ignoreCase = true)) return false
        val before = if (i == 0) ' ' else sql[i - 1]
        val after = if (i + 2 >= sql.length) ' ' else sql[i + 2]
        return !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
    }
}
