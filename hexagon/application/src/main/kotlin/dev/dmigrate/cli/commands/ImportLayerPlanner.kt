package dev.dmigrate.cli.commands

import dev.dmigrate.core.dependency.sortTablesIntoLayers
import dev.dmigrate.core.model.SchemaDefinition

/**
 * LN-007/LN-008 (ADR 0032): groups the resolved import table set into FK-safe
 * concurrency layers so `data import --parallel` can run independent tables (and
 * the child-partition files of a parent) concurrently while never importing a
 * table before the tables it references.
 *
 * FK edges come from the **target** schema. A child-partition input (its parent
 * is partitioned in the target) inherits its parent's FK layer — the parent's
 * referenced tables must still land first. Inputs whose table is neither in the
 * schema nor a known child are placed in a trailing layer (imported last), a
 * safe default when their dependencies are unknown.
 */
object ImportLayerPlanner {

    fun plan(schema: SchemaDefinition, inputTables: List<String>): List<List<String>> {
        val childToParent = buildChildToParent(schema)
        fun effective(table: String) = childToParent[table] ?: table

        val effectiveSet = inputTables.map { effective(it) }.toSet()
        val layers = sortTablesIntoLayers(effectiveSet, SchemaFkEdges.of(schema, effectiveSet)).layers

        val layerOf = HashMap<String, Int>()
        layers.forEachIndexed { index, layer -> layer.forEach { layerOf[it] = index } }
        val trailingLayer = layers.size // unknown/cyclic effective tables import last

        val grouped = sortedMapOf<Int, MutableList<String>>()
        for (table in inputTables) {
            val index = layerOf[effective(table)] ?: trailingLayer
            grouped.getOrPut(index) { mutableListOf() }.add(table)
        }
        return grouped.values.toList()
    }

    private fun buildChildToParent(schema: SchemaDefinition): Map<String, String> {
        val map = HashMap<String, String>()
        for (parent in schema.tables.keys) {
            for (child in PartitionChildren.bareNames(schema, parent)) {
                map[child] = parent
                map[PartitionChildren.qualify(parent, child)] = parent
            }
        }
        return map
    }
}
