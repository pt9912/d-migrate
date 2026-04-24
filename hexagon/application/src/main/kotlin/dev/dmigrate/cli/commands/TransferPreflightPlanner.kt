package dev.dmigrate.cli.commands

import dev.dmigrate.core.dependency.FkEdge
import dev.dmigrate.core.dependency.sortTablesByDependency
import dev.dmigrate.core.model.SchemaDefinition

internal class TransferPreflightPlanner(
    private val typeCompatibility: TransferTypeCompatibility = TransferTypeCompatibility(),
) {

    fun planTables(request: DataTransferRequest, source: SchemaDefinition, target: SchemaDefinition): List<String> {
        val candidates = if (request.tables != null) {
            for (table in request.tables) {
                if (table !in source.tables) {
                    throw TransferPreflightException("Source table '$table' not found")
                }
            }
            request.tables
        } else {
            source.tables.keys.toList()
        }

        for (table in candidates) {
            if (table !in target.tables) {
                throw TransferPreflightException("Target table '$table' not found")
            }
            for ((column, sourceDefinition) in source.tables[table]!!.columns) {
                val targetDefinition = target.tables[table]!!.columns[column]
                    ?: throw TransferPreflightException("Column '$table.$column' missing in target")
                if (!typeCompatibility.isCompatible(sourceDefinition, targetDefinition)) {
                    throw TransferPreflightException(
                        "Column '$table.$column' type mismatch: ${sourceDefinition.type} vs ${targetDefinition.type}"
                    )
                }
            }
        }

        if (request.onConflict.equals("update", true)) {
            for (table in candidates) {
                if (target.tables[table]!!.primaryKey.isEmpty()) {
                    throw TransferPreflightException("Table '$table' needs PK for --on-conflict update")
                }
            }
        }
        return topoSort(candidates, target)
    }

    private fun topoSort(tables: List<String>, schema: SchemaDefinition): List<String> {
        val tableSet = tables.toSet()
        val edges = tables.flatMap { table ->
            val refs = mutableListOf<FkEdge>()
            schema.tables[table]?.columns?.values?.forEach { column ->
                column.references?.let { refs += FkEdge(table, toTable = it.table) }
            }
            schema.tables[table]?.constraints?.forEach { constraint ->
                constraint.references?.let { refs += FkEdge(table, toTable = it.table) }
            }
            refs
        }
        val result = sortTablesByDependency(tableSet, edges)
        if (result.circularEdges.isNotEmpty()) {
            val cyclic = result.circularEdges.map { it.fromTable }.toSet()
            throw TransferPreflightException("FK cycle: ${cyclic.joinToString()}")
        }
        return result.sorted
    }
}
