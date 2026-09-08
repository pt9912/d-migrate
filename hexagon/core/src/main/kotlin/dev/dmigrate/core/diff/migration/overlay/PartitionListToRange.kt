package dev.dmigrate.core.diff.migration.overlay

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType

/**
 * Uebersetzt eine LIST-Partitionierung in die RANGE-Grenzen, die dasselbe
 * Routing ergeben — sofern der Anwender die Zuordnung beigesteuert hat.
 *
 * Aus `(1,2), (3,4)` mit den Grenzen `3, 5` wird eine RANGE-Partitionierung
 * mit den Grenzwerten `3, 5`: `RANGE RIGHT` legt `< 3` in die erste Partition,
 * `[3,5)` in die zweite. Welche Menge welcher Grenze entspricht, kann das
 * Werkzeug nicht raten; **dass** eine behauptete Zuordnung richtig routet,
 * kann es pruefen — das tut [PartitionMappingVerifier].
 *
 * Was die Uebersetzung **nicht** ist: eine Gleichsetzung von LIST und RANGE.
 * Eine RANGE-Partitionierung nimmt Werte an, die LIST zurueckgewiesen haette —
 * unterhalb der ersten Grenze faengt sie die erste Partition, oberhalb der
 * letzten eine, die es in LIST gar nicht gab. Die Uebersetzung weitet die
 * Tabelle also; sie fuehrt die zusaetzliche Partition deshalb **im Modell**
 * mit, statt sie dem Server zu ueberlassen — sonst beschriebe das Schema eine
 * Partition weniger, als danach existiert, und der naechste Vergleich faende
 * eine Abweichung, die niemand gemacht hat.
 */
object PartitionListToRange {

    sealed interface Result {
        /**
         * Die aequivalente RANGE-Partitionierung: je LIST-Partition eine mit
         * halboffenem Intervall, dazu die Auffang-Partition oberhalb der
         * letzten Grenze.
         */
        data class Translated(val config: PartitionConfig) : Result

        /** Die Zuordnung ist da, taugt aber nicht — mit Grund. */
        data class Refused(val reason: String) : Result

        /** Keine LIST-Partitionierung oder keine Zuordnung fuer diese Tabelle. */
        data object NotApplicable : Result
    }

    fun translate(
        table: String,
        config: PartitionConfig,
        entries: List<PartitionMappingOverlayEntry>,
    ): Result {
        if (config.type != PartitionType.LIST) return Result.NotApplicable
        val forTable = entries.filter { it.table == table && it.values != null && it.rangeUpperBound != null }
        if (forTable.isEmpty()) return Result.NotApplicable

        // Eine DEFAULT-Partition faengt alles, was in keiner Menge steht — auch
        // Werte unterhalb der ersten Grenze. RANGE kennt dafuer keine Form:
        // sein Auffangbecken liegt immer am Rand, nie in der Mitte.
        config.partitions.firstOrNull { it.isDefault }?.let {
            return Result.Refused(
                "partition '${it.name}' is the LIST DEFAULT; RANGE has no catch-all that covers values " +
                    "below the first boundary as well",
            )
        }

        val byName = forTable.associateBy { it.sourcePartition }
        val missing = config.partitions.filter { it.name !in byName }
        if (missing.isNotEmpty()) {
            return Result.Refused(
                "the mapping does not cover partition(s) ${missing.joinToString { "'${it.name}'" }}; " +
                    "a partition without a boundary has nowhere to route",
            )
        }

        val mappings = config.partitions.map { partition ->
            val entry = byName.getValue(partition.name)
            val declared = partition.values.orEmpty()
            // Die Behauptung des Overlays muss sich auf **diese** Menge
            // beziehen. Deckt sie sich nicht mit dem Schema, sagt die
            // Nachpruefung etwas ueber eine andere Partitionierung aus.
            if (declared.toSet() != entry.values.orEmpty().toSet()) {
                return Result.Refused(
                    "the mapping for partition '${partition.name}' claims a different value set than the " +
                        "schema declares; it would vouch for a partitioning that is not this one",
                )
            }
            PartitionMappingVerifier.Mapping(
                partition = partition.name,
                values = declared,
                upperBound = entry.rangeUpperBound.orEmpty(),
            )
        }

        return when (val verdict = PartitionMappingVerifier.verify(mappings)) {
            is PartitionMappingVerifier.Result.Invalid -> Result.Refused(verdict.reason)
            PartitionMappingVerifier.Result.Valid -> Result.Translated(rangeConfig(config, mappings))
        }
    }

    /**
     * Aus n Wertemengen werden n+1 Partitionen: jede Menge bekommt das
     * Intervall bis zu ihrer Grenze, und was oberhalb der letzten liegt,
     * bekommt die Auffang-Partition. Ihr Name haengt an dem der letzten Menge,
     * damit er nicht mit einem vergebenen kollidiert.
     */
    private fun rangeConfig(config: PartitionConfig, mappings: List<PartitionMappingVerifier.Mapping>): PartitionConfig {
        val ordered = PartitionMappingVerifier.inOrder(mappings)
        val declared = config.partitions.associateBy { it.name }
        var lower: List<PartitionBound> = listOf(PartitionBound.MinValue)
        val partitions = mutableListOf<PartitionDefinition>()
        for (mapping in ordered) {
            val upper = listOf(PartitionBound.Value(mapping.upperBound))
            partitions += declared.getValue(mapping.partition)
                .copy(values = null, from = lower, to = upper)
            lower = upper
        }
        partitions += PartitionDefinition(
            name = "${ordered.last().partition}_above",
            from = lower,
            to = listOf(PartitionBound.MaxValue),
        )
        return config.copy(type = PartitionType.RANGE, partitions = partitions)
    }
}
