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
        migrationOverlays: List<MigrationOverlayDocument>,
        capabilities: RenameProjectionCapabilities,
    ): MapperResult = finalizeIds(
        prepare(diff, current, desired, blockedTables, migrationOverlays, capabilities),
    )

    /**
     * F.4 dependency-projection T2/T3: phase 1 of the two-phase
     * mapping pipeline. Walks the [SchemaDiff] and produces the raw
     * operations + diagnostics, consulting the
     * [RenameDependencyPolicy] resolved from
     * [capabilities].dialect for each rename candidate. The result is
     * fed to [finalizeIds] which applies ID disambiguation and (in
     * later slices) remaps dependency references when a candidate's
     * final ID changes.
     */
    internal fun prepare(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        blockedTables: Set<String>,
        migrationOverlays: List<MigrationOverlayDocument>,
        capabilities: RenameProjectionCapabilities,
    ): PreparedMapping {
        val renameIndex = RenameOverlayIndex.build(migrationOverlays)
        val diagnostics = mutableListOf<DiffDiagnostic>()
        diagnostics += renameIndex.issues
        val ops = mutableListOf<DiffOperation>()
        val renameProjections = mutableListOf<RenameProjectionReport>()
        val ctx = RenameMappingContext(current, desired, capabilities)
        OperationMapperSchemaObjects.mapCustomTypes(diff, current, desired, ops)
        // T5: mapTables now reports the view names whose
        // reprojection (`DropView` + `CreateView`) the projector
        // emitted from inside the rename pipeline. The subsequent
        // mapViews skips them in `viewsChanged` so the plan does not
        // carry a duplicate `ReplaceView` alongside the projector's
        // explicit Drop+Create.
        // T6: mapTables also collects structured per-candidate
        // [RenameProjectionReport] entries so DiffPlanner can attach
        // them to `DiffResult.renameProjections`.
        val absorbedViews = mapTables(diff, ctx, blockedTables, renameIndex, diagnostics, ops, renameProjections)
        mapViews(diff, current, desired, absorbedViews, diagnostics, ops)
        OperationMapperSchemaObjects.mapSequences(diff, current, desired, ops)
        OperationMapperRoutines.mapFunctions(diff, current, desired, ops)
        OperationMapperRoutines.mapProcedures(diff, current, desired, ops)
        OperationMapperRoutines.mapTriggers(diff, current, desired, ops)
        return PreparedMapping(
            operations = ops,
            diagnostics = diagnostics,
            renameProjections = renameProjections,
        )
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
            return MapperResult(
                operations = emptyList(),
                diagnostics = prepared.diagnostics,
                renameProjections = prepared.renameProjections,
            )
        }
        val (finalOps, idRewrites) = disambiguateOpsWithRewrites(prepared.operations)
        // T6: if disambiguation renamed any op, the
        // `renameProjections` entries that pin `renameOperationId` /
        // `fallbackOperationIds` / `explicit[].operationId` must
        // follow the rename atomically. Otherwise the report would
        // reference operation ids that no longer exist in the final
        // plan.
        val finalProjections = if (idRewrites.isEmpty()) {
            prepared.renameProjections
        } else {
            prepared.renameProjections.map { it.remapIds(idRewrites) }
        }
        return MapperResult(
            operations = finalOps,
            diagnostics = prepared.diagnostics,
            renameProjections = finalProjections,
        )
    }

    /**
     * Apply the disambiguation rewrite map to every op-id reference
     * the report carries. The rewrite map is keyed by the
     * pre-disambig id — naive `rewrites[id] ?: id`. F.4 makes this
     * safe by construction: per-candidate op ids
     * (`OperationIdFactory.makeId("RenameTable", …)`, fallback
     * Drop/Create, explicit View Drop/Create) are derived from the
     * candidate's unique from/to/overlay-hash triple, so two
     * different candidates produce different ids. The remap therefore
     * only rewrites references that genuinely belong to the renamed
     * op.
     */
    private fun RenameProjectionReport.remapIds(rewrites: Map<String, String>): RenameProjectionReport {
        if (rewrites.isEmpty()) return this
        val renameId = renameOperationId?.let { rewrites[it] ?: it }
        val fallbackIds = if (fallbackOperationIds.isEmpty()) {
            fallbackOperationIds
        } else {
            fallbackOperationIds.map { rewrites[it] ?: it }
        }
        val explicitRefs = if (explicit.isEmpty()) {
            explicit
        } else {
            explicit.map { ref -> rewrites[ref.operationId]?.let { ref.copy(operationId = it) } ?: ref }
        }
        if (renameId == renameOperationId &&
            fallbackIds == fallbackOperationIds &&
            explicitRefs == explicit
        ) {
            return this
        }
        return copy(
            renameOperationId = renameId,
            fallbackOperationIds = fallbackIds,
            explicit = explicitRefs,
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
        val renameProjections: List<RenameProjectionReport> = emptyList(),
    )

    /**
     * Result wrapper so the planner can collect [diagnostics] generated
     * by the mapper (e.g. F.4 `RENAME_OVERLAY_STRUCTURAL_MISMATCH`)
     * alongside the operations. T6 adds the per-candidate
     * [renameProjections] report list so `DiffPlanner.plan` can
     * attach it to `DiffResult.renameProjections`.
     */
    internal data class MapperResult(
        val operations: List<DiffOperation>,
        val diagnostics: List<DiffDiagnostic>,
        val renameProjections: List<RenameProjectionReport> = emptyList(),
    )

    private fun disambiguateOpsWithRewrites(
        ops: List<DiffOperation>,
    ): Pair<List<DiffOperation>, Map<String, String>> {
        if (ops.isEmpty()) return ops to emptyMap()
        // `OperationIdFactory.disambiguate` assigns suffixes
        // monotonically (first occurrence keeps the base id, second
        // gets `#2`, …). The rewrite map below is therefore
        // single-target per source key — `idRewrites[id] ?: id` cannot
        // collapse two distinct dependency references.
        val pairs = ops.mapIndexed { idx, op -> op.id to idx }
        val resolved = OperationIdFactory.disambiguate(pairs)
        val idRewrites = mutableMapOf<String, String>()
        val withRenamedIds = ops.mapIndexed { idx, op ->
            val newId = resolved[idx]
            if (newId == op.id) {
                op
            } else {
                idRewrites[op.id] = newId
                op.withId(newId)
            }
        }
        if (idRewrites.isEmpty()) return withRenamedIds to emptyMap()
        // F.4 dependency-projection T4: when disambiguation renames an
        // operation, any synthetic delta op that pinned that ID via
        // `dependencies = setOf(candidateId)` must follow the rename
        // atomically. Walk every op once and rewrite each
        // `dependencies` entry that hit the rewrite map.
        val withRemappedDeps = withRenamedIds.map { op ->
            if (op.dependencies.isEmpty()) {
                op
            } else {
                val remapped = op.dependencies.mapTo(mutableSetOf()) { idRewrites[it] ?: it }
                if (remapped == op.dependencies) op else op.withDependencies(remapped)
            }
        }
        return withRemappedDeps to idRewrites
    }

    private fun mapTables(
        diff: SchemaDiff,
        ctx: RenameMappingContext,
        blockedTables: Set<String>,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
        renameProjections: MutableList<RenameProjectionReport>,
    ): Set<String> {
        val fold = mapRenameTables(diff, ctx, blockedTables, renameIndex, diagnostics, ops, renameProjections)
        val renamedAdds = fold.absorbedToNames
        val renamedRemoves = fold.absorbedFromNames
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
            mapTableColumns(changed, ctx, renameIndex, diagnostics, ops, renameProjections)
            mapTableConstraints(changed, ops)
            mapTableIndices(changed, ops)
            mapTablePrimaryKey(changed, ops)
        }
        return fold.absorbedViews
    }

    /**
     * Plan-2 §F.4 third slice: delegate to [RenameOverlayMapper]
     * which consults the dialect-specific
     * [RenameDependencyPolicy] (resolved from
     * [RenameProjectionCapabilities.dialect]) before folding
     * `(DropTable, CreateTable)` pairs into [DiffOperation.RenameTable].
     * Returns the names absorbed from the regular drop/create path and
     * the view names absorbed via T5 explicit reprojection.
     */
    private fun mapRenameTables(
        diff: SchemaDiff,
        ctx: RenameMappingContext,
        blockedTables: Set<String>,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
        renameProjections: MutableList<RenameProjectionReport>,
    ): RenameOverlayMapper.TableFoldResult =
        RenameOverlayMapper.foldRenameTables(
            diff = diff,
            ctx = ctx,
            blockedTables = blockedTables,
            renameIndex = renameIndex,
            diagnostics = diagnostics,
            ops = ops,
            reports = renameProjections,
        )

    private fun mapTableColumns(
        table: TableDiff,
        ctx: RenameMappingContext,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
        renameProjections: MutableList<RenameProjectionReport>,
    ) {
        val (renamedAddedCols, renamedRemovedCols) = mapRenameColumns(
            table, ctx, renameIndex, diagnostics, ops, renameProjections,
        )
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
     * Per-table column-rename detection. Same shape as
     * [mapRenameTables] but scoped to the [TableDiff]'s
     * `columnsAdded`/`columnsRemoved` maps.
     */
    private fun mapRenameColumns(
        table: TableDiff,
        ctx: RenameMappingContext,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
        renameProjections: MutableList<RenameProjectionReport>,
    ): Pair<Set<String>, Set<String>> =
        RenameOverlayMapper.foldRenameColumns(
            table = table,
            renameIndex = renameIndex,
            ctx = ctx,
            diagnostics = diagnostics,
            ops = ops,
            reports = renameProjections,
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
        absorbedViews: Set<String>,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.viewsAdded) {
            if (added.definition.materialized) {
                OperationMapperMaterializedView.emitCreate(added, diagnostics, ops)
            } else {
                val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(added.name))
                ops += DiffOperation.CreateView(
                    id = OperationIdFactory.makeId("CreateView", ref, CanonicalPayload.view(added.definition)),
                    objectRef = ref,
                    view = added.definition,
                )
            }
        }
        for (removed in diff.viewsRemoved) {
            if (removed.definition.materialized) {
                OperationMapperMaterializedView.emitDrop(removed, diagnostics, ops)
            } else {
                val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(removed.name))
                ops += DiffOperation.DropView(
                    id = OperationIdFactory.makeId("DropView", ref, CanonicalPayload.view(removed.definition)),
                    objectRef = ref,
                    view = removed.definition,
                )
            }
        }
        // viewsAdded / viewsRemoved are NOT filtered against
        // [absorbedViews]: the reprojector iterates `current.views`
        // only, so a brand-new view (viewsAdded) is never absorbed;
        // a view dropped from desired (viewsRemoved) hits the
        // reprojector's "missing in desired" blocker path so the
        // candidate falls back to drop+create with no absorption.
        // Only the `viewsChanged` path can collide with an absorbed
        // reprojection, and that's what the skip below guards against.
        for (changed in diff.viewsChanged) {
            // T5: a view whose underlying renamed table forced an
            // explicit DropView+CreateView pair from the rename
            // projector lives in [absorbedViews]; do not emit a
            // duplicate `ReplaceView` here.
            if (changed.name in absorbedViews) continue
            val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(changed.name))
            val before = current.views[changed.name] ?: continue
            val after = desired.views[changed.name] ?: continue
            val op = DiffOperation.ReplaceView(
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
            ops += op
            if (before.materialized != after.materialized) {
                OperationMapperMaterializedView.emitConversionDiagnostic(
                    name = changed.name,
                    before = before,
                    after = after,
                    replaceOpId = op.id,
                    diagnostics = diagnostics,
                )
            }
        }
    }

}
