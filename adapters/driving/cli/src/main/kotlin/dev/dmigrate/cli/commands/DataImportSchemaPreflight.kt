package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.yaml.YamlSchemaCodec
import dev.dmigrate.streaming.ImportInput
import java.nio.file.Files
import java.nio.file.Path

object DataImportSchemaPreflight {

    fun prepare(
        schemaPath: Path,
        input: ImportInput,
        format: DataExportFormat,
    ): ImportInput {
        val schema = readSchema(schemaPath)
        validateSchema(schemaPath, schema)

        return when (input) {
            is ImportInput.Directory -> input.copy(
                tableOrder = resolveTableOrder(schemaPath, schema, input, format)
            )
            else -> input
        }
    }

    private fun readSchema(schemaPath: Path): SchemaDefinition {
        if (!Files.exists(schemaPath)) {
            throw ImportPreflightException("Schema path does not exist: $schemaPath")
        }
        if (!Files.isRegularFile(schemaPath)) {
            throw ImportPreflightException("Schema path is not a file: $schemaPath")
        }

        return try {
            Files.newInputStream(schemaPath).use { input ->
                YamlSchemaCodec().read(input)
            }
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to parse schema file '$schemaPath': ${t.message ?: t::class.simpleName}",
                t,
            )
        }
    }

    private fun validateSchema(schemaPath: Path, schema: SchemaDefinition) {
        val result = try {
            SchemaValidator().validate(schema)
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to validate schema file '$schemaPath': ${t.message ?: t::class.simpleName}",
                t,
            )
        }

        if (!result.isValid) {
            val preview = result.errors.take(3).joinToString("; ") {
                "${it.code} ${it.objectPath}: ${it.message}"
            }
            val suffix = if (result.errors.size > 3) "; ..." else ""
            throw ImportPreflightException(
                "Schema validation failed for '$schemaPath': $preview$suffix"
            )
        }
    }

    private fun resolveTableOrder(
        schemaPath: Path,
        schema: SchemaDefinition,
        input: ImportInput.Directory,
        format: DataExportFormat,
    ): List<String> {
        val candidateTables = resolveCandidateTables(input, format)
        if (candidateTables.isEmpty()) return emptyList()

        val selectedTables = linkedMapOf<String, TableDefinition>()
        for ((name, table) in schema.tables) {
            if (name in candidateTables) {
                selectedTables[name] = table
            }
        }

        val missingInSchema = candidateTables.filterNot(selectedTables::containsKey)
        if (missingInSchema.isNotEmpty()) {
            throw ImportPreflightException(
                "Schema file '$schemaPath' does not define tables required for directory import: " +
                    missingInSchema.joinToString()
            )
        }

        val sortResult = topologicalSort(selectedTables)
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

    private fun resolveCandidateTables(
        input: ImportInput.Directory,
        format: DataExportFormat,
    ): List<String> {
        val suffix = ".${format.cliName}"
        val candidates = linkedMapOf<String, Path>()

        Files.newDirectoryStream(input.path).use { entries ->
            for (entry in entries) {
                val fileName = entry.fileName.toString()
                if (!Files.isRegularFile(entry) || !fileName.endsWith(suffix)) continue
                candidates[fileName.removeSuffix(suffix)] = entry
            }
        }

        val tableFilter = input.tableFilter
        if (tableFilter != null) {
            val missing = tableFilter.filterNot(candidates::containsKey)
            if (missing.isNotEmpty()) {
                throw ImportPreflightException(
                    "Directory import filter references tables without matching files: ${missing.joinToString()}"
                )
            }
            return tableFilter
        }

        return candidates.keys.toList()
    }

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

    private fun topologicalSort(tables: Map<String, TableDefinition>): SortResult {
        val deps = linkedMapOf<String, MutableSet<String>>()
        val allEdges = mutableListOf<CircularEdge>()

        for ((tableName, table) in tables) {
            deps.getOrPut(tableName) { linkedSetOf() }
            for ((columnName, column) in table.columns) {
                val reference = column.references ?: continue
                if (reference.table != tableName && reference.table in tables) {
                    deps.getOrPut(tableName) { linkedSetOf() }.add(reference.table)
                    allEdges += CircularEdge(
                        fromTable = tableName,
                        fromColumn = columnName,
                        toTable = reference.table,
                        toColumn = reference.column,
                    )
                }
            }
        }

        val inDegree = linkedMapOf<String, Int>()
        for (name in tables.keys) {
            inDegree[name] = deps[name]?.size ?: 0
        }

        val queue = ArrayDeque(inDegree.filterValues { it == 0 }.keys)
        val sorted = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted += current
            for ((table, depSet) in deps) {
                if (depSet.remove(current)) {
                    val remaining = (inDegree.getValue(table) - 1).also { inDegree[table] = it }
                    if (remaining == 0) {
                        queue.addLast(table)
                    }
                }
            }
        }

        val remaining = tables.keys - sorted.toSet()
        val circularEdges = if (remaining.isEmpty()) {
            emptyList()
        } else {
            allEdges.filter { it.fromTable in remaining && it.toTable in remaining }
        }

        return SortResult(sorted = sorted, circularEdges = circularEdges)
    }
}
