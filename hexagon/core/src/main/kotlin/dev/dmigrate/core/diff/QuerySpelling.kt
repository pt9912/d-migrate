package dev.dmigrate.core.diff

/**
 * Die Dialekt-Schreibweise eines **Sichten-Rumpfs**, wie `schema compare` sie
 * gleichsetzt (ADR 0056; die Menge steht in `spec/cli-spec.md`).
 *
 * **Enger als beim Ausdruck.** Vereinheitlicht werden nur Quoting, Leerraum
 * und abschliessende Semikola. Klammern und Casts bleiben: in einem
 * Abfragetext gliedern Klammern Joins und Unterabfragen, und was die Struktur
 * betrifft — gewaehlte Spalten, `WHERE`-Klauseln, die Reihenfolge —, bleibt
 * ein Unterschied. Die Grenzfaelle stehen in `ViewQueryCanonicalisationTest`.
 *
 * Literale sind geschuetzt wie beim Ausdruck, und wo der Scanner den Text
 * nicht sicher abgrenzt, wird nicht gefaltet ([RawSqlSkeleton.of]).
 */
internal object QuerySpelling {

    /** Ob [left] und [right] sich nur in der Schreibweise unterscheiden. */
    fun equal(left: String?, right: String?): Boolean {
        if (left == null || right == null) return left == right
        if (left == right) return true
        val canonicalLeft = canonical(left) ?: return false
        val canonicalRight = canonical(right) ?: return false
        return canonicalLeft == canonicalRight
    }

    private fun canonical(sql: String): String? {
        val skeleton = RawSqlSkeleton.of(sql) ?: return null
        val folded = skeleton.text
            .replace(WHITESPACE, " ")
            .replace(PUNCTUATION_GAP, "$1")
            .replace(TRAILING_SEMICOLA, "")
            .trim()
        return skeleton.restore(folded)
    }

    private val WHITESPACE = Regex("\\s+")

    /** Leerraum um Komma, Gleichheitszeichen und Klammern. */
    private val PUNCTUATION_GAP = Regex("\\s*([,=()])\\s*")

    /**
     * Ein **oder mehrere** abschliessende Semikola, mit etwaigem Leerraum
     * dazwischen und danach. Der Server haengt seines an den gespeicherten
     * Text; trug die angewendete DDL schon eines, stehen dort zwei (gemessen
     * an einem SQL-Server-Reverse: `…customer_id;;`). Nur **abschliessende**:
     * ein `;` zwischen zwei Anweisungen bleibt Unterschied, und eines in einem
     * Literal steht ohnehin im Platzhalter.
     */
    private val TRAILING_SEMICOLA = Regex("(?:\\s*;)+\\s*$")
}
