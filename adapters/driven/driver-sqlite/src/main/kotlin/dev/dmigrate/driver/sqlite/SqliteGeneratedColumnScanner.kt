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
        val body = tableBody(createSql) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (item in topLevelItems(body)) {
            val name = leadingIdentifier(item) ?: continue
            val expression = generatedExpression(item) ?: continue
            result[name] = expression
        }
        return result
    }

    /** Der Inhalt der Spaltenliste — zwischen der ersten Klammer und ihrem Partner. */
    private fun tableBody(createSql: String): String? {
        val open = createSql.indexOf('(').takeIf { it >= 0 } ?: return null
        // Der Helfer beginnt bei Tiefe 1 und will deshalb den Index NACH der
        // oeffnenden Klammer; zurueck gibt er den Index DER schliessenden.
        val close = SqliteDdlScanning.matchingParenEnd(createSql, open + 1) ?: return null
        return createSql.substring(open + 1, close)
    }

    /** Die Glieder der obersten Ebene, an Kommas getrennt. */
    private fun topLevelItems(body: String): List<String> {
        val items = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '\'', '"', '`' -> { i = SqliteDdlScanning.skipQuoted(body, i); continue }
                '[' -> { i = SqliteDdlScanning.skipBracketIdentifier(body, i); continue }
                '(' -> {
                    i = SqliteDdlScanning.matchingParenEnd(body, i + 1)?.plus(1) ?: body.length
                    continue
                }
                ',' -> {
                    items += body.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        items += body.substring(start)
        return items.map { it.trim() }.filter { it.isNotEmpty() }
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
