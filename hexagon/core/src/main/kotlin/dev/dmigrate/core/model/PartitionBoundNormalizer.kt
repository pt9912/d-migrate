package dev.dmigrate.core.model

/**
 * Ergänzt fehlende **Untergrenzen** einer RANGE-Partitionierung aus der
 * Reihenfolge: `fromₙ = toₙ₋₁`, und die erste beginnt bei `MINVALUE`.
 *
 * Der Grund ist eine Unwucht zwischen den beiden Quellen desselben Modells.
 * MySQL beschreibt eine RANGE-Partition nur über ihre Obergrenze
 * (`VALUES LESS THAN`), und so schreibt man sie auch in eine Schemadatei; der
 * Reverse dagegen rechnet die Untergrenze aus und liefert sie mit. Ohne
 * Ausgleich beschreiben beide Seiten dieselbe Tabelle verschieden — jeder
 * Vergleich meldete dann eine Änderung, die es nicht gibt.
 *
 * Ergänzt wird nur, was **fehlt**. Eine angegebene Untergrenze bleibt
 * unangetastet, auch wenn sie eine Lücke lässt: PostgreSQL erlaubt
 * nicht-lückenlose Bereiche, und die zu schließen wäre eine Aussage über die
 * Daten, keine Normalisierung.
 */
object PartitionBoundNormalizer {

    fun withDerivedLowerBounds(config: PartitionConfig): PartitionConfig =
        if (config.type != PartitionType.RANGE || config.partitions.none { it.from == null }) {
            config
        } else {
            config.copy(partitions = deriveLowerBounds(config.partitions))
        }

    private fun deriveLowerBounds(partitions: List<PartitionDefinition>): List<PartitionDefinition> {
        var previousUpper: List<PartitionBound>? = null
        return partitions.map { partition ->
            val derived = when {
                partition.isDefault -> partition
                partition.from != null -> partition
                else -> partition.copy(
                    from = previousUpper ?: partition.to?.map { PartitionBound.MinValue }
                        ?: listOf(PartitionBound.MinValue),
                )
            }
            // Eine Partition ohne Obergrenze trägt die Kette nicht weiter: die
            // nächste begänne sonst bei einer Grenze, die es nicht gibt.
            previousUpper = partition.to
            derived
        }
    }
}
