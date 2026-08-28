package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.PartitionLiteralGuard

/**
 * Die Partitionierungs-Klauseln von PostgreSQL an einer Stelle.
 *
 * Extrahiert, weil sie zwei Aufrufer haben: `PostgresDdlGenerator` (Generate)
 * und `PostgresDiffTableOps` (Migration). Vorher rendete nur der erste sie —
 * eine Tabelle, die per `schema migrate` neu entstand, verlor ihre
 * Partitionierung still. Dieselbe Aufteilung wie bei [PostgresIndexClauses].
 */
internal object PostgresPartitionClauses {

    /**
     * Ob die Partitionierung ueberhaupt gerendert werden darf.
     *
     * PostgreSQL kennt keine implizite Default-Partition: ein `PARTITION BY`
     * ohne Kinder nimmt **keine** Zeile an („no partition of relation found for
     * row"). Eine solche Tabelle als partitioniert anzulegen waere schlimmer
     * als sie flach anzulegen — deshalb entscheidet das der Aufrufer, und beide
     * Aufrufer entscheiden es gleich.
     */
    fun isRenderable(partitioning: PartitionConfig?): Boolean =
        partitioning != null && partitioning.partitions.isNotEmpty()

    /** ` PARTITION BY RANGE ("col")` — Suffix der `CREATE TABLE`-Klammer. */
    fun partitionByClause(partitioning: PartitionConfig, quote: (String) -> String): String {
        val key = partitioning.key.joinToString(", ") { quote(it) }
        return " PARTITION BY ${partitioning.type.name} ($key)"
    }

    /** `CREATE TABLE "kind" PARTITION OF "parent" FOR VALUES …;` je Kind. */
    fun childStatements(
        parentTable: String,
        partitioning: PartitionConfig,
        quote: (String) -> String,
    ): List<String> = partitioning.partitions.map { child ->
        childStatement(parentTable, child, partitioning.type, quote)
    }

    private fun childStatement(
        parentTable: String,
        partition: PartitionDefinition,
        type: PartitionType,
        quote: (String) -> String,
    ): String = buildString {
        append("CREATE TABLE ${quote(partition.name)} PARTITION OF ${quote(parentTable)}")
        if (partition.isDefault) {
            append(" DEFAULT")
        } else when (type) {
            PartitionType.RANGE -> {
                val from = rangeBounds(partition.from, "FROM", partition.name)
                val to = rangeBounds(partition.to, "TO", partition.name)
                append(" FOR VALUES FROM ($from) TO ($to)")
            }
            PartitionType.LIST -> {
                val values = partition.values.orEmpty()
                    .joinToString(", ") { PartitionLiteralGuard.ensureSafe(it, partition.name) }
                append(" FOR VALUES IN ($values)")
            }
            PartitionType.HASH -> {
                val modulus = requireNotNull(partition.modulus) {
                    "HASH partition '${partition.name}' must have a modulus"
                }
                val remainder = requireNotNull(partition.remainder) {
                    "HASH partition '${partition.name}' must have a remainder"
                }
                append(" FOR VALUES WITH (MODULUS $modulus, REMAINDER $remainder)")
            }
        }
        append(";")
    }

    private fun rangeBounds(bounds: List<PartitionBound>?, clause: String, partitionName: String): String {
        requireNotNull(bounds) { "Partition '$partitionName' $clause bound must not be null" }
        require(bounds.isNotEmpty()) { "Partition '$partitionName' $clause bound must not be empty" }
        return bounds.joinToString(", ") { bound ->
            when (bound) {
                PartitionBound.MinValue -> "MINVALUE"
                PartitionBound.MaxValue -> "MAXVALUE"
                is PartitionBound.Value -> PartitionLiteralGuard.ensureSafe(bound.literal, partitionName)
            }
        }
    }
}
