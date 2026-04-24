package dev.dmigrate.driver

import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.dependency.FkEdge

data class TopologicalSortResult(
    val sorted: List<Pair<String, TableDefinition>>,
    val circularEdges: List<CircularFkEdge>,
)

data class ViewSortResult(
    val sorted: Map<String, ViewDefinition>,
    val notes: List<TransformationNote> = emptyList(),
)

data class CircularFkEdge(
    val fromTable: String,
    val fromColumn: String,
    val toTable: String,
    val toColumn: String,
)

internal object DdlGenerationSupport {

    fun topologicalSort(tables: Map<String, TableDefinition>): TopologicalSortResult {
        val edges = tables.flatMap { (tableName, table) ->
            table.columns.mapNotNull { (columnName, column) ->
                column.references?.let { reference ->
                    FkEdge(tableName, columnName, reference.table, reference.column)
                }
            }
        }
        val result = dev.dmigrate.core.dependency.sortTablesByDependency(tables.keys, edges)
        return TopologicalSortResult(
            sorted = result.sorted.map { it to tables.getValue(it) },
            circularEdges = result.circularEdges.map {
                CircularFkEdge(it.fromTable, it.fromColumn ?: "", it.toTable, it.toColumn ?: "")
            },
        )
    }

    fun sortViewsByDependencies(views: Map<String, ViewDefinition>): ViewSortResult {
        if (views.isEmpty()) return ViewSortResult(views)

        val viewNames = views.keys
        val originalOrder = views.keys.toList()
        val dependencies = views.mapValuesTo(linkedMapOf()) { (name, view) ->
            (
                ViewDependencyResolver.declaredViewDependencies(name, view, viewNames) +
                    ViewDependencyResolver.inferViewDependenciesFromQuery(name, view.query, viewNames)
                ).toMutableSet()
        }
        val inDegree = dependencies.mapValuesTo(mutableMapOf()) { (_, depSet) -> depSet.size }
        val queue = ArrayDeque(originalOrder.filter { inDegree[it] == 0 })
        val sorted = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted += current
            for ((name, depSet) in dependencies) {
                if (depSet.remove(current)) {
                    inDegree[name] = (inDegree[name] ?: 1) - 1
                    if (inDegree[name] == 0) queue.add(name)
                }
            }
        }

        val remaining = originalOrder.filter { it !in sorted }
        val ordered = linkedMapOf<String, ViewDefinition>()
        for (name in sorted + remaining) {
            ordered[name] = views.getValue(name)
        }
        val notes = if (remaining.isEmpty()) emptyList() else listOf(
            TransformationNote(
                type = NoteType.WARNING,
                code = "W113",
                objectName = "views",
                message = buildString {
                    append("Views contain unresolved or circular dependencies: ")
                    append(remaining.joinToString(", "))
                    append(". Original order is preserved for the remaining views.")
                },
                hint = "Declare consistent view dependencies or adjust view definitions to break the cycle.",
            )
        )
        return ViewSortResult(ordered, notes)
    }
}

internal fun registerBlockedTable(
    name: String,
    blockNote: TransformationNote,
    blockedTables: MutableSet<String>,
    skipped: MutableList<SkippedObject>,
) {
    blockedTables += name
    skipped += SkippedObject(
        type = "table",
        name = name,
        reason = blockNote.message,
        code = blockNote.code,
        hint = blockNote.hint,
        phase = DdlPhase.PRE_DATA,
    )
}

internal fun List<DdlStatement>.withPhase(phase: DdlPhase): List<DdlStatement> =
    map { it.copy(phase = phase) }

internal fun tagNewSkips(skipped: MutableList<SkippedObject>, fromIndex: Int, phase: DdlPhase) {
    for (index in fromIndex until skipped.size) {
        skipped[index] = skipped[index].copy(phase = phase)
    }
}

internal fun classifyViewsByPhase(
    views: Map<String, ViewDefinition>,
    functionNames: Set<String>,
): Triple<Map<String, ViewDefinition>, Map<String, ViewDefinition>, List<TransformationNote>> =
    ViewPhaseClassifier.classify(
        views,
        functionNames,
        ViewDependencyResolver::declaredViewDependencies,
        ViewDependencyResolver::inferViewDependenciesFromQuery,
    )
