package dev.dmigrate.core.diff

import dev.dmigrate.core.model.*

/**
 * Compares two [TableDefinition]s and produces a [TableDiff].
 *
 * Handles column comparison with single-column UNIQUE/FK normalization,
 * constraint diffing (single-column + multi-column), index diffing,
 * and primary key / metadata changes.
 *
 * Extracted from [SchemaComparator] to isolate the most complex
 * comparison logic (~320 LOC).
 */
internal class TableComparator {

    fun compareTables(left: SchemaDefinition, right: SchemaDefinition): TableDiffs {
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

    internal fun compareTable(
        name: String,
        left: TableDefinition,
        right: TableDefinition,
    ): TableDiff? {
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

    // ── Columns ───────────────────────────────────

    private data class AbsorbedColumns(
        val uniqueLeft: Set<String>, val uniqueRight: Set<String>,
        val fkLeft: Set<String>, val fkRight: Set<String>,
    )

    private data class ColumnDiffs(
        val added: Map<String, ColumnDefinition>,
        val removed: Map<String, ColumnDefinition>,
        val changed: List<ColumnDiff>,
    )

    private fun compareColumns(
        left: TableDefinition, right: TableDefinition, absorbed: AbsorbedColumns,
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
        name: String, left: ColumnDefinition, right: ColumnDefinition, absorbed: AbsorbedColumns,
    ): ColumnDiff? {
        val typeDiff = diffValueChangeOrNull(left.type, right.type)
        val requiredDiff = diffValueChangeOrNull(left.required, right.required)
        val defaultDiff = if (left.default == right.default) null
            else ValueChange(left.default, right.default)
        val uniqueAbsorbed = name in absorbed.uniqueLeft || name in absorbed.uniqueRight
        val uniqueDiff = if (uniqueAbsorbed) null else diffValueChangeOrNull(left.unique, right.unique)
        val fkAbsorbed = name in absorbed.fkLeft || name in absorbed.fkRight
        val refDiff = if (fkAbsorbed) null
            else if (left.references == right.references) null
            else ValueChange(left.references, right.references)
        if (hasNoColumnDiff(typeDiff, requiredDiff, defaultDiff, uniqueDiff, refDiff)) return null
        return ColumnDiff(name, typeDiff, requiredDiff, defaultDiff, uniqueDiff, refDiff)
    }

    private fun hasNoColumnDiff(vararg diffs: Any?): Boolean =
        diffs.all { it == null }

    private fun projectColumn(col: ColumnDefinition): ColumnDefinition =
        col.copy(unique = false, references = null)

    // ── Constraint Normalization ──────────────────

    private data class NormalizedConstraints(
        val singleColumnUnique: Set<String>,
        val singleColumnForeignKeys: Map<String, ForeignKeySignature>,
        val multiColumnConstraints: Map<String, ConstraintDefinition>,
    )

    private data class ForeignKeySignature(
        val column: String, val refTable: String, val refColumn: String,
        val onDelete: ReferentialAction?, val onUpdate: ReferentialAction?,
    )

    private fun normalizeConstraints(table: TableDefinition): NormalizedConstraints {
        val singleUnique = mutableSetOf<String>()
        val singleFk = mutableMapOf<String, ForeignKeySignature>()
        val multi = mutableMapOf<String, ConstraintDefinition>()

        for ((colName, col) in table.columns) {
            if (col.unique) singleUnique.add(colName)
            col.references?.let { ref ->
                singleFk[colName] = ForeignKeySignature(colName, ref.table, ref.column, ref.onDelete, ref.onUpdate)
            }
        }

        for (constraint in table.constraints) {
            when {
                constraint.type == ConstraintType.UNIQUE && constraint.columns?.size == 1 ->
                    singleUnique.add(constraint.columns.first())

                constraint.type == ConstraintType.FOREIGN_KEY && constraint.columns?.size == 1 &&
                    constraint.references != null && constraint.references.columns.size == 1 -> {
                    val colName = constraint.columns.first()
                    val sig = ForeignKeySignature(
                        colName, constraint.references.table, constraint.references.columns.first(),
                        constraint.references.onDelete, constraint.references.onUpdate,
                    )
                    val existing = singleFk[colName]
                    if (existing != null && existing != sig) {
                        multi[constraint.name] = constraint
                    } else {
                        singleFk[colName] = sig
                    }
                }

                constraint.type == ConstraintType.CHECK || constraint.type == ConstraintType.EXCLUDE -> {}
                else -> multi[constraint.name] = constraint
            }
        }

        return NormalizedConstraints(singleUnique, singleFk, multi)
    }

    // ── Constraints ──────────────────────────────

    private data class ConstraintDiffResult(
        val added: List<ConstraintDefinition>, val removed: List<ConstraintDefinition>,
        val changed: List<ValueChange<ConstraintDefinition>>,
    )

    private fun compareConstraints(left: NormalizedConstraints, right: NormalizedConstraints): ConstraintDiffResult {
        val added = mutableListOf<ConstraintDefinition>()
        val removed = mutableListOf<ConstraintDefinition>()
        val changed = mutableListOf<ValueChange<ConstraintDefinition>>()

        for (col in (right.singleColumnUnique - left.singleColumnUnique).sorted())
            added.add(syntheticUniqueConstraint(col))
        for (col in (left.singleColumnUnique - right.singleColumnUnique).sorted())
            removed.add(syntheticUniqueConstraint(col))

        val fkLeftCols = left.singleColumnForeignKeys.keys
        val fkRightCols = right.singleColumnForeignKeys.keys
        for (col in (fkRightCols - fkLeftCols).sorted())
            added.add(syntheticFkConstraint(right.singleColumnForeignKeys.getValue(col)))
        for (col in (fkLeftCols - fkRightCols).sorted())
            removed.add(syntheticFkConstraint(left.singleColumnForeignKeys.getValue(col)))
        for (col in (fkLeftCols intersect fkRightCols).sorted()) {
            val l = left.singleColumnForeignKeys.getValue(col)
            val r = right.singleColumnForeignKeys.getValue(col)
            if (l != r) changed.add(ValueChange(syntheticFkConstraint(l), syntheticFkConstraint(r)))
        }

        val multiLeftNames = left.multiColumnConstraints.keys
        val multiRightNames = right.multiColumnConstraints.keys
        for (name in (multiRightNames - multiLeftNames).sorted())
            added.add(right.multiColumnConstraints.getValue(name))
        for (name in (multiLeftNames - multiRightNames).sorted())
            removed.add(left.multiColumnConstraints.getValue(name))
        for (name in (multiLeftNames intersect multiRightNames).sorted()) {
            val l = left.multiColumnConstraints.getValue(name)
            val r = right.multiColumnConstraints.getValue(name)
            if (l != r) changed.add(ValueChange(l, r))
        }

        return ConstraintDiffResult(added, removed, changed)
    }

    private fun syntheticUniqueConstraint(column: String) = ConstraintDefinition(
        name = "_unique_$column", type = ConstraintType.UNIQUE, columns = listOf(column),
    )

    private fun syntheticFkConstraint(sig: ForeignKeySignature) = ConstraintDefinition(
        name = "_fk_${sig.column}", type = ConstraintType.FOREIGN_KEY, columns = listOf(sig.column),
        references = ConstraintReferenceDefinition(sig.refTable, listOf(sig.refColumn), sig.onDelete, sig.onUpdate),
    )

    // ── Indices ──────────────────────────────────

    private data class IndexDiffResult(
        val added: List<IndexDefinition>, val removed: List<IndexDefinition>,
        val changed: List<ValueChange<IndexDefinition>>,
    )

    private fun compareIndices(left: List<IndexDefinition>, right: List<IndexDefinition>): IndexDiffResult {
        val leftByKey = left.associateBy { indexKey(it) }
        val rightByKey = right.associateBy { indexKey(it) }
        val leftKeys = leftByKey.keys
        val rightKeys = rightByKey.keys
        val added = (rightKeys - leftKeys).sorted().map { rightByKey.getValue(it) }
        val removed = (leftKeys - rightKeys).sorted().map { leftByKey.getValue(it) }
        val changed = (leftKeys intersect rightKeys).sorted().mapNotNull { key ->
            val l = leftByKey.getValue(key); val r = rightByKey.getValue(key)
            if (l == r) null else ValueChange(l, r)
        }
        return IndexDiffResult(added, removed, changed)
    }

    private fun indexKey(index: IndexDefinition): String =
        index.name ?: "idx:${index.columns.joinToString(",")}:${index.type}:${index.unique}"
}

internal data class TableDiffs(
    val added: List<NamedTable>,
    val removed: List<NamedTable>,
    val changed: List<TableDiff>,
)
