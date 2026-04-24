package dev.dmigrate.core.diff

import dev.dmigrate.core.model.*

class SchemaComparator {

    private val tableComparator = TableComparator()

    fun compare(left: SchemaDefinition, right: SchemaDefinition): SchemaDiff {
        val metadataDiff = compareMetadata(left, right)
        val customTypeDiffs = compareObjectMaps(
            left.customTypes, right.customTypes, ::NamedCustomType, ::compareCustomType,
        )
        val tableDiffs = tableComparator.compareTables(left, right)
        val viewDiffs = compareObjectMaps(
            left.views, right.views, ::NamedView, ::compareView,
        )
        val sequenceDiffs = compareObjectMaps(
            left.sequences, right.sequences, ::NamedSequence, ::compareSequence,
        )
        val functionDiffs = compareObjectMaps(
            left.functions, right.functions, ::NamedFunction, ::compareFunction,
        )
        val procedureDiffs = compareObjectMaps(
            left.procedures, right.procedures, ::NamedProcedure, ::compareProcedure,
        )
        val triggerDiffs = compareObjectMaps(
            left.triggers, right.triggers, ::NamedTrigger, ::compareTrigger,
        )

        return SchemaDiff(
            schemaMetadata = metadataDiff,
            tablesAdded = tableDiffs.added,
            tablesRemoved = tableDiffs.removed,
            tablesChanged = tableDiffs.changed,
            customTypesAdded = customTypeDiffs.added,
            customTypesRemoved = customTypeDiffs.removed,
            customTypesChanged = customTypeDiffs.changed,
            viewsAdded = viewDiffs.added,
            viewsRemoved = viewDiffs.removed,
            viewsChanged = viewDiffs.changed,
            sequencesAdded = sequenceDiffs.added,
            sequencesRemoved = sequenceDiffs.removed,
            sequencesChanged = sequenceDiffs.changed,
            functionsAdded = functionDiffs.added,
            functionsRemoved = functionDiffs.removed,
            functionsChanged = functionDiffs.changed,
            proceduresAdded = procedureDiffs.added,
            proceduresRemoved = procedureDiffs.removed,
            proceduresChanged = procedureDiffs.changed,
            triggersAdded = triggerDiffs.added,
            triggersRemoved = triggerDiffs.removed,
            triggersChanged = triggerDiffs.changed,
        )
    }

    private fun compareMetadata(
        left: SchemaDefinition,
        right: SchemaDefinition,
    ): SchemaMetadataDiff? {
        val nameDiff = valueChangeOrNull(left.name, right.name)
        val versionDiff = valueChangeOrNull(left.version, right.version)
        if (nameDiff == null && versionDiff == null) return null
        return SchemaMetadataDiff(name = nameDiff, version = versionDiff)
    }

    // --- Generic map-diff helper ---

    private data class DiffResult<N, D>(
        val added: List<N>,
        val removed: List<N>,
        val changed: List<D>,
    )

    private inline fun <T, N, D> compareObjectMaps(
        leftMap: Map<String, T>,
        rightMap: Map<String, T>,
        wrapNamed: (String, T) -> N,
        compareDetail: (String, T, T) -> D?,
    ): DiffResult<N, D> {
        val leftNames = leftMap.keys
        val rightNames = rightMap.keys

        val added = (rightNames - leftNames).sorted().map { wrapNamed(it, rightMap.getValue(it)) }
        val removed = (leftNames - rightNames).sorted().map { wrapNamed(it, leftMap.getValue(it)) }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareDetail(name, leftMap.getValue(name), rightMap.getValue(name))
        }

        return DiffResult(added, removed, changed)
    }

    // --- Detail comparisons per object type ---

    private fun compareCustomType(
        name: String,
        left: CustomTypeDefinition,
        right: CustomTypeDefinition,
    ): CustomTypeDiff? {
        val diff = CustomTypeDiff(
            name = name,
            kind = valueChangeOrNull(left.kind, right.kind),
            values = if (left.values.orEmpty() == right.values.orEmpty()) null
                else ValueChange(left.values.orEmpty(), right.values.orEmpty()),
            baseType = valueChangeOrNull(left.baseType, right.baseType),
            precision = valueChangeOrNull(left.precision, right.precision),
            scale = valueChangeOrNull(left.scale, right.scale),
            check = valueChangeOrNull(left.check, right.check),
            description = valueChangeOrNull(left.description, right.description),
            fields = if (left.fields == right.fields) null
                else ValueChange(left.fields, right.fields),
        )
        return if (diff.hasChanges()) diff else null
    }

    private fun compareView(
        name: String,
        left: ViewDefinition,
        right: ViewDefinition,
    ): ViewDiff? {
        val diff = ViewDiff(
            name = name,
            materialized = valueChangeOrNull(left.materialized, right.materialized),
            refresh = valueChangeOrNull(left.refresh, right.refresh),
            query = valueChangeOrNull(left.query, right.query),
            sourceDialect = valueChangeOrNull(left.sourceDialect, right.sourceDialect),
        )
        return if (diff.hasChanges()) diff else null
    }

    private fun compareSequence(
        name: String,
        left: SequenceDefinition,
        right: SequenceDefinition,
    ): SequenceDiff? {
        val diff = SequenceDiff(
            name = name,
            start = valueChangeOrNull(left.start, right.start),
            increment = valueChangeOrNull(left.increment, right.increment),
            minValue = valueChangeOrNull(left.minValue, right.minValue),
            maxValue = valueChangeOrNull(left.maxValue, right.maxValue),
            cycle = valueChangeOrNull(left.cycle, right.cycle),
            cache = valueChangeOrNull(left.cache, right.cache),
        )
        return if (diff.hasChanges()) diff else null
    }

    private fun compareFunction(
        name: String,
        left: FunctionDefinition,
        right: FunctionDefinition,
    ): FunctionDiff? {
        val diff = FunctionDiff(
            name = name,
            parameters = if (left.parameters == right.parameters) null
                else ValueChange(left.parameters, right.parameters),
            returns = valueChangeOrNull(left.returns, right.returns),
            language = valueChangeOrNull(left.language, right.language),
            deterministic = valueChangeOrNull(left.deterministic, right.deterministic),
            body = valueChangeOrNull(left.body, right.body),
            sourceDialect = valueChangeOrNull(left.sourceDialect, right.sourceDialect),
        )
        return if (diff.hasChanges()) diff else null
    }

    private fun compareProcedure(
        name: String,
        left: ProcedureDefinition,
        right: ProcedureDefinition,
    ): ProcedureDiff? {
        val diff = ProcedureDiff(
            name = name,
            parameters = if (left.parameters == right.parameters) null
                else ValueChange(left.parameters, right.parameters),
            language = valueChangeOrNull(left.language, right.language),
            body = valueChangeOrNull(left.body, right.body),
            sourceDialect = valueChangeOrNull(left.sourceDialect, right.sourceDialect),
        )
        return if (diff.hasChanges()) diff else null
    }

    private fun compareTrigger(
        name: String,
        left: TriggerDefinition,
        right: TriggerDefinition,
    ): TriggerDiff? {
        val diff = TriggerDiff(
            name = name,
            table = valueChangeOrNull(left.table, right.table),
            event = valueChangeOrNull(left.event, right.event),
            timing = valueChangeOrNull(left.timing, right.timing),
            forEach = valueChangeOrNull(left.forEach, right.forEach),
            condition = valueChangeOrNull(left.condition, right.condition),
            body = valueChangeOrNull(left.body, right.body),
            sourceDialect = valueChangeOrNull(left.sourceDialect, right.sourceDialect),
        )
        return if (diff.hasChanges()) diff else null
    }

    // --- Helpers ---

    private fun <T> valueChangeOrNull(before: T, after: T): ValueChange<T>? = diffValueChangeOrNull(before, after)
}

internal fun <T> diffValueChangeOrNull(before: T, after: T): ValueChange<T>? =
    if (before == after) null else ValueChange(before, after)
