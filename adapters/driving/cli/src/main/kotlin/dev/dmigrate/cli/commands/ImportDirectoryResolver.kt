package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves which tables to import from a directory source, maps them
 * to schema definitions, and returns a topologically sorted order
 * based on FK dependencies.
 */
internal object ImportDirectoryResolver {

    fun resolveTableOrder(
        schemaPath: Path,
        schema: dev.dmigrate.core.model.SchemaDefinition,
        input: ImportInput.Directory,
        format: DataExportFormat,
    ): List<String> {
        val candidateTables = resolveCandidateTables(input, format)
        if (candidateTables.isEmpty()) return emptyList()

        val candidateToSchema = linkedMapOf<String, String>()
        val missingInSchema = mutableListOf<String>()
        val ambiguousMatches = mutableListOf<String>()

        for (candidate in candidateTables) {
            val matches = matchingSchemaTableNames(schema.tables.keys, candidate)
            when {
                matches.isEmpty() -> missingInSchema += candidate
                matches.size > 1 ->
                    ambiguousMatches += "$candidate -> ${matches.joinToString()}"
                else -> candidateToSchema[candidate] = matches.single()
            }
        }

        if (missingInSchema.isNotEmpty()) {
            throw ImportPreflightException(
                "Schema file '$schemaPath' does not define tables required for directory import: " +
                    missingInSchema.joinToString()
            )
        }

        if (ambiguousMatches.isNotEmpty()) {
            throw ImportPreflightException(
                "Schema file '$schemaPath' matches directory import tables ambiguously: " +
                    ambiguousMatches.joinToString("; ")
            )
        }

        val duplicateSchemaTargets = candidateToSchema.entries
            .groupBy({ it.value }, { it.key })
            .filterValues { it.size > 1 }
        if (duplicateSchemaTargets.isNotEmpty()) {
            val details = duplicateSchemaTargets.entries.joinToString("; ") { (schemaTable, candidates) ->
                "$schemaTable <- ${candidates.joinToString()}"
            }
            throw ImportPreflightException(
                "Schema file '$schemaPath' maps multiple directory tables to the same schema table: $details"
            )
        }

        val schemaToCandidate = candidateToSchema.entries.associate { (candidate, schemaTable) ->
            schemaTable to candidate
        }
        val selectedTables = linkedMapOf<String, TableDefinition>()
        for ((candidate, schemaTable) in candidateToSchema) {
            selectedTables[candidate] = schema.tables.getValue(schemaTable)
        }

        val sortResult = topologicalSort(selectedTables) { referenceTable ->
            schemaToCandidate[referenceTable]
        }
        if (sortResult.circularEdges.isNotEmpty()) {
            val edges = sortResult.circularEdges.joinToString("; ") {
                "${it.fromTable}.${it.fromColumn} -> ${it.toTable}.${it.toColumn}"
            }
            throw ImportPreflightException(
                "Schema-defined table dependency cycle detected for directory import: $edges"
            )
        }

        return sortResult.sorted
    }

    fun matchingSchemaTableNames(
        schemaTables: Collection<String>,
        requestedTable: String,
    ): List<String> {
        if (requestedTable in schemaTables) {
            return listOf(requestedTable)
        }

        return if ('.' in requestedTable) {
            val unqualified = requestedTable.substringAfterLast('.')
            schemaTables.filter { it == unqualified }
        } else {
            schemaTables.filter { it.substringAfterLast('.') == requestedTable }
        }
    }

    // ── Private helpers ─────────────────────────────────────────────

    private fun resolveCandidateTables(
        input: ImportInput.Directory,
        format: DataExportFormat,
    ): List<String> {
        val suffixes = format.fileExtensions.map { ".$it" }
        val candidates = linkedMapOf<String, Path>()
        val candidateFiles = linkedMapOf<String, MutableList<String>>()

        try {
            Files.newDirectoryStream(input.path).use { entries ->
                for (entry in entries) {
                    val fileName = entry.fileName.toString()
                    if (!Files.isRegularFile(entry)) continue
                    val matchedSuffix = suffixes.firstOrNull { fileName.endsWith(it) } ?: continue
                    val tableName = fileName.removeSuffix(matchedSuffix)
                    candidateFiles.getOrPut(tableName) { mutableListOf() }.add(fileName)
                    candidates.putIfAbsent(tableName, entry)
                }
            }
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to list directory import source '${input.path}': ${t.message ?: t::class.simpleName}",
                t,
            )
        }

        val tableFilter = input.tableFilter
        if (tableFilter != null) {
            val missing = tableFilter.filterNot(candidates::containsKey)
            if (missing.isNotEmpty()) {
                throw ImportPreflightException(
                    "Directory import filter references tables without matching files: ${missing.joinToString()}"
                )
            }
            val duplicateDetails = duplicateCandidateDetails(candidateFiles, tableFilter)
            if (duplicateDetails.isNotEmpty()) {
                throw ImportPreflightException(
                    "Directory import source contains multiple files for the same table: " +
                        duplicateDetails.joinToString("; ")
                )
            }
            return tableFilter
        }

        val duplicateDetails = duplicateCandidateDetails(candidateFiles, candidates.keys)
        if (duplicateDetails.isNotEmpty()) {
            throw ImportPreflightException(
                "Directory import source contains multiple files for the same table: " +
                    duplicateDetails.joinToString("; ")
            )
        }

        return candidates.keys.toList()
    }

    private fun duplicateCandidateDetails(
        candidateFiles: Map<String, List<String>>,
        selectedTables: Iterable<String>,
    ): List<String> =
        selectedTables.asSequence()
            .distinct()
            .mapNotNull { table ->
                candidateFiles[table]
                    ?.takeIf { it.size > 1 }
                    ?.let { "$table <- ${it.sorted().joinToString()}" }
            }
            .sorted()
            .toList()

    private data class CircularEdge(
        val fromTable: String,
        val fromColumn: String,
        val toTable: String,
        val toColumn: String,
    )

    private data class SortResult(
        val sorted: List<String>,
        val circularEdges: List<CircularEdge>,
    )

    private fun topologicalSort(
        tables: Map<String, TableDefinition>,
        referenceToSelectedTable: (String) -> String? = { it },
    ): SortResult {
        val edges = mutableListOf<dev.dmigrate.core.dependency.FkEdge>()

        for ((tableName, table) in tables) {
            for ((columnName, column) in table.columns) {
                val reference = column.references ?: continue
                edges += dev.dmigrate.core.dependency.FkEdge(
                    tableName, columnName,
                    referenceToSelectedTable(reference.table) ?: reference.table,
                    reference.column,
                )
            }
            for (constraint in table.constraints) {
                if (constraint.type != ConstraintType.FOREIGN_KEY) continue
                val reference = constraint.references ?: continue
                val sourceColumns = constraint.columns.orEmpty()
                val targetColumns = reference.columns
                val pairCount = maxOf(sourceColumns.size, targetColumns.size, 1)
                for (index in 0 until pairCount) {
                    edges += dev.dmigrate.core.dependency.FkEdge(
                        tableName,
                        sourceColumns.getOrElse(index) { constraint.name },
                        referenceToSelectedTable(reference.table) ?: reference.table,
                        targetColumns.getOrElse(index) { constraint.name },
                    )
                }
            }
        }

        val result = dev.dmigrate.core.dependency.sortTablesByDependency(tables.keys, edges)
        return SortResult(
            sorted = result.sorted,
            circularEdges = result.circularEdges.map {
                CircularEdge(it.fromTable, it.fromColumn ?: "", it.toTable, it.toColumn ?: "")
            },
        )
    }
}
