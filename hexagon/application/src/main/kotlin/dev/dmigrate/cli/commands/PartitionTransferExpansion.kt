package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition

/**
 * LN-008 (ADR 0032): plans which partitioned parent tables should be
 * transferred **per child partition** (so the children can run through the
 * parallel executor) instead of as one transparent parent SELECT.
 *
 * A parent is expanded only when BOTH source and target declare it
 * partitioned with the **identical child-name set** — the PG→PG round-trip
 * case, where each source child maps 1:1 to a same-named target child.
 * Anything else (not partitioned, cross-dialect, drifted child sets) falls
 * back to the transparent parent transfer (empty result for that parent).
 */
internal object PartitionTransferExpansion {

    fun plan(
        source: SchemaDefinition,
        target: SchemaDefinition,
        tables: List<String>,
    ): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        for (parent in tables) {
            val srcChildren = PartitionChildren.bareNames(source, parent)
            if (srcChildren.isEmpty()) continue
            val tgtChildren = PartitionChildren.bareNames(target, parent)
            if (srcChildren.toSet() == tgtChildren.toSet()) {
                result[parent] = PartitionChildren.qualifiedNames(source, parent)
            }
        }
        return result
    }
}
