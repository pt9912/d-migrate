package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.IndexDefinition

/**
 * Allocates PostgreSQL index names for one `generate()` run.
 *
 * PostgreSQL index names are unique per *schema*, unlike MySQL's
 * per-table namespace. A MySQL source can carry the same explicit index
 * name (e.g. `idx_fk_address_id`) on several tables; emitting them
 * verbatim fails with `relation "..." already exists` (N8). The
 * allocator tracks every name emitted across all tables of one run and
 * disambiguates collisions deterministically — the table iteration order
 * is the deterministic topological sort, so the assignment is stable.
 *
 * [reset] MUST be called at the start of each `generate()` run so a
 * generator instance can be reused without leaking names between runs.
 */
internal class PostgresIndexNameAllocator {

    private val schemaGlobalIndexNames = mutableSetOf<String>()

    fun reset() {
        schemaGlobalIndexNames.clear()
    }

    /**
     * Final, schema-globally-unique index names for [indices] of
     * [tableName], positionally aligned with the input list. Explicit
     * names are kept unless they collide with a name already emitted in
     * this run; generated names follow `idx_<table>_<cols>` and carry a
     * within-table direction/where suffix when the base repeats.
     */
    fun namesFor(tableName: String, indices: List<IndexDefinition>): List<String> {
        val baseNames = indices.map { index ->
            index.name ?: "idx_${tableName}_${index.columnNames.joinToString("_")}"
        }
        val baseCounts = baseNames.groupingBy { it }.eachCount()
        val used = indices.mapNotNull { it.name }.groupingBy { it }.eachCount().toMutableMap()
        return indices.mapIndexed { position, index ->
            val withinTable = index.name
                ?: disambiguateWithinTable(baseNames[position], index, baseCounts.getValue(baseNames[position]), used)
            ensureSchemaGloballyUnique(withinTable)
        }
    }

    private fun disambiguateWithinTable(
        baseName: String,
        index: IndexDefinition,
        baseCount: Int,
        used: MutableMap<String, Int>,
    ): String {
        val candidate = if (baseCount == 1) baseName else "${baseName}_${disambiguationSuffix(index)}"
        val seen = used.getOrDefault(candidate, 0)
        used[candidate] = seen + 1
        return if (seen == 0) candidate else "${candidate}_${seen + 1}"
    }

    private fun disambiguationSuffix(index: IndexDefinition): String {
        val directionPart = index.columns.joinToString("_") { it.direction?.name?.lowercase() ?: "default" }
        val wherePart = index.where?.let { "_where_${Integer.toUnsignedString(it.hashCode(), 36)}" }.orEmpty()
        return "$directionPart$wherePart"
    }

    /**
     * Make [name] unique across the whole run: returns it unchanged on
     * first use, otherwise appends the smallest free `_<n>` (n ≥ 2).
     */
    private fun ensureSchemaGloballyUnique(name: String): String {
        if (schemaGlobalIndexNames.add(name)) return name
        var suffix = 2
        while (!schemaGlobalIndexNames.add("${name}_$suffix")) suffix++
        return "${name}_$suffix"
    }
}
