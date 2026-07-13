package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition

/**
 * LN-008 (ADR 0032): plans the per-child-partition fan-out for `data export`.
 * For every listed parent that the source schema reports as partitioned,
 * returns its (schema-qualified) child partition names so the exporter writes
 * one file per child instead of a single transparent parent file. Single
 * schema (export has no target) — any partitioned parent fans out.
 */
object ExportPartitionExpansion {

    fun plan(schema: SchemaDefinition, tables: List<String>): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        for (parent in tables) {
            val children = PartitionChildren.qualifiedNames(schema, parent)
            if (children.isNotEmpty()) result[parent] = children
        }
        return result
    }
}
