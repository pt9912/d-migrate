package dev.dmigrate.cli.commands

import dev.dmigrate.core.dependency.sortTablesByDependency
import dev.dmigrate.core.dependency.sortTablesIntoLayers
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.TransferTypeCompatibility

internal class TransferPreflightPlanner {

    /**
     * Linear FK-safe table order for the sequential path (`--parallel 1`).
     * Kept identical to the pre-LN-007 behaviour so the default run is
     * byte-/order-identical (incl. the "Transferred: <table>" progress order).
     */
    fun planTables(
        request: DataTransferRequest,
        source: SchemaDefinition,
        target: SchemaDefinition,
        typeCompatibility: TransferTypeCompatibility,
    ): List<String> {
        val candidates = validate(request, source, target, typeCompatibility)
        val result = sortTablesByDependency(candidates.toSet(), SchemaFkEdges.of(target, candidates))
        if (result.circularEdges.isNotEmpty()) {
            throw TransferPreflightException("FK cycle: ${result.circularEdges.map { it.fromTable }.toSet().joinToString()}")
        }
        return result.sorted
    }

    /**
     * LN-007/LN-008: the validated transfer table set grouped into FK-safe
     * concurrency layers (Kahn by level) for the parallel path. Layer `i` may
     * only start once layers `< i` are done; tables inside a layer have no FK
     * edge among them and may run concurrently.
     */
    fun planLayers(
        request: DataTransferRequest,
        source: SchemaDefinition,
        target: SchemaDefinition,
        typeCompatibility: TransferTypeCompatibility,
    ): List<List<String>> {
        val candidates = validate(request, source, target, typeCompatibility)
        val result = sortTablesIntoLayers(candidates.toSet(), SchemaFkEdges.of(target, candidates))
        if (result.circularEdges.isNotEmpty()) {
            val cyclic = result.circularEdges.map { it.fromTable }.toSet()
            throw TransferPreflightException("FK cycle: ${cyclic.joinToString()}")
        }
        return result.layers
    }

    private fun validate(
        request: DataTransferRequest,
        source: SchemaDefinition,
        target: SchemaDefinition,
        typeCompatibility: TransferTypeCompatibility,
    ): List<String> {
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
                if (!typeCompatibility.isCompatible(sourceDefinition.type, targetDefinition.type)) {
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
        return candidates
    }
}
