package dev.dmigrate.driver.sqlite

/**
 * Die Fremdschluessel-Klauseln eines SQLite-`CREATE TABLE`-Textes, mit ihrem
 * **Namen**.
 *
 * `PRAGMA foreign_key_list` nummeriert die Fremdschluessel einer Tabelle nur
 * durch (`id`); den Namen fuehrt SQLite ausschliesslich im abgelegten
 * DDL-Text. Ohne ihn hiess jeder Fremdschluessel jeder Tabelle `fk_0`, `fk_1`
 * … — und SQL Server lehnte die daraus erzeugte DDL ab, weil er
 * Constraint-Namen schemaweit eindeutig verlangt (`Msg 2714`, in der
 * Compare-Matrix gemessen).
 *
 * Gelesen werden **beide** Formen, die SQLite kennt:
 *
 * - die Klausel auf Tabellenebene:
 *   `[CONSTRAINT <name>] FOREIGN KEY (<spalten>) REFERENCES <tabelle> [(…)]`
 * - die Klausel an der Spalte:
 *   `<spalte> <typ> … [CONSTRAINT <name>] REFERENCES <tabelle> [(…)]`
 *
 * Der Generator von d-migrate schreibt fuer eine Spaltenklausel **keinen**
 * Namen ([SqliteColumnConstraintHelper]); eine von d-migrate angelegte
 * Datenbank traegt dort also nie einen. Ein Anwenderschema kann einen haben,
 * und dann gilt er.
 *
 * Der Scanner zerlegt den Rumpf der Anweisung in seine Glieder auf oberster
 * Ebene (Komma) — ein Glied ist entweder eine Tabellen-Klausel oder eine
 * Spaltendefinition. Das ist verlaesslicher als eine Suche nach Schluesselwoertern:
 * `REFERENCES` steht in beiden Formen, und nur die Stellung sagt, welche
 * vorliegt. Quotierungen und Kommentare ueberspringt [SqliteDdlScanning].
 */
internal object SqliteForeignKeyConstraintScanner {

    /** Eine Klausel: ihr Name (oder `null`), ihre Spalten und die Zieltabelle. */
    data class ForeignKeyClause(
        val name: String?,
        val columns: List<String>,
        val referencedTable: String,
    )

    fun scan(createSql: String): List<ForeignKeyClause> =
        SqliteDdlScanning.tableBody(createSql)
            ?.let { body -> SqliteDdlScanning.topLevelItems(body).mapNotNull(::clauseOf) }
            ?: emptyList()

    /** Die Klausel eines Gliedes, oder `null`, wenn es keinen Fremdschluessel traegt. */
    private fun clauseOf(item: String): ForeignKeyClause? {
        val references = keywordIndex(item, REFERENCES) ?: return null
        val leadingName = leadingConstraintName(item)
        val foreignKey = keywordIndex(item, FOREIGN_KEY)
        val referencedTable = identifierAfter(item, references + REFERENCES.length) ?: return null
        return if (foreignKey != null && foreignKey < references) {
            // Tabellenebene: die Spalten stehen in der Klammer hinter FOREIGN
            // KEY. Wie lang der Schluesselwort-Lauf im Text ist, sagt nur er
            // selbst: zwischen den beiden Woertern steht beliebiger Leerraum,
            // `FOREIGN_KEY.length` waere dort die falsche Stelle.
            val keywordEnd = SqliteDdlScanning.keywordRunEnd(item, foreignKey, FOREIGN_KEY) ?: return null
            val end = SqliteDdlScanning.parenGroupEnd(item, keywordEnd, 0) ?: return null
            val open = item.indexOf('(', keywordEnd)
            val columns = parseColumnList(item.substring(open + 1, end))
            if (columns.isEmpty()) null else ForeignKeyClause(leadingName, columns, referencedTable)
        } else {
            // Spaltenebene: das Glied beginnt mit dem Spaltennamen, und der
            // Name steht — wenn ueberhaupt — unmittelbar vor REFERENCES.
            val column = leadingIdentifier(item) ?: return null
            val name = SqliteDdlScanning.constraintNameBefore(item, references)
            ForeignKeyClause(name, listOf(column), referencedTable)
        }
    }

    /** `CONSTRAINT <name>` am Anfang eines Gliedes. */
    private fun leadingConstraintName(item: String): String? {
        if (!SqliteDdlScanning.isKeywordAt(item, 0, CONSTRAINT)) return null
        var index = CONSTRAINT.length
        while (index < item.length && item[index].isWhitespace()) index++
        return identifierAt(item, index)?.first
    }

    /** Der Index des Schluesselworts als ganzes Wort, ausserhalb von Quotierungen. */
    private fun keywordIndex(item: String, keyword: String): Int? {
        var index = 0
        while (index < item.length) {
            val afterComment = SqliteDdlScanning.skipComment(item, index)
            if (afterComment > index) {
                index = afterComment
                continue
            }
            index = when (item[index]) {
                '\'', '"', '`' -> SqliteDdlScanning.skipQuoted(item, index)
                '[' -> SqliteDdlScanning.skipBracketIdentifier(item, index)
                else -> {
                    if (SqliteDdlScanning.keywordRunEnd(item, index, keyword) != null) return index
                    index + 1
                }
            }
        }
        return null
    }

    private fun identifierAfter(item: String, from: Int): String? {
        var index = from
        while (index < item.length && item[index].isWhitespace()) index++
        return identifierAt(item, index)?.first
    }

    private fun leadingIdentifier(item: String): String? = identifierAt(item, 0)?.first

    /** Der Bezeichner an [start] samt seinem Ende, oder `null`. */
    private fun identifierAt(item: String, start: Int): Pair<String, Int>? {
        if (start >= item.length) return null
        return when (item[start]) {
            '"', '`', '\'' -> {
                val end = SqliteDdlScanning.skipQuoted(item, start)
                SqliteDdlScanning.unquoteIdentifier(item.substring(start, end)) to end
            }
            '[' -> {
                val end = SqliteDdlScanning.skipBracketIdentifier(item, start)
                SqliteDdlScanning.unquoteIdentifier(item.substring(start, end)) to end
            }
            else -> {
                var end = start
                while (end < item.length && isNameChar(item[end])) end++
                if (end == start) null else item.substring(start, end) to end
            }
        }
    }

    /** Die Spaltenliste einer Klammer, auf ihre fuehrenden Bezeichner reduziert. */
    private fun parseColumnList(body: String): List<String> =
        body.split(',').mapNotNull { entry -> identifierAt(entry.trim(), 0)?.first }

    private fun isNameChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_' || char == '$'

    private const val CONSTRAINT = "CONSTRAINT"
    private const val REFERENCES = "REFERENCES"
    private const val FOREIGN_KEY = "FOREIGN KEY"
}
