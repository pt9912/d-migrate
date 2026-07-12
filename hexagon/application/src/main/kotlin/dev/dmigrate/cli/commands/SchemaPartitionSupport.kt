package dev.dmigrate.cli.commands

import dev.dmigrate.core.dependency.FkEdge
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Shared partition-child helpers for the parallel data path (LN-007/LN-008, ADR 0032).
 * Partition children are captured as bare `relname`; [qualify] prefixes them with the
 * parent's schema so reader/writer address the right table. Used by the export, transfer
 * and import expansion/layer planners so the extraction lives in one place.
 */
internal object PartitionChildren {

    /** Bare child partition names of [parent] (empty if [parent] is not partitioned). */
    fun bareNames(schema: SchemaDefinition, parent: String): List<String> =
        schema.tables[parent]?.partitioning?.partitions?.map { it.name } ?: emptyList()

    /** Schema-qualified child partition names of [parent]. */
    fun qualifiedNames(schema: SchemaDefinition, parent: String): List<String> =
        bareNames(schema, parent).map { qualify(parent, it) }

    /** Qualify a bare child name with [parentKey]'s schema prefix (if any). */
    fun qualify(parentKey: String, child: String): String {
        val dot = parentKey.lastIndexOf('.')
        return if (dot >= 0) parentKey.substring(0, dot + 1) + child else child
    }
}

/**
 * Builds table-level FK edges from a neutral schema (column references + explicit
 * FK constraints). Shared by [TransferPreflightPlanner] and [ImportLayerPlanner]
 * so the FK-graph extraction is defined once.
 */
internal object SchemaFkEdges {

    fun of(schema: SchemaDefinition, tables: Collection<String>): List<FkEdge> =
        tables.flatMap { table ->
            val refs = mutableListOf<FkEdge>()
            schema.tables[table]?.columns?.values?.forEach { column ->
                column.references?.let { refs += FkEdge(table, toTable = it.table) }
            }
            schema.tables[table]?.constraints?.forEach { constraint ->
                constraint.references?.let { refs += FkEdge(table, toTable = it.table) }
            }
            refs
        }
}
