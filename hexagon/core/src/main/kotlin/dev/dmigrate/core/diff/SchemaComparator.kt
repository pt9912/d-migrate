package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition

class SchemaComparator {

    fun compare(left: SchemaDefinition, right: SchemaDefinition): SchemaDiff {
        val metadataDiff = compareMetadata(left, right)
        val enumDiffs = compareEnumTypes(left, right)
        val tableDiffs = compareTables(left, right)
        val viewDiffs = compareViews(left, right)

        return SchemaDiff(
            schemaMetadata = metadataDiff,
            tablesAdded = tableDiffs.added,
            tablesRemoved = tableDiffs.removed,
            tablesChanged = tableDiffs.changed,
            enumTypesAdded = enumDiffs.added,
            enumTypesRemoved = enumDiffs.removed,
            enumTypesChanged = enumDiffs.changed,
            viewsAdded = viewDiffs.added,
            viewsRemoved = viewDiffs.removed,
            viewsChanged = viewDiffs.changed,
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

    // --- Enum Custom Types ---

    private data class EnumDiffs(
        val added: List<NamedEnumType>,
        val removed: List<NamedEnumType>,
        val changed: List<EnumTypeDiff>,
    )

    private fun compareEnumTypes(left: SchemaDefinition, right: SchemaDefinition): EnumDiffs {
        val leftEnums = left.customTypes.filter { it.value.kind == CustomTypeKind.ENUM }
        val rightEnums = right.customTypes.filter { it.value.kind == CustomTypeKind.ENUM }

        val leftNames = leftEnums.keys
        val rightNames = rightEnums.keys

        val added = (rightNames - leftNames).sorted().map {
            NamedEnumType(it, rightEnums.getValue(it))
        }
        val removed = (leftNames - rightNames).sorted().map {
            NamedEnumType(it, leftEnums.getValue(it))
        }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            val l = leftEnums.getValue(name).values.orEmpty()
            val r = rightEnums.getValue(name).values.orEmpty()
            if (l == r) null else EnumTypeDiff(name, ValueChange(l, r))
        }

        return EnumDiffs(added, removed, changed)
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
        val columnDiffs = compareColumns(left, right)
        val pkDiff = if (left.primaryKey == right.primaryKey) null
            else ValueChange(left.primaryKey, right.primaryKey)

        val leftNorm = normalizeConstraints(left)
        val rightNorm = normalizeConstraints(right)

        val indexDiffs = compareIndices(left.indices, right.indices)
        val constraintDiffs = compareConstraints(leftNorm, rightNorm)

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
        )

        return if (diff.hasChanges()) diff else null
    }

    // --- Columns ---

    private data class ColumnDiffs(
        val added: Map<String, ColumnDefinition>,
        val removed: Map<String, ColumnDefinition>,
        val changed: List<ColumnDiff>,
    )

    private fun compareColumns(left: TableDefinition, right: TableDefinition): ColumnDiffs {
        val leftNames = left.columns.keys
        val rightNames = right.columns.keys

        val added = (rightNames - leftNames).sorted()
            .associateWith { right.columns.getValue(it) }
        val removed = (leftNames - rightNames).sorted()
            .associateWith { left.columns.getValue(it) }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareColumn(name, left.columns.getValue(name), right.columns.getValue(name))
        }

        return ColumnDiffs(added, removed, changed)
    }

    private fun compareColumn(
        name: String,
        left: ColumnDefinition,
        right: ColumnDefinition,
    ): ColumnDiff? {
        val typeDiff = valueChangeOrNull(left.type, right.type)
        val requiredDiff = valueChangeOrNull(left.required, right.required)
        val defaultDiff = if (left.default == right.default) null
            else ValueChange(left.default, right.default)

        if (typeDiff == null && requiredDiff == null && defaultDiff == null) return null
        return ColumnDiff(name, typeDiff, requiredDiff, defaultDiff)
    }

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
                    singleFk[colName] = ForeignKeySignature(
                        column = colName,
                        refTable = constraint.references.table,
                        refColumn = constraint.references.columns.first(),
                        onDelete = constraint.references.onDelete,
                        onUpdate = constraint.references.onUpdate,
                    )
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

    // --- Helpers ---

    private fun <T> valueChangeOrNull(before: T, after: T): ValueChange<T>? =
        if (before == after) null else ValueChange(before, after)
}
