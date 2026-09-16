package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.RawSqlSkeleton.Companion.CLOSE
import dev.dmigrate.core.diff.RawSqlSkeleton.Companion.IDENTIFIER
import dev.dmigrate.core.diff.RawSqlSkeleton.Companion.LITERAL

/**
 * Die lexikalischen Bausteine, die die Schreibweise-Faltung von
 * `schema compare` teilt (ADR 0056) — an **einer** Stelle, damit
 * Ausdrucks- und Sichten-Kanonisierer dasselbe unter „Name" und
 * „Leerraum" verstehen.
 */
internal object SqlLexis {

    /**
     * Leerraum, wie PostgreSQL ihn liest: nur ASCII. Ein geschuetztes
     * Leerzeichen (U+00A0) gehoert dort zum **Namen**; es zu falten hiesse,
     * einen Namen zu zerteilen.
     */
    val WHITESPACE = Regex("[ \\t\\n\\x0B\\f\\r]+")

    /** Dieselben Zeichen wie [WHITESPACE], als Menge. */
    const val WHITESPACE_CHARS = " \t\n\u000B\u000C\r"

    /**
     * Die Zeichen eines Namens, als Inhalt einer Regex-Zeichenklasse: ASCII-
     * Buchstaben, Ziffern, `_`, `$` — und, wie in PostgreSQL, **jedes**
     * Zeichen ausserhalb von ASCII (`maß`, `señor`). Ausgenommen sind die
     * Platzhalterzeichen des Geruests.
     */
    const val NAME_CHARS = "A-Za-z0-9_$\\x{80}-\\x{DFFF}\\x{E003}-\\x{10FFFF}"

    /** Ob [char] zu einem Namen gehoert — dieselbe Menge wie [NAME_CHARS]. */
    fun isNameChar(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '$' ||
            (char.code >= ASCII_LIMIT && char != LITERAL && char != IDENTIFIER && char != CLOSE)

    /** Vor einem Wort: kein Namenszeichen davor. Ersetzt `\b`, das nur ASCII kennt. */
    const val WORD_START = "(?<![$NAME_CHARS])"

    /** Nach einem Wort: kein Namenszeichen dahinter. */
    const val WORD_END = "(?![$NAME_CHARS])"

    /** Das Wort, das in [text] unmittelbar vor [end] endet — leer, wenn dort keines endet. */
    fun wordBefore(text: CharSequence, end: Int): String {
        var start = end
        while (start > 0 && isNameChar(text[start - 1])) start--
        return text.substring(start, end)
    }

    private const val ASCII_LIMIT = 0x80
}

/**
 * Schluesselwoerter und reservierte Woerter, deren **unquotierte** Form etwas
 * anderes ist als ein Spaltenbezug — die Vereinigung ueber PostgreSQL, MySQL,
 * SQL Server, Oracle und SQLite, soweit sie in einem Ausdruck oder einer
 * Abfrage vorkommen koennen.
 *
 * Zwei Gruppen: Woerter, die als **Wert oder Funktion ohne Klammern** gelesen
 * werden (`user`, `current_date`, `null`, `true`, Oracles `sysdate` und
 * `level`), und Woerter, die die Regeln dieses Pakets als **Syntax** lesen
 * (`and`, `or`, `not`, `between`, `case`, `in`, `like` …). Ein quotierter
 * Bezeichner mit einem dieser Namen wird nicht entpackt: `"user"` ist eine
 * Spalte, `user` die Funktion `current_user`.
 */
internal object SqlKeywords {

    /** Woerter, die ohne Klammern einen Wert liefern — je Dialekt verschieden viele. */
    private val VALUES = setOf(
        "null", "true", "false", "unknown", "default",
        "user", "current_user", "session_user", "system_user", "current_role",
        "current_catalog", "current_schema", "current_path",
        "current_date", "current_time", "current_timestamp", "localtime", "localtimestamp",
        "utc_date", "utc_time", "utc_timestamp",
        "sysdate", "systimestamp", "uid", "rownum", "rowid", "level", "ora_rowscn",
        "sessiontimezone", "dbtimezone", "oid", "_rowid_", "identitycol", "rowguidcol",
    )

    /** Woerter, die ein Ausdruck oder eine Abfrage als Syntax traegt. */
    private val SYNTAX = setOf(
        "and", "or", "not", "xor", "is", "isnull", "notnull", "in", "like", "ilike", "rlike",
        "regexp", "glob", "match", "similar", "escape", "between", "symmetric", "asymmetric",
        "case", "when", "then", "else", "end", "exists", "any", "some", "all", "distinct",
        "select", "from", "where", "with", "values", "as", "on", "using", "join", "union",
        "intersect", "except", "minus", "cast", "array", "row", "collate", "div", "mod",
        "overlaps", "unique", "interval", "binary", "prior", "over", "filter", "within",
        "lateral", "order", "group", "by", "having", "limit", "offset", "fetch", "top", "into",
        "only", "table", "asc", "desc", "sounds", "at", "zone", "operator",
    )

    private val ALL = VALUES + SYNTAX

    /** Ob [word] eines der Schluesselwoerter ist — ohne Ruecksicht auf die Schreibweise. */
    fun isKeyword(word: String): Boolean = word.lowercase() in ALL

    /**
     * Die Woerter, hinter denen eine Klammer um einen einzelnen Namen **nicht**
     * zu einem Aufruf gehoert, sondern einen Operanden umschliesst
     * (`NOT (flag)`, `a = 1 AND (b)`).
     */
    fun precedesOperand(word: String): Boolean = word.lowercase() in OPERAND_PREFIXES

    private val OPERAND_PREFIXES = setOf(
        "and", "or", "not", "xor", "when", "then", "else", "case", "like", "ilike", "between",
    )
}
