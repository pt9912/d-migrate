package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.ViewDefinition

class SchemaComparator {

    fun compare(left: SchemaDefinition, right: SchemaDefinition): SchemaDiff {
        val metadataDiff = compareMetadata(left, right)
        val customTypeDiffs = compareCustomTypes(left, right)
        val tableDiffs = compareTables(left, right)
        val viewDiffs = compareViews(left, right)
        val sequenceDiffs = compareSequences(left, right)
        val functionDiffs = compareFunctions(left, right)
        val procedureDiffs = compareProcedures(left, right)
        val triggerDiffs = compareTriggers(left, right)

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

    // --- Custom Types (ENUM, DOMAIN, COMPOSITE) ---

    private data class CustomTypeDiffs(
        val added: List<NamedCustomType>,
        val removed: List<NamedCustomType>,
        val changed: List<CustomTypeDiff>,
    )

    private fun compareCustomTypes(left: SchemaDefinition, right: SchemaDefinition): CustomTypeDiffs {
        val leftNames = left.customTypes.keys
        val rightNames = right.customTypes.keys

        val added = (rightNames - leftNames).sorted().map {
            NamedCustomType(it, right.customTypes.getValue(it))
        }
        val removed = (leftNames - rightNames).sorted().map {
            NamedCustomType(it, left.customTypes.getValue(it))
        }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareCustomType(name, left.customTypes.getValue(name), right.customTypes.getValue(name))
        }

        return CustomTypeDiffs(added, removed, changed)
    }

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

    // --- Tables ---

    private data class TableDiffs(
        val added: List<NamedTable>,
        val removed: List<NamedTable>,
        val changed: List<TableDiff>,
    )

    private fun compareTables(left: SchemaDefinition, right: SchemaDefinition): TableDiffs {
        val leftNames = left.tables.keys
        val rightNames = right.tables.keys

        val added = (rightNames - leftNames).sorted().map {
            NamedTable(it, right.tables.getValue(it))
        }
        val removed = (leftNames - rightNames).sorted().map {
            NamedTable(it, left.tables.getValue(it))
        }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareTable(name, left.tables.getValue(name), right.tables.getValue(name))
        }

        return TableDiffs(added, removed, changed)
    }

    private fun compareTable(
        name: String,
        left: TableDefinition,
        right: TableDefinition,
    ): TableDiff? {
        // Normalize first so column comparison knows which unique/references are absorbed
        val leftNorm = normalizeConstraints(left)
        val rightNorm = normalizeConstraints(right)

        val absorbedColumns = AbsorbedColumns(
            uniqueLeft = leftNorm.singleColumnUnique,
            uniqueRight = rightNorm.singleColumnUnique,
            fkLeft = leftNorm.singleColumnForeignKeys.keys,
            fkRight = rightNorm.singleColumnForeignKeys.keys,
        )

        val columnDiffs = compareColumns(left, right, absorbedColumns)
        val pkDiff = if (left.primaryKey == right.primaryKey) null
            else ValueChange(left.primaryKey, right.primaryKey)

        val indexDiffs = compareIndices(left.indices, right.indices)
        val constraintDiffs = compareConstraints(leftNorm, rightNorm)
        val metadataDiff = if (left.metadata == right.metadata) null
            else ValueChange(left.metadata, right.metadata)

        val diff = TableDiff(
            name = name,
            columnsAdded = columnDiffs.added,
            columnsRemoved = columnDiffs.removed,
            columnsChanged = columnDiffs.changed,
            primaryKey = pkDiff,
            indicesAdded = indexDiffs.added,
            indicesRemoved = indexDiffs.removed,
            indicesChanged = indexDiffs.changed,
            constraintsAdded = constraintDiffs.added,
            constraintsRemoved = constraintDiffs.removed,
            constraintsChanged = constraintDiffs.changed,
            metadata = metadataDiff,
        )

        return if (diff.hasChanges()) diff else null
    }

    // --- Columns ---

    private data class AbsorbedColumns(
        val uniqueLeft: Set<String>,
        val uniqueRight: Set<String>,
        val fkLeft: Set<String>,
        val fkRight: Set<String>,
    )

    private data class ColumnDiffs(
        val added: Map<String, ColumnDefinition>,
        val removed: Map<String, ColumnDefinition>,
        val changed: List<ColumnDiff>,
    )

    private fun compareColumns(
        left: TableDefinition,
        right: TableDefinition,
        absorbed: AbsorbedColumns,
    ): ColumnDiffs {
        val leftNames = left.columns.keys
        val rightNames = right.columns.keys

        val added = (rightNames - leftNames).sorted()
            .associateWith { projectColumn(right.columns.getValue(it)) }
        val removed = (leftNames - rightNames).sorted()
            .associateWith { projectColumn(left.columns.getValue(it)) }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareColumn(name, left.columns.getValue(name), right.columns.getValue(name), absorbed)
        }

        return ColumnDiffs(added, removed, changed)
    }

    private fun compareColumn(
        name: String,
        left: ColumnDefinition,
        right: ColumnDefinition,
        absorbed: AbsorbedColumns,
    ): ColumnDiff? {
        val typeDiff = valueChangeOrNull(left.type, right.type)
        val requiredDiff = valueChangeOrNull(left.required, right.required)
        val defaultDiff = if (left.default == right.default) null
            else ValueChange(left.default, right.default)

        // unique/references: only report at column level when NOT absorbed by
        // single-column normalization (§6.3, §4.4). In practice, normalization
        // always absorbs these for single-column cases. The fields exist for
        // model completeness and forward-compatibility.
        val uniqueAbsorbed = name in absorbed.uniqueLeft || name in absorbed.uniqueRight
        val uniqueDiff = if (uniqueAbsorbed) null
            else valueChangeOrNull(left.unique, right.unique)

        val fkAbsorbed = name in absorbed.fkLeft || name in absorbed.fkRight
        val refDiff = if (fkAbsorbed) null
            else if (left.references == right.references) null
            else ValueChange(left.references, right.references)

        if (typeDiff == null && requiredDiff == null && defaultDiff == null &&
            uniqueDiff == null && refDiff == null) return null
        return ColumnDiff(name, typeDiff, requiredDiff, defaultDiff, uniqueDiff, refDiff)
    }

    private fun projectColumn(col: ColumnDefinition): ColumnDefinition =
        col.copy(unique = false, references = null)

    // --- Single-Column UNIQUE/FK Normalization ---

    private data class NormalizedConstraints(
        val singleColumnUnique: Set<String>,
        val singleColumnForeignKeys: Map<String, ForeignKeySignature>,
        val multiColumnConstraints: Map<String, ConstraintDefinition>,
    )

    private data class ForeignKeySignature(
        val column: String,
        val refTable: String,
        val refColumn: String,
        val onDelete: dev.dmigrate.core.model.ReferentialAction?,
        val onUpdate: dev.dmigrate.core.model.ReferentialAction?,
    )

    private fun normalizeConstraints(table: TableDefinition): NormalizedConstraints {
        val singleUnique = mutableSetOf<String>()
        val singleFk = mutableMapOf<String, ForeignKeySignature>()
        val multi = mutableMapOf<String, ConstraintDefinition>()

        // Collect from column-level definitions
        for ((colName, col) in table.columns) {
            if (col.unique) {
                singleUnique.add(colName)
            }
            col.references?.let { ref ->
                singleFk[colName] = ForeignKeySignature(
                    column = colName,
                    refTable = ref.table,
                    refColumn = ref.column,
                    onDelete = ref.onDelete,
                    onUpdate = ref.onUpdate,
                )
            }
        }

        // Merge constraint-level definitions
        for (constraint in table.constraints) {
            when {
                constraint.type == ConstraintType.UNIQUE &&
                    constraint.columns?.size == 1 -> {
                    singleUnique.add(constraint.columns.first())
                }

                constraint.type == ConstraintType.FOREIGN_KEY &&
                    constraint.columns?.size == 1 &&
                    constraint.references != null &&
                    constraint.references.columns.size == 1 -> {
                    val colName = constraint.columns.first()
                    val sig = ForeignKeySignature(
                        column = colName,
                        refTable = constraint.references.table,
                        refColumn = constraint.references.columns.first(),
                        onDelete = constraint.references.onDelete,
                        onUpdate = constraint.references.onUpdate,
                    )
                    val existing = singleFk[colName]
                    if (existing != null && existing != sig) {
                        // Column-level and constraint-level FK conflict on same column.
                        // Keep column-level in singleFk; preserve constraint as multi.
                        multi[constraint.name] = constraint
                    } else {
                        singleFk[colName] = sig
                    }
                }

                constraint.type == ConstraintType.CHECK ||
                    constraint.type == ConstraintType.EXCLUDE -> {
                    // Not in MVP scope — skip silently
                }

                else -> {
                    multi[constraint.name] = constraint
                }
            }
        }

        return NormalizedConstraints(singleUnique, singleFk, multi)
    }

    // --- Constraints ---

    private data class ConstraintDiffResult(
        val added: List<ConstraintDefinition>,
        val removed: List<ConstraintDefinition>,
        val changed: List<ValueChange<ConstraintDefinition>>,
    )

    private fun compareConstraints(
        left: NormalizedConstraints,
        right: NormalizedConstraints,
    ): ConstraintDiffResult {
        val added = mutableListOf<ConstraintDefinition>()
        val removed = mutableListOf<ConstraintDefinition>()
        val changed = mutableListOf<ValueChange<ConstraintDefinition>>()

        // Single-column UNIQUE: compare as sets of column names
        val uniqueAdded = right.singleColumnUnique - left.singleColumnUnique
        val uniqueRemoved = left.singleColumnUnique - right.singleColumnUnique

        for (col in uniqueAdded.sorted()) {
            added.add(syntheticUniqueConstraint(col))
        }
        for (col in uniqueRemoved.sorted()) {
            removed.add(syntheticUniqueConstraint(col))
        }

        // Single-column FK: compare by column name
        val fkLeftCols = left.singleColumnForeignKeys.keys
        val fkRightCols = right.singleColumnForeignKeys.keys

        for (col in (fkRightCols - fkLeftCols).sorted()) {
            added.add(syntheticFkConstraint(right.singleColumnForeignKeys.getValue(col)))
        }
        for (col in (fkLeftCols - fkRightCols).sorted()) {
            removed.add(syntheticFkConstraint(left.singleColumnForeignKeys.getValue(col)))
        }
        for (col in (fkLeftCols intersect fkRightCols).sorted()) {
            val l = left.singleColumnForeignKeys.getValue(col)
            val r = right.singleColumnForeignKeys.getValue(col)
            if (l != r) {
                changed.add(ValueChange(syntheticFkConstraint(l), syntheticFkConstraint(r)))
            }
        }

        // Multi-column constraints: compare by name
        val multiLeftNames = left.multiColumnConstraints.keys
        val multiRightNames = right.multiColumnConstraints.keys

        for (name in (multiRightNames - multiLeftNames).sorted()) {
            added.add(right.multiColumnConstraints.getValue(name))
        }
        for (name in (multiLeftNames - multiRightNames).sorted()) {
            removed.add(left.multiColumnConstraints.getValue(name))
        }
        for (name in (multiLeftNames intersect multiRightNames).sorted()) {
            val l = left.multiColumnConstraints.getValue(name)
            val r = right.multiColumnConstraints.getValue(name)
            if (l != r) {
                changed.add(ValueChange(l, r))
            }
        }

        return ConstraintDiffResult(added, removed, changed)
    }

    private fun syntheticUniqueConstraint(column: String) = ConstraintDefinition(
        name = "_unique_$column",
        type = ConstraintType.UNIQUE,
        columns = listOf(column),
    )

    private fun syntheticFkConstraint(sig: ForeignKeySignature) = ConstraintDefinition(
        name = "_fk_${sig.column}",
        type = ConstraintType.FOREIGN_KEY,
        columns = listOf(sig.column),
        references = ConstraintReferenceDefinition(
            table = sig.refTable,
            columns = listOf(sig.refColumn),
            onDelete = sig.onDelete,
            onUpdate = sig.onUpdate,
        ),
    )

    // --- Indices ---

    private data class IndexDiffResult(
        val added: List<IndexDefinition>,
        val removed: List<IndexDefinition>,
        val changed: List<ValueChange<IndexDefinition>>,
    )

    private fun compareIndices(
        left: List<IndexDefinition>,
        right: List<IndexDefinition>,
    ): IndexDiffResult {
        val leftByKey = left.associateBy { indexKey(it) }
        val rightByKey = right.associateBy { indexKey(it) }

        val leftKeys = leftByKey.keys
        val rightKeys = rightByKey.keys

        val added = (rightKeys - leftKeys).sorted().map { rightByKey.getValue(it) }
        val removed = (leftKeys - rightKeys).sorted().map { leftByKey.getValue(it) }
        val changed = (leftKeys intersect rightKeys).sorted().mapNotNull { key ->
            val l = leftByKey.getValue(key)
            val r = rightByKey.getValue(key)
            if (l == r) null else ValueChange(l, r)
        }

        return IndexDiffResult(added, removed, changed)
    }

    private fun indexKey(index: IndexDefinition): String =
        index.name ?: "idx:${index.columns.joinToString(",")}:${index.type}:${index.unique}"

    // --- Views ---

    private data class ViewDiffs(
        val added: List<NamedView>,
        val removed: List<NamedView>,
        val changed: List<ViewDiff>,
    )

    private fun compareViews(left: SchemaDefinition, right: SchemaDefinition): ViewDiffs {
        val leftNames = left.views.keys
        val rightNames = right.views.keys

        val added = (rightNames - leftNames).sorted().map {
            NamedView(it, right.views.getValue(it))
        }
        val removed = (leftNames - rightNames).sorted().map {
            NamedView(it, left.views.getValue(it))
        }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareView(name, left.views.getValue(name), right.views.getValue(name))
        }

        return ViewDiffs(added, removed, changed)
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

    // --- Sequences ---

    private data class SequenceDiffs(
        val added: List<NamedSequence>,
        val removed: List<NamedSequence>,
        val changed: List<SequenceDiff>,
    )

    private fun compareSequences(left: SchemaDefinition, right: SchemaDefinition): SequenceDiffs {
        val leftNames = left.sequences.keys
        val rightNames = right.sequences.keys

        val added = (rightNames - leftNames).sorted().map {
            NamedSequence(it, right.sequences.getValue(it))
        }
        val removed = (leftNames - rightNames).sorted().map {
            NamedSequence(it, left.sequences.getValue(it))
        }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareSequence(name, left.sequences.getValue(name), right.sequences.getValue(name))
        }

        return SequenceDiffs(added, removed, changed)
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

    // --- Functions ---

    private data class FunctionDiffs(
        val added: List<NamedFunction>,
        val removed: List<NamedFunction>,
        val changed: List<FunctionDiff>,
    )

    private fun compareFunctions(left: SchemaDefinition, right: SchemaDefinition): FunctionDiffs {
        val leftNames = left.functions.keys
        val rightNames = right.functions.keys

        val added = (rightNames - leftNames).sorted().map {
            NamedFunction(it, right.functions.getValue(it))
        }
        val removed = (leftNames - rightNames).sorted().map {
            NamedFunction(it, left.functions.getValue(it))
        }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareFunction(name, left.functions.getValue(name), right.functions.getValue(name))
        }

        return FunctionDiffs(added, removed, changed)
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

    // --- Procedures ---

    private data class ProcedureDiffs(
        val added: List<NamedProcedure>,
        val removed: List<NamedProcedure>,
        val changed: List<ProcedureDiff>,
    )

    private fun compareProcedures(left: SchemaDefinition, right: SchemaDefinition): ProcedureDiffs {
        val leftNames = left.procedures.keys
        val rightNames = right.procedures.keys

        val added = (rightNames - leftNames).sorted().map {
            NamedProcedure(it, right.procedures.getValue(it))
        }
        val removed = (leftNames - rightNames).sorted().map {
            NamedProcedure(it, left.procedures.getValue(it))
        }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareProcedure(name, left.procedures.getValue(name), right.procedures.getValue(name))
        }

        return ProcedureDiffs(added, removed, changed)
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

    // --- Triggers ---

    private data class TriggerDiffs(
        val added: List<NamedTrigger>,
        val removed: List<NamedTrigger>,
        val changed: List<TriggerDiff>,
    )

    private fun compareTriggers(left: SchemaDefinition, right: SchemaDefinition): TriggerDiffs {
        val leftNames = left.triggers.keys
        val rightNames = right.triggers.keys

        val added = (rightNames - leftNames).sorted().map {
            NamedTrigger(it, right.triggers.getValue(it))
        }
        val removed = (leftNames - rightNames).sorted().map {
            NamedTrigger(it, left.triggers.getValue(it))
        }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareTrigger(name, left.triggers.getValue(name), right.triggers.getValue(name))
        }

        return TriggerDiffs(added, removed, changed)
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

    private fun <T> valueChangeOrNull(before: T, after: T): ValueChange<T>? =
        if (before == after) null else ValueChange(before, after)
}
