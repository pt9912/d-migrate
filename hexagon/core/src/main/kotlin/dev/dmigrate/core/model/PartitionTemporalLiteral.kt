package dev.dmigrate.core.model

/**
 * Zerlegt ein temporales Partitionsgrenzen-Literal in Datum, Zeit und
 * Zeitzonen-Offset.
 *
 * Das neutrale Modell traegt Grenzen fertig gequotet (ADR 0019); wer sie fuer
 * einen Dialekt rendert, muss den Zeitanteil oft anfassen — MySQL entfernt
 * einen UTC-Offset, weil `DATETIME` keine Zone kennt, und Oracle braucht das
 * Datum in einer expliziten `TO_DATE`-Form, weil ein blanker String an
 * `NLS_DATE_FORMAT` haengt.
 *
 * Beide brauchen dieselbe Zerlegung, und sie ist **strukturell**, nicht per
 * String-Chirurgie: der Offset sitzt INNERHALB der zeit-tragenden Gruppe und
 * wird nur erkannt, wenn eine Zeitkomponente vorausgeht. Ein unverankertes
 * Muster frass sonst das `-DD` einer reinen Datumsgrenze als Phantom-Zone und
 * verschob die Grenze still (AP6-Review #1).
 *
 * Gemeinsame Quelle statt zweier gleichlautender Kopien — dieselbe Erwaegung
 * wie bei `PartitionBoundScanner`, der aus demselben Grund zusammengelegt
 * wurde.
 *
 * Liegt im **Modell**, nicht im Adapter: das Format einer Grenze legt das
 * neutrale Modell fest (ADR 0019), und ausser den Renderern liest es auch die
 * Fingerabdruck-Projektion in der Anwendungsschicht — die keinen Adapter
 * sehen darf.
 */
object PartitionTemporalLiteral {

    /** [time] und [offset] sind `null`, wo das Literal sie nicht traegt. */
    data class Parts(
        val date: String,
        val time: String?,
        val offset: String?,
    ) {
        /** Datum plus Zeit ohne Offset, wie es ein zonenloser Zieltyp braucht. */
        val instant: String get() = if (time == null) date else "$date $time"

        /** Ob der Zeitanteil Sekundenbruchteile traegt (`TIMESTAMP` statt `DATE`). */
        val hasFraction: Boolean get() = time?.contains('.') == true
    }

    /** Die Zerlegung, oder `null`, wenn das Literal keine erkennbare Temporalform hat. */
    fun parse(literal: String): Parts? {
        val match = TEMPORAL_LITERAL.matchEntire(unwrapSingleQuotes(literal)) ?: return null
        return Parts(
            date = match.groupValues[DATE_GROUP],
            time = match.groupValues[TIME_GROUP].ifEmpty { null },
            offset = match.groupValues[OFFSET_GROUP].ifEmpty { null },
        )
    }

    /** `Z`/`z` (Zulu) und die expliziten +00-Schreibweisen sind UTC. */
    fun isUtcOffset(offset: String): Boolean =
        offset.equals("Z", ignoreCase = true) || offset in UTC_OFFSETS

    fun isSingleQuoted(s: String): Boolean =
        s.length >= 2 && s.first() == '\'' && s.last() == '\''

    fun unwrapSingleQuotes(s: String): String =
        if (isSingleQuoted(s)) s.substring(1, s.length - 1) else s

    /**
     * `date[ T|t]time[offset]`, vollstaendig verankert (`^…$`). Akzeptiert
     * Leerzeichen, `T` und `t` als ISO-8601-Trenner.
     */
    private val TEMPORAL_LITERAL = Regex(
        "^(\\d{4}-\\d{2}-\\d{2})(?:[ Tt](\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?)([+-]\\d{2}(?::?\\d{2})?|[Zz])?)?$",
    )
    private const val DATE_GROUP = 1
    private const val TIME_GROUP = 2
    private const val OFFSET_GROUP = 3
    private val UTC_OFFSETS = setOf("+00", "+0000", "+00:00", "-00", "-0000", "-00:00")
}
