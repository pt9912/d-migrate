package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.EffectivePrimaryKey
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.util.sha256Hex

/**
 * Maps a [SchemaDiff] to a flat list of [DiffOperation]s with stable
 * IDs but no dependency edges yet. The [DiffPlanner] orchestrates,
 * [DependencyAnalyzer] then attaches edges, [TopologicalSorter]
 * orders the result.
 *
 * `CHECK` and `EXCLUDE` constraints flow through the same
 * `AddConstraint` / `DropConstraint` ops as UNIQUE / FOREIGN_KEY
 * since F.5 Sub-Slice A. A `constraintsChanged` entry emits a
 * `DropConstraint(before)` + `AddConstraint(after)` pair, both
 * tagged with a shared deterministic [DiffOperation.AddConstraint.replacePairId]
 * so reporters recognise the Replace; the [ConstraintReplaceContract]
 * post-pass then pins per-op reversibility. Per-dialect renderers
 * decide whether to emit DDL or to block.
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
        triggerPlanningContext: TriggerPlanningContext = TriggerPlanningContext(),
    ): MapperResult = finalizeIds(
        prepare(diff, current, desired, blockedTables, migrationOverlays, capabilities, triggerPlanningContext),
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
        triggerPlanningContext: TriggerPlanningContext = TriggerPlanningContext(),
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
        val absorbedViewsFromTables = mapTables(
            diff, ctx, blockedTables, renameIndex, diagnostics, ops, renameProjections,
        )
        // F.4 A.2 Teil 2: fold rename-overlay mappings for the five
        // new object-level kinds before the regular Create/Drop loops
        // run. Each fold either:
        // - emits a single `Rename*` op and absorbs the from/to names
        //   so the regular loop skips them;
        // - registers a `RenameProvenance` keyed by from/to so the
        //   regular loop tags its Drop+Create ops; or
        // - emits an `OBJECT_RENAME_UNSUPPORTED` BLOCKER diagnostic.
        val viewFold = RenameObjectMapper.foldRenameViews(diff, ctx, renameIndex, diagnostics, ops)
        val sequenceFold = RenameObjectMapper.foldRenameSequences(diff, ctx, renameIndex, diagnostics, ops)
        val functionFold = RenameObjectMapper.foldRenameFunctions(diff, ctx, renameIndex, diagnostics, ops)
        val procedureFold = RenameObjectMapper.foldRenameProcedures(diff, ctx, renameIndex, diagnostics, ops)
        val triggerFold = RenameObjectMapper.foldRenameTriggers(diff, ctx, renameIndex, diagnostics, ops)
        mapViews(diff, current, desired, absorbedViewsFromTables, viewFold, diagnostics, ops)
        OperationMapperSchemaObjects.mapSequences(diff, current, desired, sequenceFold, ops)
        OperationMapperRoutines.mapFunctions(diff, current, desired, functionFold, ops)
        OperationMapperRoutines.mapProcedures(diff, current, desired, procedureFold, ops)
        OperationMapperRoutines.mapTriggers(diff, current, desired, triggerFold, ops, triggerPlanningContext)
        // F.4 Sub-Slice D: rewrite `SequenceNextVal` references in
        // `CreateTable` / `AddColumn` / `AlterColumnDefault` ops that
        // point at a sequence renamed elsewhere in this plan. The
        // companion DependencyAnalyzer extension makes those column-
        // bearing ops depend on the corresponding `RenameSequence`,
        // so the topological sort places the rename first regardless
        // of the order in which the Mapper emitted the ops.
        val reprojected = SequenceDefaultReprojector.apply(ops)
        // F.5 Sub-Slice F: pin reversibility for CHECK / EXCLUDE
        // Add/Drop ops (Replace pairs and standalone). Down-rendering
        // of a `DropConstraint(CHECK/EXCLUDE)` without expression
        // surfaces `ROLLBACK_NOT_POSSIBLE` once this classification
        // reaches the dialect renderer.
        val finalOps = ConstraintReplaceContract.apply(reprojected)
        return PreparedMapping(
            operations = finalOps,
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
            val definition = materializeEffectivePrimaryKey(added.definition)
            ops += DiffOperation.CreateTable(
                id = OperationIdFactory.makeId("CreateTable", ref, CanonicalPayload.table(definition)),
                objectRef = ref,
                table = definition,
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
            mapTablePartitioning(changed, diagnostics, ops)
        }
        return fold.absorbedViews
    }

    /**
     * When a `CreateTable`'s desired definition carries no explicit
     * `primary_key` but its *effective* PK is derivable — a single
     * `identifier` column, the same v3 rule [EffectivePrimaryKey] gives
     * the Fingerprint and the target-aware `TableComparator` — materialise
     * it into the definition so every dialect renderer emits the PK
     * uniformly: MySQL's AUTO_INCREMENT column gets its required KEY, PG's
     * `SERIAL` gets `PRIMARY KEY`, and SQLite's inline `INTEGER PRIMARY KEY
     * AUTOINCREMENT` is deduped against the table-level clause downstream
     * (`SqliteDiffSimpleOps`).
     *
     * No-op when an explicit PK already exists (then [EffectivePrimaryKey.of]
     * returns it verbatim) or the PK is ambiguous (multiple `identifier`
     * columns → empty). [TableDefinition] is immutable, so the `.copy()`
     * never mutates the desired schema — the Fingerprint (which applies
     * [EffectivePrimaryKey.of] itself) stays unaffected. The same copy feeds
     * both `table =` and the operation-id payload so the id remains
     * content-consistent with the rendered table.
     */
    private fun materializeEffectivePrimaryKey(definition: TableDefinition): TableDefinition {
        val effective = EffectivePrimaryKey.of(definition)
        return if (effective == definition.primaryKey) definition else definition.copy(primaryKey = effective)
    }

    /**
     * A partitioning change splits into two very different cases, and the
     * mapper's job is to tell them apart ([PartitionChangeClassifier]).
     *
     * A table cannot be re-partitioned in place — there is no
     * `ALTER TABLE … PARTITION BY` — so a changed strategy or key stays a
     * WARNING, now naming which case it is. But a changed *set of children* is
     * an ordinary statement in every partitioning dialect, and rolling
     * partitioning (add this month, drop the oldest) is the common case: it
     * becomes an [DiffOperation.AlterTablePartitions] the dialect renders.
     *
     * The risk follows the delta: losing a child means losing rows in
     * PostgreSQL and MySQL (SQL Server's `MERGE RANGE` moves them into the
     * neighbour). The classification cannot tell dialects apart, so it takes
     * the conservative reading.
     */
    private fun mapTablePartitioning(
        table: TableDiff,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ) {
        val change = table.partitioning ?: return
        when (val classified = PartitionChangeClassifier.classify(change.before, change.after)) {
            is PartitionChange.ChildrenChanged -> ops += partitionOperation(table.name, change, classified.delta)
            is PartitionChange.NotResolvable -> diagnostics += partitionDiagnostic(table.name, classified.reason)
        }
    }

    private fun partitionOperation(
        table: String,
        change: ValueChange<PartitionConfig?>,
        delta: PartitionDelta,
    ): DiffOperation.AlterTablePartitions {
        val before = requireNotNull(change.before) { "resolvable partition change without a before state" }
        val after = requireNotNull(change.after) { "resolvable partition change without an after state" }
        val ref = DiffObjectRef(DiffObjectType.TABLE, listOf(table))
        // Zerstörend ist nicht „ein Kind weniger", sondern „ein Bereich
        // weniger": ein entfallenes Kind, dessen Bereich die hinzugekommenen
        // wieder abdecken, ist eine Aufteilung. Ohne die Unterscheidung
        // verlangte das Hinzufügen einer Partition in SQL Server
        // `--allow-destructive`, weil dort jede eingefügte Grenze ein Kind
        // ersetzt.
        val losesChild = delta.droppedOutright.isNotEmpty()
        val gainsChild = delta.addedOutright.isNotEmpty()
        return DiffOperation.AlterTablePartitions(
            id = OperationIdFactory.makeId("AlterTablePartitions", ref, CanonicalPayload.partitioning(before, after)),
            objectRef = ref,
            before = before,
            after = after,
            delta = delta,
            risks = OperationRisks(
                up = partitionRisk(losesChild),
                down = partitionRisk(gainsChild),
            ),
        )
    }

    private fun partitionRisk(dropsPartition: Boolean): OperationRisk =
        if (dropsPartition) {
            OperationRisk(destructive = true, dataLossPossible = true, requiresManualConfirmation = true)
        } else {
            OperationRisk.SAFE
        }

    private fun partitionDiagnostic(table: String, reason: PartitionChangeReason): DiffDiagnostic {
        val cause = when (reason) {
            PartitionChangeReason.PARTITIONING_ADDED ->
                "the table is not partitioned and cannot be partitioned in place"
            PartitionChangeReason.PARTITIONING_REMOVED ->
                "the table's partitioning cannot be removed in place"
            PartitionChangeReason.STRATEGY_CHANGED ->
                "the partitioning strategy changed"
            PartitionChangeReason.KEY_CHANGED ->
                "the partition key changed"
            PartitionChangeReason.CHILD_NAMES_CHANGED ->
                "the partition boundaries are unchanged and only the child names differ"
            PartitionChangeReason.CHILD_INDICES_CHANGED ->
                "the partition boundaries are unchanged and only the child-local indices differ"
        }
        return DiffDiagnostic(
            code = "PARTITIONING_CHANGE_NOT_APPLIED",
            message = "Table '$table': a partitioning change was detected but not emitted as a " +
                "migration operation — $cause. Recreate the table with the desired partitioning " +
                "manually if the change must be applied.",
            severity = DiffDiagnostic.Severity.WARNING,
        )
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
        // F.5 Sub-Slice A (2026-05-19): CHECK + EXCLUDE constraints
        // now flow through the mapper alongside UNIQUE / FOREIGN_KEY.
        // The per-dialect renderer (Sub-Slice B/C/D) decides whether
        // to emit DDL or block with `DIALECT_UNSUPPORTED_OPERATION`.
        // The planner still blocks the table upstream if any CHECK
        // expression looks like a cross-table sub-query (the
        // `CrossTableCheckHeuristic` path).
        for (c in table.constraintsAdded) {
            val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, c.name))
            ops += DiffOperation.AddConstraint(
                id = OperationIdFactory.makeId("AddConstraint", ref, CanonicalPayload.constraint(c)),
                objectRef = ref,
                constraint = c,
            )
        }
        for (c in table.constraintsRemoved) {
            val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, c.name))
            ops += DiffOperation.DropConstraint(
                id = OperationIdFactory.makeId("DropConstraint", ref, CanonicalPayload.constraint(c)),
                objectRef = ref,
                constraint = c,
            )
        }
        for (vc in table.constraintsChanged) {
            val refOld = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, vc.before.name))
            val refNew = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, vc.after.name))
            // F.5 Sub-Slice F: tag both ops of a `constraintsChanged`
            // pair with a deterministic `replacePairId` so the
            // `ConstraintReplaceContract` post-pass and downstream
            // reporters can treat the (Drop, Add) pair as one logical
            // replacement without conflating the unique op ids that
            // dependency-sort / artefact binding /
            // `renderedStatements.operationIds` rely on. Only CHECK
            // and EXCLUDE participate in the contract; UNIQUE /
            // FOREIGN_KEY pairs are out-of-scope for F.
            val pairId: String? = if (
                vc.before.type == ConstraintType.CHECK ||
                vc.before.type == ConstraintType.EXCLUDE
            ) {
                replacePairIdFor(table.name, vc.before, vc.after)
            } else {
                null
            }
            val dropId = OperationIdFactory.makeId("DropConstraint", refOld, CanonicalPayload.constraint(vc.before))
            ops += DiffOperation.DropConstraint(
                id = dropId,
                objectRef = refOld,
                constraint = vc.before,
                replacePairId = pairId,
            )
            ops += DiffOperation.AddConstraint(
                id = OperationIdFactory.makeId("AddConstraint", refNew, CanonicalPayload.constraint(vc.after)),
                objectRef = refNew,
                constraint = vc.after,
                replacePairId = pairId,
                // Siehe [ORDERING] unten: behaelt der Constraint seinen Namen --
                // der Normalfall einer Definitionsaenderung -- teilen sich beide
                // Operationen den Objektnamen, und ohne diese Kante legt der Plan
                // ihn an, bevor er den alten verwirft.
                dependencies = setOf(dropId),
            )
        }
    }

    /**
     * F.5 Sub-Slice F: deterministic pair identity for a
     * `DropConstraint(before) + AddConstraint(after)` replacement.
     * Folds the table name and both canonical payloads through
     * SHA-256 and keeps the leading 12 hex chars — same prefix length
     * the [OperationIdFactory] uses for its own hash tails.
     */
    private fun replacePairIdFor(
        tableName: String,
        before: ConstraintDefinition,
        after: ConstraintDefinition,
    ): String {
        val seed = buildString {
            append("ReplaceConstraint")
            append(CanonicalEncoding.SEP).append(tableName)
            append(CanonicalEncoding.SEP).append(before.name)
            append(CanonicalEncoding.SEP).append(CanonicalPayload.constraint(before))
            append(CanonicalEncoding.SEP).append(CanonicalPayload.constraint(after))
        }
        return "replace:" + sha256Hex(seed).take(REPLACE_PAIR_HEX_LEN)
    }

    private const val REPLACE_PAIR_HEX_LEN: Int = 12

    private fun mapTableIndices(table: TableDiff, ops: MutableList<DiffOperation>) {
        // Eine Tabelle hat genau EINE Ablage. Wechselt sie von einem Index auf
        // einen anderen -- etwa weil der Index umbenannt wurde und deshalb als
        // Entfernen + Hinzufuegen erscheint --, muss der abgebende zuerst weichen.
        // Die beiden tragen verschiedene Objektnamen, die Kante bei
        // [ORDERING] greift also nicht; ohne diese hier laeuft das Anlegen des
        // neuen, waehrend der alte die Ablage noch haelt (SQL Server Msg 1902).
        // Dialekte ohne Ablage-Steuerung ignorieren `clustered` -- fuer sie ist
        // die Kante folgenlos.
        val storageReleaseIds = table.indicesRemoved.filter { it.clustered }.map { idx ->
            OperationIdFactory.makeId("DropIndex", indexRef(table.name, idx), CanonicalPayload.index(idx))
        }.toSet()

        for (idx in table.indicesAdded) {
            val ref = indexRef(table.name, idx)
            ops += DiffOperation.AddIndex(
                id = OperationIdFactory.makeId("AddIndex", ref, CanonicalPayload.index(idx)),
                objectRef = ref,
                index = idx,
                dependencies = if (idx.clustered) storageReleaseIds else emptySet(),
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
            val dropId = OperationIdFactory.makeId("DropIndex", refOld, CanonicalPayload.index(vc.before))
            ops += DiffOperation.DropIndex(id = dropId, objectRef = refOld, index = vc.before)
            val refNew = indexRef(table.name, vc.after)
            ops += DiffOperation.AddIndex(
                id = OperationIdFactory.makeId("AddIndex", refNew, CanonicalPayload.index(vc.after)),
                objectRef = refNew,
                index = vc.after,
                // Siehe [ORDERING] bei mapTablePrimaryKey. Fuer Indizes heisst der
                // Ausgang: der Name existiert beim Anlegen schon, und der Server
                // lehnt ab (SQL Server Msg 1913, PostgreSQL 42P07).
                dependencies = setOf(dropId),
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

    /**
     * [ORDERING] Warum Anlegen und Loeschen desselben Objekts eine Kante brauchen.
     *
     * `TopologicalSorter.stableOrder` ordnet nach Phase, Objekttyp, Objektname --
     * und bricht den Gleichstand ueber die Operations-ID. Die ID beginnt mit der
     * Operationsart (`OperationIdFactory.makeId`), also steht `Add…`
     * lexikografisch **immer** vor `Drop…`. Zwei Operationen auf demselben Objekt
     * in derselben Phase kommen damit in der falschen Reihenfolge heraus, und
     * zwar deterministisch, nicht mal so und mal so.
     *
     * Fuer den Primaerschluessel ist der Objektname immer gleich, fuer einen
     * Constraint, wenn er seinen Namen behaelt. Ohne Kante rendert der Plan
     * `ADD CONSTRAINT` vor `DROP CONSTRAINT` -- SQL Server antwortet mit
     * Msg 1779 bzw. 2714, PostgreSQL mit 42P16 bzw. 42710.
     */
    private fun mapTablePrimaryKey(table: TableDiff, ops: MutableList<DiffOperation>) {
        val pk = table.primaryKey ?: return
        val ref = DiffObjectRef(DiffObjectType.PRIMARY_KEY, listOf(table.name))
        val dropId = OperationIdFactory.makeId("DropPrimaryKey", ref, pk.before.joinToString(","))
        if (pk.before.isNotEmpty()) {
            ops += DiffOperation.DropPrimaryKey(id = dropId, objectRef = ref, columns = pk.before)
        }
        if (pk.after.isNotEmpty()) {
            ops += DiffOperation.AddPrimaryKey(
                id = OperationIdFactory.makeId("AddPrimaryKey", ref, pk.after.joinToString(",")),
                objectRef = ref,
                columns = pk.after,
                // Nur wenn es auch etwas zu verwerfen gibt: eine Tabelle, die
                // ihren ersten Primaerschluessel bekommt, wartet auf nichts.
                dependencies = if (pk.before.isNotEmpty()) setOf(dropId) else emptySet(),
            )
        }
    }

    private fun mapViews(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        absorbedViews: Set<String>,
        viewFold: RenameObjectMapper.ObjectFoldResult,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.viewsAdded) {
            if (added.name in viewFold.absorbedToNames) continue
            if (added.definition.materialized) {
                OperationMapperMaterializedView.emitCreate(added, diagnostics, ops)
            } else {
                val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(added.name))
                ops += DiffOperation.CreateView(
                    id = OperationIdFactory.makeId("CreateView", ref, CanonicalPayload.view(added.definition)),
                    objectRef = ref,
                    view = added.definition,
                    renameProvenance = viewFold.fallbackByToName[added.name],
                )
            }
        }
        for (removed in diff.viewsRemoved) {
            if (removed.name in viewFold.absorbedFromNames) continue
            if (removed.definition.materialized) {
                OperationMapperMaterializedView.emitDrop(removed, diagnostics, ops)
            } else {
                val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(removed.name))
                ops += DiffOperation.DropView(
                    id = OperationIdFactory.makeId("DropView", ref, CanonicalPayload.view(removed.definition)),
                    objectRef = ref,
                    view = removed.definition,
                    renameProvenance = viewFold.fallbackByFromName[removed.name],
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
            val before = current.views[changed.name] ?: continue
            val after = desired.views[changed.name] ?: continue
            val materializedFlip = before.materialized != after.materialized
            // Plan-2 §8 D.3b Sub-Slice B: a materialized-view body /
            // columns change (both sides remain materialized) routes to
            // the dedicated `ReplaceMaterializedView` op (DROP+CREATE
            // under one operation id, atomic under PG's transactional
            // DDL). A `materialized` flag flip is instead a `View↔MV`
            // conversion which D.3b blocks via
            // `BLOCKED_CONVERSION_UNSUPPORTED` on a `ReplaceView`
            // placeholder so the report builder has an operation id to
            // attach the contract to.
            if (!materializedFlip && before.materialized && after.materialized) {
                OperationMapperMaterializedView.emitReplace(
                    name = changed.name,
                    before = before,
                    after = after,
                    diagnostics = diagnostics,
                    ops = ops,
                )
                continue
            }
            val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(changed.name))
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
            if (materializedFlip) {
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
