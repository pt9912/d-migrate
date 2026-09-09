package dev.dmigrate.driver

/**
 * Was **ein** Zieldialekt an einem View-Rumpf nicht versteht, wie er ihn
 * umschreibt, und welche Funktionsnamen er kennt.
 *
 * Bis hierher lag das als `when (targetDialect)` in `ViewQueryTransformer` —
 * in einem Modul, das keinen der fünf Dialekte besitzt. Jeder neue Dialekt
 * erweiterte damit eine geteilte Klasse, statt sein Wissen mitzubringen.
 *
 * **Was hier NICHT hingehoert:** Regeln, die von der **Quelle** handeln statt
 * vom Ziel. MySQLs Backticks und T-SQLs Klammern sind fuer *jeden* anderen
 * Dialekt unlesbar; sie stuenden sonst viermal gleichlautend da. Solche
 * Marker bleiben in der Huelle ([ViewQueryTransformer]).
 */
interface ViewPortabilityRules {

    /** Der Dialekt, fuer den diese Regeln gelten. */
    val dialect: DatabaseDialect

    /**
     * Die Marker, die **dieser** Zieldialekt am Rumpf findet — leer, wenn er
     * ihn versteht.
     */
    fun markers(context: ViewPortabilityContext): List<String>

    /**
     * Die Umschreibregeln des Dialekts. Leer heisst: Rumpf passiert
     * unveraendert, und was der Dialekt nicht kennt, meldet [markers].
     */
    fun rules(): List<ViewQueryRule> = emptyList()

    /**
     * Die Funktionsnamen, die der Dialekt kennt. Alles andere in einem
     * dialektfremden Rumpf gilt als verdaechtig (W111).
     */
    fun knownFunctions(): Set<String> = ViewQueryFunctionSets.BASELINE
}

/**
 * Der Rumpf, wie die Regeln ihn brauchen: als Token, mit der Herkunft und
 * einer Sicht ohne Zeichenketten.
 */
class ViewPortabilityContext(
    val tokens: List<ViewQueryToken>,
    val sourceDialect: String?,
    /** Ob Quelle und Ziel verschiedene Dialekte sind. */
    val crossDialect: Boolean,
) {

    /**
     * Der Rumpf ohne Zeichenketten-Inhalte. Ein `::` oder `||` **in** einem
     * Literal ist kein Cast und keine Verkettung; ohne diese Sicht meldete
     * jede Adresse mit `||` im Text eine Unportabilitaet.
     */
    val codeOnly: String by lazy {
        tokens.joinToString("") { if (it.type == ViewQueryTokenType.STRING) " " else it.text }
    }

    /** Ob der Rumpf aus [dialect] stammt — Aliasnamen normalisiert. */
    fun sourceIs(dialect: DatabaseDialect): Boolean =
        sourceDialect != null && runCatching { DatabaseDialect.fromString(sourceDialect!!) }.getOrNull() == dialect
}
