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
 * einen falschen Rumpf abzulegen. Dasselbe gilt fuer eine Optionsklausel
 * ([hasOptionsClause]): sie steht vor dem `AS` und faellt beim Schnitt weg.
 */
internal object MssqlRoutineBody {

    /**
     * Ob die Definition eine `WITH`-Optionsklausel vor dem Rumpf traegt.
     *
     * `WITH SCHEMABINDING`, `WITH EXECUTE AS OWNER`, `WITH RETURNS NULL ON NULL
     * INPUT` und Verwandte stehen zwischen Signatur und `AS` — und damit vor
     * dem Schnitt. Das neutrale Modell hat kein Feld dafuer, also gingen sie
     * beim Zurueckschreiben verloren: eine schemagebundene Funktion verloere
     * ihre Bindung, und die indizierte Sicht darauf liesse sich nicht mehr
     * anlegen. `WITH EXECUTE AS` wuerde zusaetzlich den Schnitt selbst
     * verschieben, weil sein `AS` das erste auf oberster Ebene ist.
     *
     * Ein `WITH` **hinter** dem `AS` ist eine CTE im Rumpf und zaehlt nicht.
     */
    fun hasOptionsClause(definition: String): Boolean =
        firstTopLevelKeyword(definition, listOf("WITH", "AS"))?.first == "WITH"

    fun extract(definition: String): String? {
        val at = topLevelAsIndex(definition) ?: return null
        // Ein abschliessendes Semikolon beendet die `CREATE`-Anweisung und
        // gehoert nicht zum Rumpf. Bliebe es stehen, wuechse der Rumpf bei
        // jedem Reverse-Generate-Umlauf um ein weiteres `;`: SQL Server legt
        // in `sys.sql_modules` den Text ab, wie er gesendet wurde.
        return definition.substring(at).trim().trimEnd(';', ' ', '\t', '\n', '\r').ifEmpty { null }
    }

    private fun topLevelAsIndex(sql: String): Int? =
        firstTopLevelKeyword(sql, listOf("AS"))?.let { (keyword, at) -> at + keyword.length }

    /** Das erste der [keywords] auf oberster Klammerebene, mit seinem Startindex. */
    private fun firstTopLevelKeyword(sql: String, keywords: List<String>): Pair<String, Int>? {
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
                depth == 0 -> keywords.firstOrNull { isKeywordAt(sql, i, it) }?.let { return it to i }
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

    private fun isKeywordAt(sql: String, i: Int, keyword: String): Boolean {
        val end = i + keyword.length
        if (end > sql.length) return false
        if (!sql.regionMatches(i, keyword, 0, keyword.length, ignoreCase = true)) return false
        val before = if (i == 0) ' ' else sql[i - 1]
        val after = if (end >= sql.length) ' ' else sql[end]
        return !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
    }
}
