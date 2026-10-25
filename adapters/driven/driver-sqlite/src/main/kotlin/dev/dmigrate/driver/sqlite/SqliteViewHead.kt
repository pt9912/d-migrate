package dev.dmigrate.driver.sqlite

/**
 * Liest den Kopf einer Sicht aus `sqlite_master.sql`:
 * `CREATE [TEMP] VIEW [IF NOT EXISTS] [schema.]name [(spalten)] AS <abfrage>`.
 *
 * Von Hand gelesen, nicht per Regex: eine lange Spaltenliste liesse die
 * Regex-Engine rekursieren und mit einem `StackOverflowError` abbrechen.
 * Zwischen den Teilen duerfen Leerraum und Kommentare stehen; ein `AS` im
 * (zitierten) Namen oder in der Abfrage ist nicht gemeint.
 */
internal object SqliteViewHead {

    /** Die Abfrage hinter dem `AS` des Kopfes, `null` wenn [createSql] keinen Sichtkopf traegt. */
    fun query(createSql: String): String? {
        var i = skip(createSql, 0)
        i = keyword(createSql, i, "CREATE") ?: return null
        i = keyword(createSql, skip(createSql, i), "TEMPORARY")
            ?: keyword(createSql, skip(createSql, i), "TEMP") ?: i
        i = keyword(createSql, skip(createSql, i), "VIEW") ?: return null
        i = skip(createSql, i)
        i = ifNotExists(createSql, i)
        i = name(createSql, i) ?: return null
        i = skip(createSql, i)
        if (createSql.getOrNull(i) == '.') {
            i = name(createSql, skip(createSql, i + 1)) ?: return null
            i = skip(createSql, i)
        }
        if (createSql.getOrNull(i) == '(') {
            i = skip(createSql, columnListEnd(createSql, i) ?: return null)
        }
        val afterAs = keyword(createSql, i, "AS") ?: return null
        return createSql.substring(afterAs).trim()
    }

    private fun keyword(sql: String, i: Int, word: String): Int? =
        if (SqliteDdlScanning.isKeywordAt(sql, i, word)) i + word.length else null

    private fun ifNotExists(sql: String, start: Int): Int {
        val ifEnd = keyword(sql, start, "IF") ?: return start
        val notEnd = keyword(sql, skip(sql, ifEnd), "NOT") ?: return start
        val existsEnd = keyword(sql, skip(sql, notEnd), "EXISTS") ?: return start
        return skip(sql, existsEnd)
    }

    /** Index hinter Leerraum und Kommentaren ab [start]. */
    private fun skip(sql: String, start: Int): Int {
        var i = start
        while (i < sql.length) {
            val next = SqliteDdlScanning.skipComment(sql, i)
            i = when {
                next > i -> next
                sql[i].isWhitespace() -> i + 1
                else -> return i
            }
        }
        return i
    }

    /** Index hinter dem Namen bei [start] (zitiert oder nackt), `null` wenn dort keiner steht. */
    private fun name(sql: String, start: Int): Int? {
        val c = sql.getOrNull(start) ?: return null
        return when (c) {
            '"', '`', '\'' -> SqliteDdlScanning.skipQuoted(sql, start)
            '[' -> SqliteDdlScanning.skipBracketIdentifier(sql, start)
            else -> {
                var i = start
                while (i < sql.length && isBare(sql, i)) i++
                if (i == start) null else i
            }
        }
    }

    private fun isBare(sql: String, i: Int): Boolean {
        val c = sql[i]
        return !c.isWhitespace() && c !in ".(\"`'[" &&
            !sql.startsWith("--", i) && !sql.startsWith("/*", i)
    }

    /** Index hinter der schliessenden Klammer der Spaltenliste bei [open], `null` wenn sie nicht schliesst. */
    private fun columnListEnd(sql: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < sql.length) {
            val next = SqliteDdlScanning.skipComment(sql, i)
            i = when {
                next > i -> next
                sql[i] == '(' -> { depth++; i + 1 }
                sql[i] == ')' -> {
                    depth--
                    if (depth == 0) return i + 1
                    i + 1
                }
                sql[i] in "\"`'" -> SqliteDdlScanning.skipQuoted(sql, i)
                sql[i] == '[' -> SqliteDdlScanning.skipBracketIdentifier(sql, i)
                else -> i + 1
            }
        }
        return null
    }
}
