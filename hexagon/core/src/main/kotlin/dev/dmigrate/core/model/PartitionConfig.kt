package dev.dmigrate.core.model

data class PartitionConfig(
    val type: PartitionType,
    val key: List<String>,
    val partitions: List<PartitionDefinition> = emptyList()
)

enum class PartitionType {
    RANGE, HASH, LIST
}

/**
 * Eine RANGE-Partitionsgrenze: ein kanonisierter Literalwert oder ein
 * PG-Sentinel. Strukturiert statt rohem Dialekt-String (ADR 0019 —
 * „kein Native-Passthrough im neutralen Modell"; Präzedenz fulltext/geometry).
 */
sealed interface PartitionBound {
    /** Untere Sentinel-Grenze (`MINVALUE`). */
    data object MinValue : PartitionBound

    /** Obere Sentinel-Grenze (`MAXVALUE`). */
    data object MaxValue : PartitionBound

    /** Kanonisierter Literalwert (Typ-Casts gestrippt, Quoting/Whitespace normiert). */
    data class Value(val literal: String) : PartitionBound
}

/**
 * Eine Kind-Partition. Strukturierte Grenzen je Strategie (ADR 0019):
 * - RANGE: [from]/[to] als Bound-Tupel (mehrspaltig möglich), inkl. Sentinels.
 * - LIST: [values] als kanonisierte Literal-Liste.
 * - HASH: [modulus]/[remainder] als Zahlen.
 * - [isDefault]: die DEFAULT-Partition (Catch-all); schließt die anderen Grenzen aus.
 */
data class PartitionDefinition(
    val name: String,
    val isDefault: Boolean = false,
    val from: List<PartitionBound>? = null,
    val to: List<PartitionBound>? = null,
    val values: List<String>? = null,
    val modulus: Int? = null,
    val remainder: Int? = null,
)
