package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Maps a [SchemaDiff] to a flat list of [DiffOperation]s with stable
 * IDs but no dependency edges yet. The [DiffPlanner] orchestrates,
 * [DependencyAnalyzer] then attaches edges, [TopologicalSorter]
 * orders the result.
 *
 * `CHECK` and `EXCLUDE` constraints are explicitly skipped (Phase A
 * decision: tables carrying them are surfaced via
 * `CONSTRAINT_NOT_DIFFABLE` blockers; the comparator does not lossless-
 * diff their semantics).
 *
 * Operation-ID payload determinism: at-risk types (those carrying
 * `Map` fields whose iteration order can vary across loaders / JVM
 * versions — `TableDefinition`, `ColumnDefinition`, `CustomTypeDefinition`,
 * `ViewDefinition`) are canonicalised via [CanonicalPayload]. The
 * remaining types (`SequenceDefinition`, `FunctionDefinition`,
 * `ProcedureDefinition`, `TriggerDefinition` plus their `Diff`
 * variants) have only primitive / `List` / sealed-subclass fields,
 * so their `toString()` is already deterministic under the Kotlin
 * data-class contract.
 */
internal object OperationMapper {

    fun map(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        blockedTables: Set<String>,
        migrationOverlays: List<MigrationOverlayDocument> = emptyList(),
    ): MapperResult = finalizeIds(prepare(diff, current, desired, blockedTables, migrationOverlays))

    /**
     * F.4 dependency-projection T2: phase 1 of the two-phase mapping
     * pipeline. Walks the [SchemaDiff] and produces the raw operations
     * + diagnostics. The result is fed to [finalizeIds] which applies
     * ID disambiguation and (in later slices) remaps dependency
     * references when a candidate's final ID changes.
     */
    internal fun prepare(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        blockedTables: Set<String>,
        migrationOverlays: List<MigrationOverlayDocument> = emptyList(),
    ): PreparedMapping {
        val renameIndex = RenameOverlayIndex.build(migrationOverlays)
        val diagnostics = mutableListOf<DiffDiagnostic>()
        diagnostics += renameIndex.issues
        val ops = mutableListOf<DiffOperation>()
        mapCustomTypes(diff, current, desired, ops)
        mapTables(diff, current, desired, blockedTables, renameIndex, diagnostics, ops)
        mapViews(diff, current, desired, ops)
        mapSequences(diff, current, desired, ops)
        mapFunctions(diff, current, desired, ops)
        mapProcedures(diff, current, desired, ops)
        mapTriggers(diff, current, desired, ops)
        return PreparedMapping(operations = ops, diagnostics = diagnostics)
    }

    /**
     * F.4 dependency-projection T2: phase 2 of the two-phase mapping
     * pipeline. Applies [OperationIdFactory.disambiguate] so any pair
     * of operations that produced the same base ID gets a stable `#N`
     * suffix in declaration order. Today's mappers do not collide for
     * non-degenerate inputs, but the planner contract is that IDs are
     * unique.
     *
     * Later slices (T4 / T5) will also remap operations that carry a
     * `dependencies = setOf(candidate.id)` reference when the candidate
     * itself was renamed by the suffix step.
     */
    internal fun finalizeIds(prepared: PreparedMapping): MapperResult {
        if (prepared.operations.isEmpty()) {
            return MapperResult(operations = emptyList(), diagnostics = prepared.diagnostics)
        }
        return MapperResult(
            operations = disambiguateOps(prepared.operations),
            diagnostics = prepared.diagnostics,
        )
    }

    /**
     * Intermediate state handed between [prepare] and [finalizeIds].
     * Carries the raw operations list + the collected diagnostics so
     * later slices can attach a candidate-ID map without changing the
     * public [MapperResult] shape.
     */
    internal data class PreparedMapping(
        val operations: List<DiffOperation>,
        val diagnostics: List<DiffDiagnostic>,
    )

    /**
     * Result wrapper so the planner can collect [diagnostics] generated
     * by the mapper (e.g. F.4 `RENAME_OVERLAY_STRUCTURAL_MISMATCH`)
     * alongside the operations.
     */
    internal data class MapperResult(
        val operations: List<DiffOperation>,
        val diagnostics: List<DiffDiagnostic>,
    )

    private fun disambiguateOps(ops: List<DiffOperation>): List<DiffOperation> {
        if (ops.isEmpty()) return ops
        val pairs = ops.mapIndexed { idx, op -> op.id to idx }
        val resolved = OperationIdFactory.disambiguate(pairs)
        return ops.mapIndexed { idx, op ->
            val newId = resolved[idx]
            if (newId == op.id) op else op.withId(newId)
        }
    }

    private fun mapCustomTypes(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.customTypesAdded) {
            val ref = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf(added.name))
            ops += DiffOperation.CreateCustomType(
                id = OperationIdFactory.makeId("CreateCustomType", ref, CanonicalPayload.customType(added.definition)),
                objectRef = ref,
                customType = added.definition,
            )
        }
        for (removed in diff.customTypesRemoved) {
            val ref = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf(removed.name))
            ops += DiffOperation.DropCustomType(
                id = OperationIdFactory.makeId("DropCustomType", ref, CanonicalPayload.customType(removed.definition)),
                objectRef = ref,
                customType = removed.definition,
            )
        }
        for (changed in diff.customTypesChanged) {
            val ref = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf(changed.name))
            val before = current.customTypes[changed.name] ?: continue
            val after = desired.customTypes[changed.name] ?: continue
            ops += DiffOperation.AlterCustomType(
                id = OperationIdFactory.makeId(
                    "AlterCustomType",
                    ref,
                    "before=" + CanonicalPayload.customType(before) +
                        "->after=" + CanonicalPayload.customType(after),
                ),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapTables(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        blockedTables: Set<String>,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ) {
        val (renamedAdds, renamedRemoves) = mapRenameTables(
            diff, current, desired, blockedTables, renameIndex, diagnostics, ops,
        )
        for (added in diff.tablesAdded) {
            if (added.name in blockedTables) continue
            if (added.name in renamedAdds) continue
            val ref = DiffObjectRef(DiffObjectType.TABLE, listOf(added.name))
            ops += DiffOperation.CreateTable(
                id = OperationIdFactory.makeId("CreateTable", ref, CanonicalPayload.table(added.definition)),
                objectRef = ref,
                table = added.definition,
            )
        }
        for (removed in diff.tablesRemoved) {
            if (removed.name in blockedTables) continue
            if (removed.name in renamedRemoves) continue
            val ref = DiffObjectRef(DiffObjectType.TABLE, listOf(removed.name))
            ops += DiffOperation.DropTable(
                id = OperationIdFactory.makeId("DropTable", ref, CanonicalPayload.table(removed.definition)),
                objectRef = ref,
                table = removed.definition,
            )
        }
        for (changed in diff.tablesChanged) {
            if (changed.name in blockedTables) continue
            mapTableColumns(changed, renameIndex, diagnostics, ops)
            mapTableConstraints(changed, ops)
            mapTableIndices(changed, ops)
            mapTablePrimaryKey(changed, ops)
        }
    }

    /**
     * Plan-2 §F.4 second slice: collapse a `(DropTable, CreateTable)`
     * pair into [DiffOperation.RenameTable] when an active
     * [RenameMappingOverlayEntry] binds the names AND both tables are
     * structurally identical (compared via [CanonicalPayload]). Returns
     * the names removed from the regular drop/create path.
     *
     * Structural mismatch is a non-blocking warning — the regular
     * Drop+Add fallback still renders. The operator can either adjust
     * the schemas (e.g. align columns first) or remove the rename
     * mapping from the overlay.
     */
    private fun mapRenameTables(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        blockedTables: Set<String>,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ): Pair<Set<String>, Set<String>> =
        RenameOverlayMapper.foldRenameTables(
            diff = diff,
            current = current,
            desired = desired,
            blockedTables = blockedTables,
            renameIndex = renameIndex,
            diagnostics = diagnostics,
            ops = ops,
        )

    private fun mapTableColumns(
        table: TableDiff,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ) {
        val (renamedAddedCols, renamedRemovedCols) = mapRenameColumns(table, renameIndex, diagnostics, ops)
        for ((name, def) in table.columnsAdded) {
            if (name in renamedAddedCols) continue
            val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(table.name, name))
            ops += DiffOperation.AddColumn(
                id = OperationIdFactory.makeId("AddColumn", ref, CanonicalPayload.column(def)),
                objectRef = ref,
                column = def,
            )
        }
        for ((name, def) in table.columnsRemoved) {
            if (name in renamedRemovedCols) continue
            val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(table.name, name))
            ops += DiffOperation.DropColumn(
                id = OperationIdFactory.makeId("DropColumn", ref, CanonicalPayload.column(def)),
                objectRef = ref,
                column = def,
            )
        }
        for (cd in table.columnsChanged) mapColumnChange(table.name, cd, ops)
    }

    /**
     * Per-table column-rename detection. Mirrors [mapRenameTables] but
     * scoped to the [TableDiff]'s `columnsAdded`/`columnsRemoved`
     * maps. The rename mapping's `objectType` is treated as `column`
     * if the `fromName` references `<tableName>.<columnName>` OR the
     * mapping carries no qualifier and a unique drop/add candidate is
     * available for the column name.
     */
    private fun mapRenameColumns(
        table: TableDiff,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ): Pair<Set<String>, Set<String>> =
        RenameOverlayMapper.foldRenameColumns(
            table = table,
            renameIndex = renameIndex,
            diagnostics = diagnostics,
            ops = ops,
        )

    private fun mapColumnChange(tableName: String, cd: ColumnDiff, ops: MutableList<DiffOperation>) {
        val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(tableName, cd.name))
        cd.type?.let {
            ops += DiffOperation.AlterColumnType(
                id = OperationIdFactory.makeId("AlterColumnType", ref, "${it.before}->${it.after}"),
                objectRef = ref,
                before = it.before,
                after = it.after,
            )
        }
        cd.required?.let {
            ops += DiffOperation.AlterColumnNullability(
                id = OperationIdFactory.makeId("AlterColumnNullability", ref, "${it.before}->${it.after}"),
                objectRef = ref,
                before = it.before,
                after = it.after,
            )
        }
        cd.default?.let {
            ops += DiffOperation.AlterColumnDefault(
                id = OperationIdFactory.makeId("AlterColumnDefault", ref, "${it.before}->${it.after}"),
                objectRef = ref,
                before = it.before,
                after = it.after,
            )
        }
    }

    private fun mapTableConstraints(table: TableDiff, ops: MutableList<DiffOperation>) {
        for (c in table.constraintsAdded) {
            if (c.type == ConstraintType.CHECK || c.type == ConstraintType.EXCLUDE) continue
            val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, c.name))
            ops += DiffOperation.AddConstraint(
                id = OperationIdFactory.makeId("AddConstraint", ref, CanonicalPayload.constraint(c)),
                objectRef = ref,
                constraint = c,
            )
        }
        for (c in table.constraintsRemoved) {
            if (c.type == ConstraintType.CHECK || c.type == ConstraintType.EXCLUDE) continue
            val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, c.name))
            ops += DiffOperation.DropConstraint(
                id = OperationIdFactory.makeId("DropConstraint", ref, CanonicalPayload.constraint(c)),
                objectRef = ref,
                constraint = c,
            )
        }
        for (vc in table.constraintsChanged) {
            val refOld = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, vc.before.name))
            ops += DiffOperation.DropConstraint(
                id = OperationIdFactory.makeId("DropConstraint", refOld, CanonicalPayload.constraint(vc.before)),
                objectRef = refOld,
                constraint = vc.before,
            )
            val refNew = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, vc.after.name))
            ops += DiffOperation.AddConstraint(
                id = OperationIdFactory.makeId("AddConstraint", refNew, CanonicalPayload.constraint(vc.after)),
                objectRef = refNew,
                constraint = vc.after,
            )
        }
    }

    private fun mapTableIndices(table: TableDiff, ops: MutableList<DiffOperation>) {
        for (idx in table.indicesAdded) {
            val ref = indexRef(table.name, idx)
            ops += DiffOperation.AddIndex(
                id = OperationIdFactory.makeId("AddIndex", ref, CanonicalPayload.index(idx)),
                objectRef = ref,
                index = idx,
            )
        }
        for (idx in table.indicesRemoved) {
            val ref = indexRef(table.name, idx)
            ops += DiffOperation.DropIndex(
                id = OperationIdFactory.makeId("DropIndex", ref, CanonicalPayload.index(idx)),
                objectRef = ref,
                index = idx,
            )
        }
        for (vc in table.indicesChanged) {
            val refOld = indexRef(table.name, vc.before)
            ops += DiffOperation.DropIndex(
                id = OperationIdFactory.makeId("DropIndex", refOld, CanonicalPayload.index(vc.before)),
                objectRef = refOld,
                index = vc.before,
            )
            val refNew = indexRef(table.name, vc.after)
            ops += DiffOperation.AddIndex(
                id = OperationIdFactory.makeId("AddIndex", refNew, CanonicalPayload.index(vc.after)),
                objectRef = refNew,
                index = vc.after,
            )
        }
    }

    private fun indexRef(tableName: String, idx: IndexDefinition): DiffObjectRef {
        val name = idx.name ?: anonIndexKey(idx)
        return DiffObjectRef(DiffObjectType.INDEX, listOf(tableName, name))
    }

    private fun anonIndexKey(idx: IndexDefinition): String {
        val cols = idx.columns.joinToString("_") { it.name }
        val whereHash = idx.where?.hashCode()?.toString(16) ?: "0"
        return "anon_${cols}_${idx.type.name}_${idx.unique}_$whereHash"
    }

    private fun mapTablePrimaryKey(table: TableDiff, ops: MutableList<DiffOperation>) {
        val pk = table.primaryKey ?: return
        val ref = DiffObjectRef(DiffObjectType.PRIMARY_KEY, listOf(table.name))
        if (pk.before.isNotEmpty()) {
            ops += DiffOperation.DropPrimaryKey(
                id = OperationIdFactory.makeId("DropPrimaryKey", ref, pk.before.joinToString(",")),
                objectRef = ref,
                columns = pk.before,
            )
        }
        if (pk.after.isNotEmpty()) {
            ops += DiffOperation.AddPrimaryKey(
                id = OperationIdFactory.makeId("AddPrimaryKey", ref, pk.after.joinToString(",")),
                objectRef = ref,
                columns = pk.after,
            )
        }
    }

    private fun mapViews(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.viewsAdded) {
            val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(added.name))
            ops += DiffOperation.CreateView(
                id = OperationIdFactory.makeId("CreateView", ref, CanonicalPayload.view(added.definition)),
                objectRef = ref,
                view = added.definition,
            )
        }
        for (removed in diff.viewsRemoved) {
            val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(removed.name))
            ops += DiffOperation.DropView(
                id = OperationIdFactory.makeId("DropView", ref, CanonicalPayload.view(removed.definition)),
                objectRef = ref,
                view = removed.definition,
            )
        }
        for (changed in diff.viewsChanged) {
            val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(changed.name))
            val before = current.views[changed.name] ?: continue
            val after = desired.views[changed.name] ?: continue
            ops += DiffOperation.ReplaceView(
                id = OperationIdFactory.makeId(
                    "ReplaceView",
                    ref,
                    "before=" + CanonicalPayload.view(before) +
                        "->after=" + CanonicalPayload.view(after),
                ),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapSequences(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.sequencesAdded) {
            val ref = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(added.name))
            ops += DiffOperation.CreateSequence(
                id = OperationIdFactory.makeId("CreateSequence", ref, added.definition.toString()),
                objectRef = ref,
                sequence = added.definition,
            )
        }
        for (removed in diff.sequencesRemoved) {
            val ref = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(removed.name))
            ops += DiffOperation.DropSequence(
                id = OperationIdFactory.makeId("DropSequence", ref, removed.definition.toString()),
                objectRef = ref,
                sequence = removed.definition,
            )
        }
        for (changed in diff.sequencesChanged) {
            val ref = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(changed.name))
            val before = current.sequences[changed.name] ?: continue
            val after = desired.sequences[changed.name] ?: continue
            ops += DiffOperation.AlterSequence(
                id = OperationIdFactory.makeId("AlterSequence", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapFunctions(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.functionsAdded) {
            val ref = DiffObjectRef(DiffObjectType.FUNCTION, listOf(added.name))
            ops += DiffOperation.CreateFunction(
                id = OperationIdFactory.makeId("CreateFunction", ref, added.definition.toString()),
                objectRef = ref,
                function = added.definition,
            )
        }
        for (removed in diff.functionsRemoved) {
            val ref = DiffObjectRef(DiffObjectType.FUNCTION, listOf(removed.name))
            ops += DiffOperation.DropFunction(
                id = OperationIdFactory.makeId("DropFunction", ref, removed.definition.toString()),
                objectRef = ref,
                function = removed.definition,
            )
        }
        for (changed in diff.functionsChanged) {
            val ref = DiffObjectRef(DiffObjectType.FUNCTION, listOf(changed.name))
            val before = current.functions[changed.name] ?: continue
            val after = desired.functions[changed.name] ?: continue
            ops += DiffOperation.ReplaceFunction(
                id = OperationIdFactory.makeId("ReplaceFunction", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapProcedures(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.proceduresAdded) {
            val ref = DiffObjectRef(DiffObjectType.PROCEDURE, listOf(added.name))
            ops += DiffOperation.CreateProcedure(
                id = OperationIdFactory.makeId("CreateProcedure", ref, added.definition.toString()),
                objectRef = ref,
                procedure = added.definition,
            )
        }
        for (removed in diff.proceduresRemoved) {
            val ref = DiffObjectRef(DiffObjectType.PROCEDURE, listOf(removed.name))
            ops += DiffOperation.DropProcedure(
                id = OperationIdFactory.makeId("DropProcedure", ref, removed.definition.toString()),
                objectRef = ref,
                procedure = removed.definition,
            )
        }
        for (changed in diff.proceduresChanged) {
            val ref = DiffObjectRef(DiffObjectType.PROCEDURE, listOf(changed.name))
            val before = current.procedures[changed.name] ?: continue
            val after = desired.procedures[changed.name] ?: continue
            ops += DiffOperation.ReplaceProcedure(
                id = OperationIdFactory.makeId("ReplaceProcedure", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapTriggers(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.triggersAdded) {
            val ref = DiffObjectRef(DiffObjectType.TRIGGER, listOf(added.name))
            ops += DiffOperation.CreateTrigger(
                id = OperationIdFactory.makeId("CreateTrigger", ref, added.definition.toString()),
                objectRef = ref,
                trigger = added.definition,
            )
        }
        for (removed in diff.triggersRemoved) {
            val ref = DiffObjectRef(DiffObjectType.TRIGGER, listOf(removed.name))
            ops += DiffOperation.DropTrigger(
                id = OperationIdFactory.makeId("DropTrigger", ref, removed.definition.toString()),
                objectRef = ref,
                trigger = removed.definition,
            )
        }
        for (changed in diff.triggersChanged) {
            val ref = DiffObjectRef(DiffObjectType.TRIGGER, listOf(changed.name))
            val before = current.triggers[changed.name] ?: continue
            val after = desired.triggers[changed.name] ?: continue
            ops += DiffOperation.ReplaceTrigger(
                id = OperationIdFactory.makeId("ReplaceTrigger", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

}
