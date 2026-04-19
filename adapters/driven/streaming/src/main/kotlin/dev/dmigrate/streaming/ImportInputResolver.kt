package dev.dmigrate.streaming

import dev.dmigrate.format.data.DataExportFormat
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Resolves [ImportInput] into a list of per-table input streams.
 *
 * Handles stdin, single-file, and directory inputs. For directories,
 * discovers matching files by format extension, validates filters and
 * ordering, and detects duplicate candidates.
 */
internal class ImportInputResolver {

    fun resolve(
        input: ImportInput,
        format: DataExportFormat,
    ): List<ResolvedTableInput> =
        when (input) {
            is ImportInput.Stdin ->
                listOf(
                    ResolvedTableInput(
                        table = input.table,
                        openInput = { input.input },
                    )
                )

            is ImportInput.SingleFile ->
                listOf(
                    ResolvedTableInput(
                        table = input.table,
                        openInput = { Files.newInputStream(input.path) },
                    )
                )

            is ImportInput.Directory -> resolveDirectoryInputs(input, format)
        }

    private fun resolveDirectoryInputs(
        input: ImportInput.Directory,
        format: DataExportFormat,
    ): List<ResolvedTableInput> {
        require(Files.isDirectory(input.path)) {
            "ImportInput.Directory path '${input.path}' is not a directory"
        }
        val tableFilter = input.tableFilter
        val tableOrder = input.tableOrder

        val suffixes = format.fileExtensions.map { ".$it" }
        val candidates = Files.list(input.path).use { entries ->
            val result = linkedMapOf<String, Path>()
            val candidateFiles = linkedMapOf<String, MutableList<String>>()
            entries
                .filter(Files::isRegularFile)
                .forEach { path ->
                    val fileName = path.fileName.name
                    val matchedSuffix = suffixes.firstOrNull { fileName.endsWith(it) }
                    if (matchedSuffix != null) {
                        val tableName = fileName.removeSuffix(matchedSuffix)
                        candidateFiles.getOrPut(tableName) { mutableListOf() }.add(fileName)
                        result.putIfAbsent(tableName, path)
                    }
                }
            val selectedTables = tableFilter ?: result.keys
            val duplicateDetails = duplicateCandidateDetails(candidateFiles, selectedTables)
            require(duplicateDetails.isEmpty()) {
                "ImportInput.Directory contains multiple files for the same table: ${duplicateDetails.joinToString("; ")}"
            }
            result
        }.toMutableMap()

        if (tableFilter != null) {
            val missing = tableFilter.filterNot(candidates::containsKey)
            require(missing.isEmpty()) {
                "ImportInput.Directory.tableFilter references tables without matching files: ${missing.joinToString()}"
            }
            candidates.keys.retainAll(tableFilter.toSet())
        }

        val orderedTables = if (tableOrder != null) {
            val duplicates = tableOrder.groupBy { it }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) {
                "ImportInput.Directory.tableOrder contains duplicate tables: ${duplicates.joinToString()}"
            }
            val missing = tableOrder.filterNot(candidates::containsKey)
            require(missing.isEmpty()) {
                "ImportInput.Directory.tableOrder references tables without matching files: ${missing.joinToString()}"
            }
            val extras = candidates.keys - tableOrder.toSet()
            require(extras.isEmpty()) {
                "ImportInput.Directory.tableOrder must cover all candidate tables, missing order for: ${extras.joinToString()}"
            }
            tableOrder
        } else {
            candidates.keys.sorted()
        }

        return orderedTables.map { table ->
            ResolvedTableInput(
                table = table,
                openInput = { Files.newInputStream(candidates.getValue(table)) },
            )
        }
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
}

internal data class ResolvedTableInput(
    val table: String,
    val openInput: () -> InputStream,
)
