package dev.dmigrate.core.diff.migration.overlay

import java.math.BigDecimal

/**
 * Prueft eine LIST-nach-RANGE-Zuordnung nach, statt sie zu glauben.
 *
 * Das ist der Grund, warum der LIST-Fall den Namensfall an Wert uebertrifft:
 * bei Namen ist die Angabe des Anwenders die einzige Quelle, hier nicht. Eine
 * Wertemenge und eine RANGE-Obergrenze sagen zusammen etwas Nachpruefbares —
 * naemlich in welche Partition eine Zeile geroutet wird —, und eine Zuordnung,
 * die falsch routen wuerde, kommt nicht durch.
 *
 * Geprueft wird dreierlei:
 *
 * 1. **Ueberschneidungsfreiheit.** Ein Wert darf nicht in zwei Mengen stehen;
 *    sonst gaebe es keine eindeutige Zielpartition.
 * 2. **Keine Verschraenkung.** In der Ordnung der Werte muss jede Menge einen
 *    zusammenhaengenden Lauf belegen. `(1,2), (3,4)` tut das,
 *    `('DE','FR'), ('US','CA')` nicht — dort liegt `CA` vor `DE`, die Mengen
 *    verschraenken sich, und RANGE kann das nicht ausdruecken.
 * 3. **Die Grenzen routen richtig.** Fuer jede Menge muss
 *    `max(Menge) < Grenze <= min(naechste Menge)` gelten. Die linke Haelfte
 *    haelt die eigenen Zeilen drin (`VALUES LESS THAN` ist exklusiv), die
 *    rechte haelt die der naechsten Menge draussen. Die letzte Menge hat nur
 *    die linke Bedingung.
 *
 * **Die Ordnung.** Lassen sich *alle* beteiligten Literale als Zahl lesen,
 * wird numerisch verglichen — sonst lexikografisch. Das ist eine Entscheidung
 * und keine Ableitung: `'10'` und `'9'` liegen numerisch anders als
 * alphabetisch, und wer Zahlen als Text partitioniert, meint fast immer die
 * Zahl. Gemischt (`'1'` und `'x'`) faellt auf lexikografisch zurueck, weil
 * eine numerische Ordnung dort gar nicht existiert.
 */
object PartitionMappingVerifier {

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
    }

    /** Ein Eintrag, wie ihn die Pruefung braucht — losgeloest von der Overlay-Form. */
    data class Mapping(val partition: String, val values: List<String>, val upperBound: String)

    fun verify(mappings: List<Mapping>): Result {
        if (mappings.isEmpty()) return Result.Valid
        val empty = mappings.firstOrNull { it.values.isEmpty() }
        if (empty != null) {
            return Result.Invalid("partition '${empty.partition}' maps an empty value set")
        }

        val comparator = comparatorFor(mappings.flatMap { it.values } + mappings.map { it.upperBound })

        val duplicate = duplicateValue(mappings)
        if (duplicate != null) {
            return Result.Invalid(
                "value '$duplicate' appears in more than one partition; the target partition would be ambiguous",
            )
        }

        // Nach dem kleinsten Wert ordnen — das ist die Reihenfolge, in der die
        // Mengen liegen muessen, wenn sie sich nicht verschraenken.
        val ordered = mappings.sortedWith(compareBy(comparator) { it.values.min(comparator) })
        interleaving(ordered, comparator)?.let { return Result.Invalid(it) }
        return boundsRoute(ordered, comparator)?.let { Result.Invalid(it) } ?: Result.Valid
    }

    /**
     * Die Zuordnungen in der Reihenfolge, in der ihre Mengen liegen.
     *
     * Nur sinnvoll fuer eine Zuordnung, die [verify] angenommen hat — bei einer
     * verschraenkten gibt es keine Reihenfolge, die etwas bedeutet.
     */
    fun inOrder(mappings: List<Mapping>): List<Mapping> {
        if (mappings.isEmpty()) return emptyList()
        val comparator = comparatorFor(mappings.flatMap { it.values } + mappings.map { it.upperBound })
        return mappings.sortedWith(compareBy(comparator) { it.values.min(comparator) })
    }

    private fun duplicateValue(mappings: List<Mapping>): String? {
        val seen = mutableSetOf<String>()
        for (mapping in mappings) {
            for (value in mapping.values) {
                if (!seen.add(value)) return value
            }
        }
        return null
    }

    /** Ueberlappt eine Menge die naechste, ist die Reihenfolge nicht herstellbar. */
    private fun interleaving(ordered: List<Mapping>, comparator: Comparator<String>): String? {
        for (index in 0 until ordered.size - 1) {
            val current = ordered[index]
            val next = ordered[index + 1]
            if (comparator.compare(current.values.max(comparator), next.values.min(comparator)) >= 0) {
                return "the value sets of partitions '${current.partition}' and '${next.partition}' interleave; " +
                    "RANGE bounds cannot express that ordering"
            }
        }
        return null
    }

    private fun boundsRoute(ordered: List<Mapping>, comparator: Comparator<String>): String? {
        for ((index, mapping) in ordered.withIndex()) {
            val ownMax = mapping.values.max(comparator)
            if (comparator.compare(mapping.upperBound, ownMax) <= 0) {
                return "the upper bound '${mapping.upperBound}' of partition '${mapping.partition}' does not " +
                    "cover its own value '$ownMax'; those rows would land in a later partition"
            }
            val next = ordered.getOrNull(index + 1) ?: continue
            val nextMin = next.values.min(comparator)
            if (comparator.compare(mapping.upperBound, nextMin) > 0) {
                return "the upper bound '${mapping.upperBound}' of partition '${mapping.partition}' reaches " +
                    "into '${next.partition}'; its value '$nextMin' would land in the wrong partition"
            }
        }
        return null
    }

    private fun comparatorFor(literals: List<String>): Comparator<String> {
        val numeric = literals.all { it.toBigDecimalOrNull() != null }
        return if (numeric) {
            compareBy { it.toBigDecimalOrNull() ?: BigDecimal.ZERO }
        } else {
            naturalOrder()
        }
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(trim()) }.getOrNull()

    private fun List<String>.min(comparator: Comparator<String>): String = minWith(comparator)

    private fun List<String>.max(comparator: Comparator<String>): String = maxWith(comparator)
}
