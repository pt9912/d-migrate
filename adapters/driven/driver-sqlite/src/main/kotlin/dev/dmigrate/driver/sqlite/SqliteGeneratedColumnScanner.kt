package dev.dmigrate.driver.sqlite

/**
 * Der Berechnungsausdruck einer generierten Spalte, aus `sqlite_master.sql`.
 *
 * **Warum aus dem Text und nicht aus einem Pragma.** SQLite meldet ueber
 * `PRAGMA table_xinfo`, **dass** eine Spalte generiert ist und ob gespeichert
 * oder virtuell — den Ausdruck meldet es nirgends. Er steht nur in der
 * abgelegten `CREATE TABLE`-Anweisung, und zwar wortgleich so, wie sie
 * geschrieben wurde: anders als PostgreSQL und MySQL normalisiert SQLite den
 * Text nicht.
 *
 * Gelesen wird deshalb mit demselben klammer- und quote-sicheren Werkzeug wie
 * die CHECK-Ausdruecke ([SqliteDdlScanning]), nicht mit einem Regex — ein
 * Ausdruck darf Klammern, Zeichenketten und Bezeichner in Anfuehrungszeichen
 * enthalten.
 */
internal object SqliteGeneratedColumnScanner {

    private const val AS_KEYWORD = "AS"

    /**
     * Ausdruecke je Spaltenname, aus der `CREATE TABLE`-Anweisung.
     *
     * Gelesen werden nur die Glieder der Spaltenliste — also die oberste
     * Klammerebene. Ein `AS (…)` tiefer drin gehoert zu etwas anderem.
     */
    fun expressionsOf(createSql: String): Map<String, String> {
        val body = SqliteDdlScanning.tableBody(createSql) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (item in SqliteDdlScanning.topLevelItems(body)) {
            val name = leadingIdentifier(item) ?: continue
            val expression = generatedExpression(item) ?: continue
            result[name] = expression
        }
        return result
    }

    /** Der Spaltenname am Anfang eines Glieds, oder `null` bei einem Tabellen-Constraint. */
    private fun leadingIdentifier(item: String): String? {
        val text = item.trimStart()
        if (text.isEmpty()) return null
        return when (text.first()) {
            '"', '`' -> {
                val end = SqliteDdlScanning.skipQuoted(text, 0)
                SqliteDdlScanning.unquoteIdentifier(text.substring(0, end))
            }
            '[' -> {
                val end = SqliteDdlScanning.skipBracketIdentifier(text, 0)
                SqliteDdlScanning.unquoteIdentifier(text.substring(0, end))
            }
            else -> {
                val word = text.takeWhile { it.isLetterOrDigit() || it == '_' }
                word.takeIf { it.isNotEmpty() && !isTableConstraintKeyword(it) }
            }
        }
    }

    /** Woerter, mit denen ein Tabellen-Constraint beginnt — kein Spaltenname. */
    private fun isTableConstraintKeyword(word: String): Boolean = word.uppercase() in setOf(
        "CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN",
    )

    /** Der Ausdruck hinter `AS (` in diesem Glied, oder `null`. */
    private fun generatedExpression(item: String): String? {
        var i = 0
        while (i < item.length) {
            val afterComment = SqliteDdlScanning.skipComment(item, i)
            if (afterComment > i) {
                i = afterComment
                continue
            }
            when (item[i]) {
                '\'', '"', '`' -> { i = SqliteDdlScanning.skipQuoted(item, i); continue }
                '[' -> { i = SqliteDdlScanning.skipBracketIdentifier(item, i); continue }
                '(' -> { i = SqliteDdlScanning.matchingParenEnd(item, i + 1)?.plus(1) ?: item.length; continue }
            }
            if (SqliteDdlScanning.isKeywordAt(item, i, AS_KEYWORD)) {
                val open = item.indexOf('(', i + AS_KEYWORD.length)
                if (open >= 0 && item.substring(i + AS_KEYWORD.length, open).isBlank()) {
                    val close = SqliteDdlScanning.matchingParenEnd(item, open + 1) ?: return null
                    return item.substring(open + 1, close).trim()
                }
            }
            i++
        }
        return null
    }
}
