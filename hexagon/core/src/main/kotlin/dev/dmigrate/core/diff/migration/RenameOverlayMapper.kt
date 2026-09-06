package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import java.util.Locale

/**
 * F.4 (second slice) helpers for the [OperationMapper]: extracts
 * rename-overlay parsing and the `(DropTable, CreateTable)` /
 * per-table `(DropColumn, AddColumn)` folding into rename
 * operations.
 *
 * Stays an `internal object` so the mapper can call into it without
 * exposing the helper types. The mapper still owns the overall
 * mapping pipeline; this object only carries the rename-specific
 * concerns so [OperationMapper] stays under Detekt's `LargeClass`
 * threshold.
 */
internal object RenameOverlayMapper {

    const val STRUCTURAL_MISMATCH: String = "RENAME_OVERLAY_STRUCTURAL_MISMATCH"
    const val DEPENDENCY_PROJECTION_REQUIRED: String = "RENAME_OVERLAY_DEPENDENCY_PROJECTION_REQUIRED"

    /**
     * F.4 dependency-projection T3 pipeline:
     * `prepareTableItems(...)` builds candidates without touching the
     * operations list. [RenameDependencyProjector.projectTables]
     * consults the dialect-specific
     * [RenameDependencyPolicy] (resolved from
     * [RenameProjectionCapabilities.dialect]) and folds the items into
     * `RenameTable` operations, structural-mismatch warnings, stale-
     * reference warnings, or `RENAME_DEPENDENCY_UNPROJECTABLE`
     * diagnostics — whichever the policy + mapper signals demand.
     *
     * Return contract (preserved from pre-T2 for the existing
     * [OperationMapper.mapTables] consumer): [TableFoldResult.absorbedToNames]
     * are skipped from `tablesAdded`, [TableFoldResult.absorbedFromNames]
     * are skipped from `tablesRemoved`, and [TableFoldResult.absorbedViews]
     * are skipped from `viewsChanged` so the mapper does not emit a
     * duplicate `ReplaceView` alongside the projector's `DropView`/
     * `CreateView` pair (T5 explicit view-reprojection).
     */
    fun foldRenameTables(
        diff: SchemaDiff,
        ctx: RenameMappingContext,
        blockedTables: Set<String>,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
        reports: MutableList<RenameProjectionReport>,
    ): TableFoldResult {
        val items = prepareTableItems(diff, ctx.current, ctx.desired, blockedTables, renameIndex)
        val projection = RenameDependencyProjector(ctx.capabilities)
            .projectTables(items, diff, ctx.current, ctx.desired)
        ops += projection.operations
        diagnostics += projection.diagnostics
        reports += projection.reports
        return TableFoldResult(
            absorbedToNames = projection.absorbedToNames,
            absorbedFromNames = projection.absorbedFromNames,
            absorbedViews = projection.absorbedViews,
        )
    }

    /**
     * Result of [foldRenameTables]. Carries the three sets the
     * [OperationMapper.mapTables] loop needs to skip in `tablesAdded`,
     * `tablesRemoved`, and `viewsChanged` respectively. T6 report
     * carriers are appended directly to the caller's mutable list.
     */
    data class TableFoldResult(
        val absorbedToNames: Set<String>,
        val absorbedFromNames: Set<String>,
        val absorbedViews: Set<String>,
    )

    /**
     * Per-table column-rename fold. Same return-contract shape as
     * [foldRenameTables]: the **first** set carries absorbed `to`-side
     * column names and the **second** set carries absorbed `from`-side
     * column names so the destructuring at the
     * [OperationMapper.mapTableColumns] call site stays correct.
     */
    /**
     * Was ein Spalten-Rename-Fold an die Aufrufstelle zurueckgibt.
     *
     * Benannt statt `Pair`, weil mit [absorbedViews] ein dritter Wert
     * dazukam — der Rueckgabewert eines Folds ist damit nicht mehr aus
     * der Signatur ablesbar, und die beiden Spaltenmengen sind
     * verwechselbar (beide `Set<String>`).
     */
    data class ColumnFoldResult(
        val absorbedToColumns: Set<String>,
        val absorbedFromColumns: Set<String>,
        val absorbedViews: Set<String>,
    )

    fun foldRenameColumns(
        table: TableDiff,
        renameIndex: RenameOverlayIndex,
        ctx: RenameMappingContext,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
        reports: MutableList<RenameProjectionReport>,
    ): ColumnFoldResult {
        val items = prepareColumnItems(table, renameIndex)
        val projection = RenameDependencyProjector(ctx.capabilities)
            .projectColumns(items, table, ctx.current, ctx.desired)
        ops += projection.operations
        diagnostics += projection.diagnostics
        reports += projection.reports
        return ColumnFoldResult(
            absorbedToColumns = projection.absorbedToColumns,
            absorbedFromColumns = projection.absorbedFromColumns,
            absorbedViews = projection.absorbedViews,
        )
    }

    /**
     * Builds [RenameTablePlanningItem]s for every overlay table mapping
     * whose `from` is in `tablesRemoved` AND `to` is in `tablesAdded`.
     * Mappings that touch a `CONSTRAINT_NOT_DIFFABLE`-blocked table are
     * skipped entirely (the planner already surfaces a top-level
     * blocker for those tables).
     *
     * T4 (mixed-case intra-object delta synthesis): when source and
     * target tables differ structurally but the drift is fully
     * intra-object (column add/drop/alter, index drift, constraint
     * drift, PK reshape), the candidate carries the rename PLUS
     * synthetic delta operations on
     * [RenamePlanningItem.postRenameDeltaOperations] — each anchored
     * to the rename via `dependencies = setOf(candidate.id)`. Drift
     * that the synthesiser cannot cover (table metadata, T5 cross-
     * object dependencies) is reported via [structuralDifferences];
     * the projector then falls back to drop+create.
     */
    fun prepareTableItems(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        blockedTables: Set<String>,
        renameIndex: RenameOverlayIndex,
    ): List<RenameTablePlanningItem> {
        if (renameIndex.isEmpty()) return emptyList()
        val addedByName = diff.tablesAdded.associateBy { it.name }
        val removedByName = diff.tablesRemoved.associateBy { it.name }
        val items = mutableListOf<RenameTablePlanningItem>()
        for (mapping in renameIndex.tableMappings()) {
            val from = mapping.fromName
            val to = mapping.toName
            if (from in blockedTables || to in blockedTables) continue
            val removed = removedByName[from] ?: continue
            val added = addedByName[to] ?: continue
            val before = current.tables[from] ?: removed.definition
            val after = desired.tables[to] ?: added.definition
            val candidateId = renameTableOperationId(mapping, from, to)
            // CanonicalPayload covers columns, PK, constraints AND
            // every index field. If both sides are byte-identical we
            // skip synthesis entirely.
            val byteIdentical = CanonicalPayload.table(before) == CanonicalPayload.table(after)
            val synthesis = if (byteIdentical) {
                RenameIntraObjectDeltaSynthesizer.SynthesisResult.EMPTY
            } else {
                RenameIntraObjectDeltaSynthesizer.synthesizeForTableRename(
                    candidateId = candidateId,
                    targetTableName = to,
                    before = before,
                    after = after,
                )
            }
            // Drift that the synthesiser cannot project (today: table
            // metadata) keeps the candidate structurally-unequal so the
            // projector falls back to drop+create.
            val renamable = synthesis.isComplete
            val structuralDifferences = if (renamable) {
                emptyList()
            } else {
                synthesis.residualDifferences.ifEmpty {
                    describeTableDifferences(before, after)
                        .ifEmpty { listOf("structural difference detected") }
                }
            }
            // Only probe the cross-table FK references when the
            // projector is about to emit a `RenameTable`. A
            // non-renamable candidate is already going to drop+create
            // and surface a structural-mismatch warning — adding a
            // stale-reference warning on top would shadow that
            // diagnostic without changing the outcome.
            val staleRef = if (renamable) staleReferenceToOldName(diff, from) else null
            // Pre-compute the ids of the regular drop+create ops the
            // mapper will emit when the projector falls back to
            // drop+create. The ids are deterministic (same canonical
            // payload, same id) so the projector can reference them
            // in `renameProjections` reports without waiting for the
            // mapper to actually emit them.
            val dropRef = DiffObjectRef(DiffObjectType.TABLE, listOf(from))
            val createRef = DiffObjectRef(DiffObjectType.TABLE, listOf(to))
            val fallbackIds = listOf(
                OperationIdFactory.makeId("DropTable", dropRef, CanonicalPayload.table(removed.definition)),
                OperationIdFactory.makeId("CreateTable", createRef, CanonicalPayload.table(added.definition)),
            )
            items += RenameTablePlanningItem(
                candidate = RenameTableCandidate(
                    id = candidateId,
                    fromName = from,
                    toName = to,
                    overlaySource = mapping.source,
                    overlayEntryId = mapping.entryId,
                    overlayHash = mapping.overlayHash,
                    renamable = renamable,
                    structuralDifferences = structuralDifferences,
                    staleReferenceObject = staleRef,
                    fallbackOperationIds = fallbackIds,
                ),
                postRenameDeltaOperations = if (renamable) synthesis.operations else emptyList(),
            )
        }
        return items
    }

    /**
     * Builds [RenameColumnPlanningItem]s for every overlay column
     * mapping whose `from` is in `columnsRemoved` AND `to` is in
     * `columnsAdded` for the given [table]. T4 adds intra-column
     * delta synthesis: when the renamed column has type / nullability
     * / default drift, the item carries `AlterColumn*` operations on
     * [RenamePlanningItem.postRenameDeltaOperations] anchored to the
     * rename via `dependencies = setOf(candidate.id)`. Drift in
     * `unique` / `references` / `generation` (absorbed into table-
     * level UNIQUE / FK constraints) and any same-table PK / index /
     * constraint touching the column stays blocked — those are
     * cross-object T5 territory and the projector falls back to
     * drop+add.
     */
    fun prepareColumnItems(
        table: TableDiff,
        renameIndex: RenameOverlayIndex,
    ): List<RenameColumnPlanningItem> {
        if (renameIndex.isEmpty()) return emptyList()
        val items = mutableListOf<RenameColumnPlanningItem>()
        for (mapping in renameIndex.columnMappings(table.name)) {
            val removed = table.columnsRemoved[mapping.fromColumn] ?: continue
            val added = table.columnsAdded[mapping.toColumn] ?: continue
            val candidateId = renameColumnOperationId(table, mapping)
            val byteIdentical = CanonicalPayload.column(removed) == CanonicalPayload.column(added)
            val synthesis = if (byteIdentical) {
                RenameIntraObjectDeltaSynthesizer.SynthesisResult.EMPTY
            } else {
                RenameIntraObjectDeltaSynthesizer.synthesizeForColumnRename(
                    candidateId = candidateId,
                    tableName = table.name,
                    toColumn = mapping.toColumn,
                    before = removed,
                    after = added,
                )
            }
            val renamable = synthesis.isComplete
            val structuralDifferences = if (renamable) {
                emptyList()
            } else {
                synthesis.residualDifferences.ifEmpty {
                    describeColumnDifferences(removed, added)
                        .ifEmpty { listOf("structural difference detected") }
                }
            }
            val referencingObject = if (renamable) {
                referencingObjectFor(table, mapping.fromColumn, mapping.toColumn)
            } else {
                null
            }
            val fromRef = DiffObjectRef(DiffObjectType.COLUMN, listOf(table.name, mapping.fromColumn))
            val toRef = DiffObjectRef(DiffObjectType.COLUMN, listOf(table.name, mapping.toColumn))
            val fallbackIds = listOf(
                OperationIdFactory.makeId("DropColumn", fromRef, CanonicalPayload.column(removed)),
                OperationIdFactory.makeId("AddColumn", toRef, CanonicalPayload.column(added)),
            )
            items += RenameColumnPlanningItem(
                candidate = RenameColumnCandidate(
                    id = candidateId,
                    tableName = table.name,
                    fromColumn = mapping.fromColumn,
                    toColumn = mapping.toColumn,
                    overlaySource = mapping.source,
                    overlayEntryId = mapping.entryId,
                    overlayHash = mapping.overlayHash,
                    renamable = renamable,
                    structuralDifferences = structuralDifferences,
                    referencingObject = referencingObject,
                    fallbackOperationIds = fallbackIds,
                ),
                postRenameDeltaOperations = if (renamable) synthesis.operations else emptyList(),
            )
        }
        return items
    }

    internal fun structuralMismatchTableDiagnostic(candidate: RenameTableCandidate): DiffDiagnostic = DiffDiagnostic(
        code = STRUCTURAL_MISMATCH,
        message = "Rename mapping ${candidate.overlaySource} entry=${candidate.overlayEntryId} for table " +
            "'${candidate.fromName}' -> '${candidate.toName}' was ignored: source and target tables differ structurally " +
            "(${candidate.structuralDifferences.joinToString("; ")}). The migration falls back to drop+create. " +
            "Align the schemas (or remove the mapping) to enable a native " +
            "ALTER TABLE ... RENAME TO.",
        severity = DiffDiagnostic.Severity.WARNING,
    )

    internal fun structuralMismatchColumnDiagnostic(candidate: RenameColumnCandidate): DiffDiagnostic = DiffDiagnostic(
        code = STRUCTURAL_MISMATCH,
        message = "Rename mapping ${candidate.overlaySource} entry=${candidate.overlayEntryId} for column " +
            "'${candidate.tableName}.${candidate.fromColumn}' -> '${candidate.tableName}." +
            "${candidate.toColumn}' was ignored: source and target columns differ structurally " +
            "(${candidate.structuralDifferences.joinToString("; ")}). The migration falls back to drop+add. " +
            "Align the column definitions (or remove the mapping) to enable a native " +
            "ALTER TABLE ... RENAME COLUMN.",
        severity = DiffDiagnostic.Severity.WARNING,
    )

    /**
     * Builds a short, operator-readable list of structural differences
     * between two [TableDefinition]s for the
     * [STRUCTURAL_MISMATCH] diagnostic message. Returns an empty list
     * when the two definitions are equivalent.
     *
     * Differences are summarised at the granularity an operator can
     * act on (column-name sets, attribute drift, PK shape, constraint
     * names, index names). Per-attribute deltas for changed columns
     * are included via [describeColumnDifferences].
     */
    private fun describeTableDifferences(
        before: TableDefinition,
        after: TableDefinition,
    ): List<String> = buildList {
        addAll(describeColumnSetDifferences(before, after))
        if (before.primaryKey != after.primaryKey) {
            add("primary key ${before.primaryKey} -> ${after.primaryKey}")
        }
        addAll(describeConstraintDifferences(before, after))
        addAll(describeIndexDifferences(before, after))
    }

    private fun describeColumnSetDifferences(
        before: TableDefinition,
        after: TableDefinition,
    ): List<String> = buildList {
        val beforeCols = before.columns.keys.toSet()
        val afterCols = after.columns.keys.toSet()
        (beforeCols - afterCols).takeIf { it.isNotEmpty() }?.let {
            add("removed columns ${it.sorted()}")
        }
        (afterCols - beforeCols).takeIf { it.isNotEmpty() }?.let {
            add("added columns ${it.sorted()}")
        }
        for (name in (beforeCols intersect afterCols).sorted()) {
            val colDiffs = describeColumnDifferences(before.columns.getValue(name), after.columns.getValue(name))
            if (colDiffs.isNotEmpty()) add("column '$name': ${colDiffs.joinToString(", ")}")
        }
    }

    private fun describeConstraintDifferences(
        before: TableDefinition,
        after: TableDefinition,
    ): List<String> = buildList {
        val beforeConstraints = before.constraints.associateBy { it.name }
        val afterConstraints = after.constraints.associateBy { it.name }
        (beforeConstraints.keys - afterConstraints.keys).takeIf { it.isNotEmpty() }?.let {
            add("removed constraints ${it.sorted()}")
        }
        (afterConstraints.keys - beforeConstraints.keys).takeIf { it.isNotEmpty() }?.let {
            add("added constraints ${it.sorted()}")
        }
        for (name in (beforeConstraints.keys intersect afterConstraints.keys).sorted()) {
            if (CanonicalPayload.constraint(beforeConstraints.getValue(name)) !=
                CanonicalPayload.constraint(afterConstraints.getValue(name))
            ) {
                add("constraint '$name' definition changed")
            }
        }
    }

    /**
     * Index drift contributes to the [STRUCTURAL_MISMATCH] message in
     * three flavours: named index removed, named index added, named
     * index with the same name but a different definition. Anonymous
     * indices are keyed by their [CanonicalPayload.index] string so
     * `(email)` vs `(username)` shows up as one removed + one added
     * rather than vanishing from the comparison.
     */
    private fun describeIndexDifferences(
        before: TableDefinition,
        after: TableDefinition,
    ): List<String> {
        val beforeByKey = before.indices.associateBy { indexKey(it) }
        val afterByKey = after.indices.associateBy { indexKey(it) }
        val out = mutableListOf<String>()
        (beforeByKey.keys - afterByKey.keys).takeIf { it.isNotEmpty() }?.let { out += "removed indices ${it.sorted()}" }
        (afterByKey.keys - beforeByKey.keys).takeIf { it.isNotEmpty() }?.let { out += "added indices ${it.sorted()}" }
        for (key in (beforeByKey.keys intersect afterByKey.keys).sorted()) {
            if (CanonicalPayload.index(beforeByKey.getValue(key)) !=
                CanonicalPayload.index(afterByKey.getValue(key))
            ) {
                out += "index '$key' definition changed"
            }
        }
        return out
    }

    private fun indexKey(idx: dev.dmigrate.core.model.IndexDefinition): String =
        idx.name ?: "anon(${CanonicalPayload.index(idx)})"

    /**
     * Per-attribute deltas between two [ColumnDefinition]s. Each
     * entry is a `"<attribute> <before> -> <after>"` string so the
     * mismatch diagnostic can point to the exact attribute the
     * operator needs to align.
     */
    private fun describeColumnDifferences(
        before: ColumnDefinition,
        after: ColumnDefinition,
    ): List<String> {
        val out = mutableListOf<String>()
        if (before.type != after.type) out += "type ${before.type} -> ${after.type}"
        if (before.required != after.required) out += "required ${before.required} -> ${after.required}"
        if (before.unique != after.unique) out += "unique ${before.unique} -> ${after.unique}"
        if (before.default != after.default) out += "default ${before.default} -> ${after.default}"
        if (before.references != after.references) {
            out += "references ${before.references} -> ${after.references}"
        }
        if (before.generation != after.generation) {
            out += "generation ${before.generation} -> ${after.generation}"
        }
        return out
    }

    internal fun staleReferenceTableDiagnostic(candidate: RenameTableCandidate): DiffDiagnostic {
        val staleRef = candidate.staleReferenceObject
            ?: error("staleReferenceTableDiagnostic invoked for a candidate without a stale reference")
        return DiffDiagnostic(
            code = DEPENDENCY_PROJECTION_REQUIRED,
            message = "Rename mapping ${candidate.overlaySource} entry=${candidate.overlayEntryId} for table " +
                "'${candidate.fromName}' -> '${candidate.toName}' was ignored: another table's diff " +
                "($staleRef) still references the old name '${candidate.fromName}' after the rename. " +
                "Forward references to the new name '${candidate.toName}' are sorted by the dependency " +
                "analyzer (CreateTable / RenameTable produce an FK-target edge); stale references to the " +
                "old name require manual re-projection and remain out of scope for this slice.",
            severity = DiffDiagnostic.Severity.WARNING,
        )
    }

    internal fun dependencyProjectionColumnDiagnostic(candidate: RenameColumnCandidate): DiffDiagnostic {
        val referencingObject = candidate.referencingObject
            ?: error("dependencyProjectionColumnDiagnostic invoked for a candidate without a referencing object")
        return DiffDiagnostic(
            code = DEPENDENCY_PROJECTION_REQUIRED,
            message = "Rename mapping ${candidate.overlaySource} entry=${candidate.overlayEntryId} for column " +
                "'${candidate.tableName}.${candidate.fromColumn}' -> '${candidate.tableName}." +
                "${candidate.toColumn}' was ignored: $referencingObject changes in the same table diff and " +
                "would need to be re-projected to the new column name. Dependency projection is out of " +
                "scope for this slice; the migration falls back to drop+add.",
            severity = DiffDiagnostic.Severity.WARNING,
        )
    }

    internal fun buildRenameTableOperation(candidate: RenameTableCandidate): DiffOperation.RenameTable {
        val ref = DiffObjectRef(DiffObjectType.TABLE, listOf(candidate.toName))
        return DiffOperation.RenameTable(
            id = candidate.id,
            objectRef = ref,
            fromName = candidate.fromName,
            toName = candidate.toName,
            overlaySource = candidate.overlaySource,
            overlayEntryId = candidate.overlayEntryId,
            overlayHash = candidate.overlayHash,
        )
    }

    internal fun buildRenameColumnOperation(candidate: RenameColumnCandidate): DiffOperation.RenameColumn {
        val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(candidate.tableName, candidate.toColumn))
        return DiffOperation.RenameColumn(
            id = candidate.id,
            objectRef = ref,
            fromName = candidate.fromColumn,
            toName = candidate.toColumn,
            overlaySource = candidate.overlaySource,
            overlayEntryId = candidate.overlayEntryId,
            overlayHash = candidate.overlayHash,
        )
    }

    private fun renameTableOperationId(mapping: TableRenameMapping, from: String, to: String): String {
        val ref = DiffObjectRef(DiffObjectType.TABLE, listOf(to))
        return OperationIdFactory.makeId(
            "RenameTable", ref,
            "from=$from->to=$to::overlay=${mapping.overlayHash ?: "<unhashed>"}",
        )
    }

    private fun renameColumnOperationId(table: TableDiff, mapping: ColumnRenameMapping): String {
        val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(table.name, mapping.toColumn))
        return OperationIdFactory.makeId(
            "RenameColumn", ref,
            "from=${mapping.fromColumn}->to=${mapping.toColumn}" +
                "::overlay=${mapping.overlayHash ?: "<unhashed>"}",
        )
    }

    /**
     * After a `users_old -> users` rename, any FK that still points at
     * the old name `users_old` is left dangling — the dependency
     * analyzer's `tableSourceIdByName` only carries the **new** name,
     * so there is no edge that orders such a reference correctly. This
     * usually indicates a schema-side mistake (the desired schema
     * still references the pre-rename name) and blocks the rename
     * with [DEPENDENCY_PROJECTION_REQUIRED]; forward references to
     * the new name are fine and handled by [DependencyAnalyzer].
     */
    private fun staleReferenceToOldName(diff: SchemaDiff, fromName: String): String? {
        for (changed in diff.tablesChanged) {
            for (c in changed.constraintsAdded) {
                if (c.type == ConstraintType.FOREIGN_KEY && c.references?.table == fromName) {
                    return "${changed.name}.${c.name}"
                }
            }
            for (vc in changed.constraintsChanged) {
                if (vc.after.references?.table == fromName) return "${changed.name}.${vc.after.name}"
            }
            for ((colName, def) in changed.columnsAdded) {
                if (def.references?.table == fromName) return "${changed.name}.$colName"
            }
        }
        for (added in diff.tablesAdded) {
            for (col in added.definition.columns) {
                if (col.value.references?.table == fromName) return "${added.name}.${col.key}"
            }
            for (c in added.definition.constraints) {
                if (c.type == ConstraintType.FOREIGN_KEY && c.references?.table == fromName) {
                    return "${added.name}.${c.name}"
                }
            }
        }
        return null
    }

    private fun referencingObjectFor(table: TableDiff, fromCol: String, toCol: String): String? {
        val needle = setOf(fromCol, toCol)
        primaryKeyTouchingColumn(table, needle)?.let { return it }
        indexTouchingColumn(table, needle)?.let { return it }
        constraintTouchingColumn(table, needle)?.let { return it }
        return null
    }

    private fun primaryKeyTouchingColumn(table: TableDiff, needle: Set<String>): String? {
        val pk = table.primaryKey ?: return null
        val touched = pk.before.any { it in needle } || pk.after.any { it in needle }
        return if (touched) "primary key" else null
    }

    private fun indexTouchingColumn(table: TableDiff, needle: Set<String>): String? {
        for (idx in table.indicesAdded + table.indicesRemoved) {
            if (idx.columns.any { it.name in needle }) return "index ${idx.name ?: "(anonymous)"}"
        }
        for (vc in table.indicesChanged) {
            val touched = vc.before.columns.any { it.name in needle } ||
                vc.after.columns.any { it.name in needle }
            if (touched) return "index ${vc.after.name ?: vc.before.name ?: "(anonymous)"}"
        }
        return null
    }

    private fun constraintTouchingColumn(table: TableDiff, needle: Set<String>): String? {
        for (c in table.constraintsAdded + table.constraintsRemoved) {
            if (constraintTouchesColumn(c, needle)) return "constraint ${c.name}"
        }
        for (vc in table.constraintsChanged) {
            if (constraintTouchesColumn(vc.before, needle) ||
                constraintTouchesColumn(vc.after, needle)
            ) {
                return "constraint ${vc.after.name}"
            }
        }
        return null
    }

    private fun constraintTouchesColumn(c: ConstraintDefinition, needle: Set<String>): Boolean =
        c.columns?.any { it in needle } == true ||
            c.references?.columns?.any { it in needle } == true
}

/**
 * F.4 second-slice index over rename-overlay entries. The mapper
 * consults this index while walking the [SchemaDiff]; the index does
 * NOT re-validate fingerprints, hashes, dialect or uniqueness — those
 * checks live in `MigrationOverlayValidator` and are invoked by
 * `MigrationOverlayPreflight` before render. If the preflight blocks
 * the migration, the mapper's collapsed `RenameTable`/`RenameColumn`
 * operations never reach a renderer.
 */
internal data class RenameOverlayIndex(
    private val tableMappings: List<TableRenameMapping>,
    private val qualifiedColumnMappings: Map<String, List<ColumnRenameMapping>>,
    private val unqualifiedColumnMappings: List<ColumnRenameMapping>,
    private val viewMappings: List<ViewRenameMapping>,
    private val triggerMappings: List<TriggerRenameMapping>,
    private val functionMappings: List<RoutineRenameMapping>,
    private val procedureMappings: List<RoutineRenameMapping>,
    private val sequenceMappings: List<SequenceRenameMapping>,
    /**
     * Diagnostics produced while indexing the overlay entries (e.g.
     * cross-table or partially-qualified column-rename mappings the
     * mapper cannot safely interpret). These are surfaced as
     * [DiffDiagnostic.Severity.WARNING] by [OperationMapper.map];
     * the affected entries are dropped from the index so they cannot
     * accidentally trigger a wrong rename.
     */
    val issues: List<DiffDiagnostic>,
) {
    fun isEmpty(): Boolean =
        tableMappings.isEmpty() && qualifiedColumnMappings.isEmpty() && unqualifiedColumnMappings.isEmpty() &&
            viewMappings.isEmpty() && triggerMappings.isEmpty() && functionMappings.isEmpty() &&
            procedureMappings.isEmpty() && sequenceMappings.isEmpty()

    fun tableMappings(): List<TableRenameMapping> = tableMappings

    fun columnMappings(tableName: String): List<ColumnRenameMapping> {
        val qualified = qualifiedColumnMappings[tableName.lowercase(Locale.ROOT)].orEmpty()
        if (unqualifiedColumnMappings.isEmpty()) return qualified
        val withDefaults = unqualifiedColumnMappings.map { it.copy(tableName = tableName) }
        return qualified + withDefaults
    }

    fun viewMappings(): List<ViewRenameMapping> = viewMappings
    fun triggerMappings(): List<TriggerRenameMapping> = triggerMappings
    fun functionMappings(): List<RoutineRenameMapping> = functionMappings
    fun procedureMappings(): List<RoutineRenameMapping> = procedureMappings
    fun sequenceMappings(): List<SequenceRenameMapping> = sequenceMappings

    companion object {
        private const val CROSS_TABLE_REJECTED: String = "RENAME_OVERLAY_CROSS_TABLE_REJECTED"
        private const val MIXED_QUALIFICATION: String = "RENAME_OVERLAY_MIXED_COLUMN_QUALIFICATION"
        private const val TRIGGER_KEY_INVALID: String = "RENAME_OVERLAY_TRIGGER_KEY_INVALID"
        private const val TRIGGER_CROSS_TABLE: String = "RENAME_OVERLAY_TRIGGER_CROSS_TABLE_REJECTED"
        private const val ROUTINE_KEY_INVALID: String = "RENAME_OVERLAY_ROUTINE_KEY_INVALID"
        private const val ROUTINE_SIGNATURE_MISMATCH: String = "RENAME_OVERLAY_ROUTINE_SIGNATURE_MISMATCH"

        /**
         * Der Zustand, der beim Einlesen der Overlays entsteht: je Objektart
         * eine Liste und ein „schon gesehen"-Satz.
         *
         * Sechzehn lokale Sammler machten `build` lang, ohne dass die Methode
         * viel taete — sie sind ein Gegenstand, nicht sechzehn.
         */
        private class RenameAccumulator {
            val tables = mutableListOf<TableRenameMapping>()
            val qualified = mutableMapOf<String, MutableList<ColumnRenameMapping>>()
            val unqualified = mutableListOf<ColumnRenameMapping>()
            val views = mutableListOf<ViewRenameMapping>()
            val triggers = mutableListOf<TriggerRenameMapping>()
            val functions = mutableListOf<RoutineRenameMapping>()
            val procedures = mutableListOf<RoutineRenameMapping>()
            val sequences = mutableListOf<SequenceRenameMapping>()
            val issues = mutableListOf<DiffDiagnostic>()

            // Defensive dedupe: auch wenn `MigrationOverlayValidator` exakte
            // Dubletten mit RENAME_MAPPING_DUPLICATE vor dem Mapper abfaengt,
            // darf kein Aufrufer (Tests, kuenftige Wege am Preflight vorbei)
            // ein Rename zweimal falten. Die Saetze sind case-gefaltet nach
            // (objectType, fromName, toName) verschluesselt.
            val seenTables = mutableSetOf<Pair<String, String>>()
            val seenColumns = mutableSetOf<Triple<String?, String, String>>()
            val seenViews = mutableSetOf<Pair<String, String>>()
            val seenTriggers = mutableSetOf<Pair<String, String>>()
            val seenFunctions = mutableSetOf<Pair<String, String>>()
            val seenProcedures = mutableSetOf<Pair<String, String>>()
            val seenSequences = mutableSetOf<Pair<String, String>>()

            fun toIndex() = RenameOverlayIndex(
                tableMappings = tables,
                qualifiedColumnMappings = qualified,
                unqualifiedColumnMappings = unqualified,
                viewMappings = views,
                triggerMappings = triggers,
                functionMappings = functions,
                procedureMappings = procedures,
                sequenceMappings = sequences,
                issues = issues,
            )
        }

        fun build(documents: List<MigrationOverlayDocument>): RenameOverlayIndex {
            if (documents.isEmpty()) return EMPTY
            val acc = RenameAccumulator()
            for (doc in documents) {
                if (doc.overlay.overlayKind != MigrationOverlayKinds.RENAME_MAPPING) continue
                for (entry in doc.overlay.entries.filterIsInstance<RenameMappingOverlayEntry>()) {
                    absorb(doc, entry, acc)
                }
            }
            return acc.toIndex()
        }

        /** Eine Overlay-Zeile in den passenden Sammler. */
        private fun absorb(
            doc: MigrationOverlayDocument,
            entry: RenameMappingOverlayEntry,
            acc: RenameAccumulator,
        ) {
            when (entry.objectType.lowercase(Locale.ROOT)) {
                "table" -> addTableMapping(doc, entry, acc.tables, acc.seenTables)
                "column" -> indexColumnEntry(
                    doc, entry, acc.qualified, acc.unqualified, acc.issues, acc.seenColumns,
                )
                "view" -> addViewMapping(doc, entry, acc.views, acc.seenViews)
                "trigger" -> addTriggerMapping(doc, entry, acc.triggers, acc.issues, acc.seenTriggers)
                "function" -> addRoutineMapping(
                    doc, entry, acc.functions, acc.issues, acc.seenFunctions, kindLabel = "function",
                )
                "procedure" -> addRoutineMapping(
                    doc, entry, acc.procedures, acc.issues, acc.seenProcedures, kindLabel = "procedure",
                )
                "sequence" -> addSequenceMapping(doc, entry, acc.sequences, acc.seenSequences)
                // Unbekannter objectType: hier still uebergangen — der
                // UNKNOWN_ENTRY_KIND-Blocker des Validators deckt ihn ab.
            }
        }

        private fun addTableMapping(
            doc: MigrationOverlayDocument,
            entry: RenameMappingOverlayEntry,
            tables: MutableList<TableRenameMapping>,
            seen: MutableSet<Pair<String, String>>,
        ) {
            val key = entry.fromName.lowercase(Locale.ROOT) to entry.toName.lowercase(Locale.ROOT)
            if (!seen.add(key)) return
            tables += TableRenameMapping(
                fromName = entry.fromName,
                toName = entry.toName,
                source = doc.source,
                entryId = entry.id,
                overlayHash = doc.overlay.overlayHash,
            )
        }

        private fun indexColumnEntry(
            doc: MigrationOverlayDocument,
            entry: RenameMappingOverlayEntry,
            qualified: MutableMap<String, MutableList<ColumnRenameMapping>>,
            unqualified: MutableList<ColumnRenameMapping>,
            issues: MutableList<DiffDiagnostic>,
            seen: MutableSet<Triple<String?, String, String>>,
        ) {
            val (fromQualifier, fromCol) = splitQualifiedName(entry.fromName)
            val (toQualifier, toCol) = splitQualifiedName(entry.toName)
            // Mixed qualification — operator must choose either both qualified
            // or both unqualified.
            if ((fromQualifier == null) != (toQualifier == null)) {
                issues += DiffDiagnostic(
                    code = MIXED_QUALIFICATION,
                    message = "Rename mapping ${doc.source} entry=${entry.id} mixes qualified and " +
                        "unqualified column names ('${entry.fromName}' -> '${entry.toName}'). " +
                        "Provide both names as <table>.<column> or both as plain <column>.",
                    severity = DiffDiagnostic.Severity.WARNING,
                )
                return
            }
            // Cross-table column renames are not supported.
            if (fromQualifier != null && toQualifier != null &&
                !fromQualifier.equals(toQualifier, ignoreCase = true)
            ) {
                issues += DiffDiagnostic(
                    code = CROSS_TABLE_REJECTED,
                    message = "Rename mapping ${doc.source} entry=${entry.id} targets two different " +
                        "tables ('${entry.fromName}' -> '${entry.toName}'). Cross-table column " +
                        "renames are not supported; move the column manually or split into a " +
                        "drop-from-source / add-to-target pair.",
                    severity = DiffDiagnostic.Severity.WARNING,
                )
                return
            }
            val dedupeKey = Triple(
                fromQualifier?.lowercase(Locale.ROOT),
                fromCol.lowercase(Locale.ROOT),
                toCol.lowercase(Locale.ROOT),
            )
            if (!seen.add(dedupeKey)) return
            val mapping = ColumnRenameMapping(
                tableName = fromQualifier ?: "",
                fromColumn = fromCol,
                toColumn = toCol,
                source = doc.source,
                entryId = entry.id,
                overlayHash = doc.overlay.overlayHash,
            )
            if (fromQualifier != null) {
                qualified.getOrPut(fromQualifier.lowercase(Locale.ROOT)) { mutableListOf() } += mapping
            } else {
                unqualified += mapping
            }
        }

        private fun splitQualifiedName(name: String): Pair<String?, String> {
            val dotIdx = name.indexOf('.')
            return if (dotIdx in 1 until name.length - 1) {
                name.substring(0, dotIdx) to name.substring(dotIdx + 1)
            } else {
                null to name
            }
        }

        val EMPTY: RenameOverlayIndex = RenameOverlayIndex(
            tableMappings = emptyList(),
            qualifiedColumnMappings = emptyMap(),
            unqualifiedColumnMappings = emptyList(),
            viewMappings = emptyList(),
            triggerMappings = emptyList(),
            functionMappings = emptyList(),
            procedureMappings = emptyList(),
            sequenceMappings = emptyList(),
            issues = emptyList(),
        )

        private fun addViewMapping(
            doc: MigrationOverlayDocument,
            entry: RenameMappingOverlayEntry,
            views: MutableList<ViewRenameMapping>,
            seen: MutableSet<Pair<String, String>>,
        ) {
            val key = entry.fromName.lowercase(Locale.ROOT) to entry.toName.lowercase(Locale.ROOT)
            if (!seen.add(key)) return
            views += ViewRenameMapping(
                fromName = entry.fromName,
                toName = entry.toName,
                source = doc.source,
                entryId = entry.id,
                overlayHash = doc.overlay.overlayHash,
            )
        }

        private fun addSequenceMapping(
            doc: MigrationOverlayDocument,
            entry: RenameMappingOverlayEntry,
            sequences: MutableList<SequenceRenameMapping>,
            seen: MutableSet<Pair<String, String>>,
        ) {
            val key = entry.fromName.lowercase(Locale.ROOT) to entry.toName.lowercase(Locale.ROOT)
            if (!seen.add(key)) return
            sequences += SequenceRenameMapping(
                fromName = entry.fromName,
                toName = entry.toName,
                source = doc.source,
                entryId = entry.id,
                overlayHash = doc.overlay.overlayHash,
            )
        }

        private fun addTriggerMapping(
            doc: MigrationOverlayDocument,
            entry: RenameMappingOverlayEntry,
            triggers: MutableList<TriggerRenameMapping>,
            issues: MutableList<DiffDiagnostic>,
            seen: MutableSet<Pair<String, String>>,
        ) {
            val fromParsed = parseTriggerKey(entry.fromName)
            val toParsed = parseTriggerKey(entry.toName)
            if (fromParsed == null || toParsed == null) {
                issues += DiffDiagnostic(
                    code = TRIGGER_KEY_INVALID,
                    message = "Rename mapping ${doc.source} entry=${entry.id} for objectType=trigger requires " +
                        "canonical keys 'table::name' on both sides ('${entry.fromName}' -> '${entry.toName}').",
                    severity = DiffDiagnostic.Severity.BLOCKER,
                )
                return
            }
            val (fromTable, fromName) = fromParsed
            val (toTable, toName) = toParsed
            if (!fromTable.equals(toTable, ignoreCase = true)) {
                issues += DiffDiagnostic(
                    code = TRIGGER_CROSS_TABLE,
                    message = "Rename mapping ${doc.source} entry=${entry.id} for trigger '${entry.fromName}' -> " +
                        "'${entry.toName}' moves between tables ('$fromTable' -> '$toTable'). Cross-table trigger " +
                        "moves are not a rename; remove the mapping or rewrite the schema.",
                    severity = DiffDiagnostic.Severity.BLOCKER,
                )
                return
            }
            val key = entry.fromName.lowercase(Locale.ROOT) to entry.toName.lowercase(Locale.ROOT)
            if (!seen.add(key)) return
            triggers += TriggerRenameMapping(
                table = fromTable,
                fromName = fromName,
                toName = toName,
                source = doc.source,
                entryId = entry.id,
                overlayHash = doc.overlay.overlayHash,
            )
        }

        private fun addRoutineMapping(
            doc: MigrationOverlayDocument,
            entry: RenameMappingOverlayEntry,
            routines: MutableList<RoutineRenameMapping>,
            issues: MutableList<DiffDiagnostic>,
            seen: MutableSet<Pair<String, String>>,
            kindLabel: String,
        ) {
            val fromParsed = parseRoutineKey(entry.fromName)
            val toParsed = parseRoutineKey(entry.toName)
            if (fromParsed == null || toParsed == null) {
                issues += DiffDiagnostic(
                    code = ROUTINE_KEY_INVALID,
                    message = "Rename mapping ${doc.source} entry=${entry.id} for objectType=$kindLabel requires " +
                        "canonical keys 'name(direction:type,...)' on both sides ('${entry.fromName}' -> " +
                        "'${entry.toName}').",
                    severity = DiffDiagnostic.Severity.BLOCKER,
                )
                return
            }
            val (fromName, fromParams) = fromParsed
            val (toName, toParams) = toParsed
            if (fromParams != toParams) {
                issues += DiffDiagnostic(
                    code = ROUTINE_SIGNATURE_MISMATCH,
                    message = "Rename mapping ${doc.source} entry=${entry.id} for $kindLabel '${entry.fromName}' -> " +
                        "'${entry.toName}' carries differing signatures ($fromParams vs $toParams). A signature " +
                        "change is a different routine, not a rename.",
                    severity = DiffDiagnostic.Severity.BLOCKER,
                )
                return
            }
            val key = entry.fromName.lowercase(Locale.ROOT) to entry.toName.lowercase(Locale.ROOT)
            if (!seen.add(key)) return
            routines += RoutineRenameMapping(
                fromName = fromName,
                toName = toName,
                parameters = fromParams,
                source = doc.source,
                entryId = entry.id,
                overlayHash = doc.overlay.overlayHash,
            )
        }

        private fun parseTriggerKey(raw: String): Pair<String, String>? = try {
            if (!raw.contains("::")) null else ObjectKeyCodec.parseTriggerKey(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun parseRoutineKey(raw: String): Pair<String, List<Pair<String, String>>>? = try {
            if (!raw.contains('(') || !raw.endsWith(')')) null else ObjectKeyCodec.parseRoutineKey(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal data class TableRenameMapping(
    val fromName: String,
    val toName: String,
    val source: String,
    val entryId: String,
    val overlayHash: String?,
)

internal data class ColumnRenameMapping(
    val tableName: String,
    val fromColumn: String,
    val toColumn: String,
    val source: String,
    val entryId: String,
    val overlayHash: String?,
)

/** F.4 Sub-Slice A.2 view-rename mapping (plain visible names, no canonical key). */
internal data class ViewRenameMapping(
    val fromName: String,
    val toName: String,
    val source: String,
    val entryId: String,
    val overlayHash: String?,
)

/** F.4 Sub-Slice A.2 trigger-rename mapping. Cross-table moves are pre-filtered at index time. */
internal data class TriggerRenameMapping(
    val table: String,
    val fromName: String,
    val toName: String,
    val source: String,
    val entryId: String,
    val overlayHash: String?,
)

/**
 * F.4 Sub-Slice A.2 routine-rename mapping (function or procedure). The
 * canonical key embeds the signature on both sides; [parameters]
 * carries the shared signature in `direction:type` form so the
 * Mapper can match against the schema-side `ParameterDefinition` list.
 */
internal data class RoutineRenameMapping(
    val fromName: String,
    val toName: String,
    val parameters: List<Pair<String, String>>,
    val source: String,
    val entryId: String,
    val overlayHash: String?,
)

internal data class SequenceRenameMapping(
    val fromName: String,
    val toName: String,
    val source: String,
    val entryId: String,
    val overlayHash: String?,
)
