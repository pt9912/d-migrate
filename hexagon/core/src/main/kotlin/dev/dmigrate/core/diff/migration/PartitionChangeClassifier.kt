package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionBoundNormalizer
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType

/**
 * Ein Kind-Paar, das beide Seiten der Änderung überdauert: gleiche Grenzen,
 * möglicherweise anderer Name oder andere kind-lokale Indizes. Beides deutet
 * je Dialekt Verschiedenes — SQL Server nummeriert Partitionen und ändert
 * Namen bei jeder eingefügten Grenze, PostgreSQL und MySQL benennen sie —,
 * deshalb trägt das Delta beide Seiten und urteilt nicht.
 */
data class RetainedPartition(
    val before: PartitionDefinition,
    val after: PartitionDefinition,
)

/**
 * Die Kinder einer Partitionierung, aufgeteilt nach ihrem Verbleib. Die
 * Zuordnung läuft über die **Grenzen**, nicht über den Namen: SQL Server
 * speichert keine Partitionsnamen, der Reverse vergibt sie in
 * Grenzreihenfolge, und eine eingefügte Grenze verschiebt jede Nummer
 * dahinter.
 */
data class PartitionDelta(
    val added: List<PartitionDefinition>,
    val removed: List<PartitionDefinition>,
    val retained: List<RetainedPartition>,
    /**
     * Aufteilungen: ein entfallenes Kind, dessen Bereich die hinzugekommenen
     * lückenlos wieder abdecken. Kein Wegfall, sondern ein neuer Schnitt — in
     * der SQL-Server-Lesart der Normalfall, weil die Partitionen dort die
     * Zahlenachse lückenlos abdecken und eine eingefügte Grenze deshalb ein
     * Kind durch zwei ersetzt.
     */
    val splits: List<PartitionRecut> = emptyList(),
    /** Zusammenlegungen: ein hinzugekommenes Kind, das den Bereich mehrerer entfallener abdeckt. */
    val merges: List<PartitionRecut> = emptyList(),
) {
    /** Kinder, die wirklich wegfallen — ihr Bereich taucht auf der anderen Seite nicht wieder auf. */
    val droppedOutright: List<PartitionDefinition>
        get() = removed - (splits.map { it.whole } + merges.flatMap { it.pieces }).toSet()

    /** Kinder, die wirklich dazukommen — kein Stück eines neu geschnittenen Bereichs. */
    val addedOutright: List<PartitionDefinition>
        get() = added - (merges.map { it.whole } + splits.flatMap { it.pieces }).toSet()
}

/**
 * Ein Bereich, der neu geschnitten wird: [whole] und [pieces] beschreiben
 * denselben Wertebereich, nur verschieden geteilt. Welche Seite die alte ist,
 * sagt das Feld, in dem der Schnitt steht — bei einer Aufteilung ist [whole]
 * das entfallene Kind, bei einer Zusammenlegung das hinzugekommene.
 */
data class PartitionRecut(
    val whole: PartitionDefinition,
    val pieces: List<PartitionDefinition>,
)

/** Warum eine erkannte Partitionierungsänderung keine Kind-Operation ergibt. */
enum class PartitionChangeReason {
    /** Die Tabelle war unpartitioniert und soll es nicht mehr sein. */
    PARTITIONING_ADDED,

    /** Die Tabelle war partitioniert und soll es nicht mehr sein. */
    PARTITIONING_REMOVED,

    /** RANGE ↔ LIST ↔ HASH. */
    STRATEGY_CHANGED,

    /** Andere Spalten oder andere Spaltenreihenfolge im Partitionsschlüssel. */
    KEY_CHANGED,

    /** Gleiche Grenzen, andere Kindnamen. */
    CHILD_NAMES_CHANGED,

    /**
     * HASH: der Bestand an Eimern ändert sich. Das ist keine Grenzänderung —
     * ein anderer Modulus verteilt **jede** Zeile neu, und kein Dialekt kann
     * das mit einer Anweisung am Bestand erledigen.
     */
    HASH_BUCKETS_CHANGED,

    /** Gleiche Grenzen und Namen, andere kind-lokale Indizes. */
    CHILD_INDICES_CHANGED,
}

/** Das Ergebnis der Klassifikation. */
sealed interface PartitionChange {
    /**
     * Strategie und Schlüssel stehen, nur der Bestand an Kindern ändert sich —
     * der Fall, den jeder partitionierende Dialekt als einzelne Anweisung
     * kennt. Welche Anweisung das ist, entscheidet der Dialekt: SQL Server
     * rechnet das Delta in Grenzwerte zurück (`SPLIT`/`MERGE`), PostgreSQL und
     * MySQL rendern Kind-Anweisungen.
     */
    data class ChildrenChanged(val delta: PartitionDelta) : PartitionChange

    /** Nur über Neubau erreichbar oder in diesem Schnitt nicht abgedeckt. */
    data class NotResolvable(val reason: PartitionChangeReason) : PartitionChange
}

/**
 * Teilt eine vom `TableComparator` erkannte Partitionierungsänderung in den
 * auflösbaren Teil (Kinder kommen dazu oder fallen weg) und den Rest auf.
 *
 * Eine Tabelle lässt sich nicht in place umpartitionieren — es gibt kein
 * `ALTER TABLE … PARTITION BY`. Daraus folgt aber nicht, dass gar nichts geht:
 * eine Partition hinzuzufügen oder zu entfernen ist in jedem partitionierenden
 * Dialekt eine gewöhnliche Anweisung, und rollierende Partitionierung ist der
 * Normalfall.
 */
object PartitionChangeClassifier {

    fun classify(before: PartitionConfig?, after: PartitionConfig?): PartitionChange = when {
        before == null && after != null -> PartitionChange.NotResolvable(PartitionChangeReason.PARTITIONING_ADDED)
        before != null && after == null -> PartitionChange.NotResolvable(PartitionChangeReason.PARTITIONING_REMOVED)
        before == null || after == null -> PartitionChange.NotResolvable(PartitionChangeReason.STRATEGY_CHANGED)
        before.type != after.type -> PartitionChange.NotResolvable(PartitionChangeReason.STRATEGY_CHANGED)
        before.key != after.key -> PartitionChange.NotResolvable(PartitionChangeReason.KEY_CHANGED)
        before.type == PartitionType.HASH ->
            PartitionChange.NotResolvable(PartitionChangeReason.HASH_BUCKETS_CHANGED)
        else -> classifyChildren(before, after)
    }

    private fun classifyChildren(rawBefore: PartitionConfig, rawAfter: PartitionConfig): PartitionChange {
        // Beide Seiten auf dieselbe Grenzform bringen: ein von Hand
        // geschriebenes MySQL-Schema trägt nur die Obergrenze, der Reverse
        // liefert beide ([PartitionBoundNormalizer]).
        val before = PartitionBoundNormalizer.withDerivedLowerBounds(rawBefore)
        val after = PartitionBoundNormalizer.withDerivedLowerBounds(rawAfter)
        val left = before.partitions.associateByTo(LinkedHashMap()) { signature(it) }
        val right = after.partitions.associateByTo(LinkedHashMap()) { signature(it) }

        val added = right.filterKeys { it !in left }.values.toList()
        val removed = left.filterKeys { it !in right }.values.toList()
        val retained = left.entries.mapNotNull { (sig, beforeChild) ->
            right[sig]?.let { RetainedPartition(beforeChild, it) }
        }

        if (added.isNotEmpty() || removed.isNotEmpty()) {
            return PartitionChange.ChildrenChanged(
                PartitionDelta(
                    added = added,
                    removed = removed,
                    retained = retained,
                    splits = recuts(removed, added),
                    merges = recuts(added, removed),
                ),
            )
        }
        // Gleiche Grenzen auf beiden Seiten: was der Comparator gesehen hat,
        // steckt dann in den Namen oder den kind-lokalen Indizes. Beides ist
        // eine eigene Operation und gehört nicht in dieses Delta.
        return if (retained.any { it.before.name != it.after.name }) {
            PartitionChange.NotResolvable(PartitionChangeReason.CHILD_NAMES_CHANGED)
        } else {
            PartitionChange.NotResolvable(PartitionChangeReason.CHILD_INDICES_CHANGED)
        }
    }

    /**
     * Die Kinder aus [wholes], deren Bereich [pieces] lückenlos wieder
     * abdeckt — samt der Stücke, aus denen er nun besteht.
     *
     * „Lückenlos" ist dabei eine Gleichheitsfrage, keine Ordnungsfrage: die
     * Kette beginnt an der unteren Grenze des Kindes und folgt Endpunkt an
     * Anfangspunkt, bis sie die obere erreicht. Damit braucht es keinen
     * Vergleich zweier Grenzwerte — den könnte das neutrale Modell für Datum,
     * Zahl und Zeichenkette gar nicht führen.
     */
    private fun recuts(
        wholes: List<PartitionDefinition>,
        pieces: List<PartitionDefinition>,
    ): List<PartitionRecut> {
        if (pieces.isEmpty()) return emptyList()
        val byFrom = pieces.filter { it.from != null }.groupBy { it.from }
        return wholes.mapNotNull { whole ->
            if (whole.from == null || whole.to == null) return@mapNotNull null
            chain(whole.from, whole.to, byFrom)?.let { PartitionRecut(whole, it) }
        }
    }

    private fun chain(
        from: List<PartitionBound>,
        to: List<PartitionBound>,
        byFrom: Map<List<PartitionBound>?, List<PartitionDefinition>>,
    ): List<PartitionDefinition>? {
        var current: List<PartitionBound> = from
        val walked = mutableListOf<PartitionDefinition>()
        repeat(byFrom.size) {
            val next = byFrom[current]?.singleOrNull() ?: return null
            walked += next
            val upper = next.to ?: return null
            if (upper == to) return walked
            current = upper
        }
        return null
    }

    /**
     * Die Identität einer Partition: ihre Grenzen, nicht ihr Name. Kind-lokale
     * Indizes gehören nicht dazu — ein Kind mit einem zusätzlichen Index ist
     * dasselbe Kind.
     */
    private fun signature(partition: PartitionDefinition): Signature = Signature(
        isDefault = partition.isDefault,
        from = partition.from,
        to = partition.to,
        values = partition.values,
        modulus = partition.modulus,
        remainder = partition.remainder,
    )

    private data class Signature(
        val isDefault: Boolean,
        val from: List<PartitionBound>?,
        val to: List<PartitionBound>?,
        val values: List<String>?,
        val modulus: Int?,
        val remainder: Int?,
    )
}
