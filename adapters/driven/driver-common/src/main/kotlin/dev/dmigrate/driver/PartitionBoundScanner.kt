package dev.dmigrate.driver

/**
 * Quote- und klammer-bewusstes Top-Level-Splitting für Partitionsgrenzen-
 * Ausdrücke. Gemeinsamer Scanner für den PostgreSQL-`pg_get_expr`-Output und
 * MySQLs `information_schema.PARTITIONS`-Form (ADR 0019/0020).
 *
 * Beide Dialekte trennen Bound-Tupel/Wertelisten per Komma auf Top-Level, müssen
 * dabei aber Kommata schützen, die innerhalb eines String-Literals (`'…'`,
 * MySQL zusätzlich `` `…` ``) oder einer Klammergruppe (`(…)`, PG zusätzlich
 * `[…]` für Array-Bounds) stehen.
 *
 * Der Scanner ist die **Vereinigung** beider Formen — Quote-Zeichen `'` und
 * `` ` ``, Klammern `(`/`[` … `)`/`]`. Da PG nie Backticks und MySQL nie eckige
 * Klammern in diesen Ausdrücken führt, ist die Vereinigung pro Dialekt
 * verhaltensgleich zum vorherigen dialekt-lokalen Fork (AP6-Review P3 #9).
 *
 * Ein verdoppeltes `''`/`` `` `` (escaptes Quote) schließt das Quote und öffnet
 * es sofort wieder — die Quote-Bilanz bleibt symmetrisch, und der einzige
 * Zwischenraum (zwischen den beiden Quote-Zeichen) ist leer, enthält also nie
 * ein Komma. Das Split-Ergebnis ist damit identisch zu einer Toggle-Zählung.
 */
object PartitionBoundScanner {

    /** Komma-Trennung auf Top-Level (respektiert `'`-/`` ` ``-Quotes + `()`/`[]`). */
    fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        val token = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (c in s) {
            when {
                quote != null -> { token.append(c); if (c == quote) quote = null }
                c == '\'' || c == '`' -> { quote = c; token.append(c) }
                c == '(' || c == '[' -> { depth++; token.append(c) }
                c == ')' || c == ']' -> { depth--; token.append(c) }
                c == ',' && depth == 0 -> { addTrimmed(parts, token); token.clear() }
                else -> token.append(c)
            }
        }
        addTrimmed(parts, token)
        return parts
    }

    private fun addTrimmed(parts: MutableList<String>, token: StringBuilder) {
        val trimmed = token.toString().trim()
        if (trimmed.isNotEmpty()) parts.add(trimmed)
    }
}
