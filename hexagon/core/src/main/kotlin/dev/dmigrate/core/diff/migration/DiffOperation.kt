package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.model.DefaultValue

/**
 * Single migratable operation produced by the [DiffPlanner].
 *
 * Each operation is self-contained: it knows what schema object it
 * targets ([objectRef]), where it sits in the dependency graph
 * ([dependencies]), what phase it belongs to as a tie-breaker
 * ([phase]), whether it is reversible ([reversibility]), and what
 * risks it carries on Up- and Down-sides ([risks]). The concrete
 * subtypes carry the rendering payload (e.g. a `CreateTable` has the
 * full new [TableDefinition], an `AddColumn` has the table name plus
 * the new column name + [ColumnDefinition]).
 *
 * IDs are assigned by [OperationIdFactory]; subtypes do not generate
 * them themselves so the planner controls the deterministic ID
 * derivation per `docs/planning/done/diffresult-migration-plan.md
 * §4.2.1`.
 *
 * The catalog mirrors the §4.3 list. A dialect renderer that does not
 * support a given operation surfaces a blocker via [OperationRisk.notes]
 * + a [DiffDiagnostic.Severity.BLOCKER] in the parent [DiffResult]; it
 * never silently skips.
 */
sealed interface DiffOperation {
    val id: String
    val objectRef: DiffObjectRef
    val phase: DiffPhase
    val dependencies: Set<String>
    val reversibility: Reversibility
    val risks: OperationRisks

    val objectType: DiffObjectType get() = objectRef.type

    /**
     * Returns a copy of this operation with [dependencies] replaced.
     * The [DiffPlanner] uses this in its second pass after operation
     * IDs are known, so a 31-arm `when` on subtype is not needed.
     */
    fun withDependencies(dependencies: Set<String>): DiffOperation

    /**
     * Returns a copy of this operation with [id] replaced. Used by
     * [OperationMapper] when [OperationIdFactory.disambiguate]
     * resolves a base-ID collision into `#N`-suffixed variants.
     */
    fun withId(id: String): DiffOperation

    // ── Tables ──────────────────────────────────────────────────────

    data class CreateTable(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val table: TableDefinition,
        override val phase: DiffPhase = DiffPhase.TABLES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC_WITH_DATA_RISK,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk.SAFE,
            down = OperationRisk(destructive = true, dataLossPossible = true, requiresManualConfirmation = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropTable(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val table: TableDefinition,
        override val phase: DiffPhase = DiffPhase.TABLES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.NOT_REVERSIBLE,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true, dataLossPossible = true, requiresManualConfirmation = true),
            down = null,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    /**
     * Plan-2 §F.4 second slice: collapses a matching `DropTable` +
     * `CreateTable` pair into a native rename when an active
     * `RenameMappingOverlayEntry` binds the two names and the source
     * and target tables are structurally identical (compared via
     * [CanonicalPayload]). [overlayHash] pins the overlay that
     * authorised the rename so reports / artefacts can correlate
     * back to the operator decision.
     */
    data class RenameTable(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val fromName: String,
        val toName: String,
        val overlaySource: String,
        /**
         * T6 entry-provenance: stable identifier of the overlay entry
         * that authorised this rename. Multiple entries can share the
         * same `overlayHash`, so the report carrier identifies the
         * authorising entry by `(overlaySource, overlayEntryId)` —
         * not by hash alone.
         */
        val overlayEntryId: String,
        val overlayHash: String?,
        override val phase: DiffPhase = DiffPhase.TABLES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Columns ─────────────────────────────────────────────────────

    data class AddColumn(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val column: ColumnDefinition,
        override val phase: DiffPhase = DiffPhase.COLUMNS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC_WITH_DATA_RISK,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk.SAFE,
            down = OperationRisk(destructive = true, dataLossPossible = true, requiresManualConfirmation = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropColumn(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val column: ColumnDefinition,
        override val phase: DiffPhase = DiffPhase.COLUMNS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.NOT_REVERSIBLE,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true, dataLossPossible = true, requiresManualConfirmation = true),
            down = null,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class AlterColumnType(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: NeutralType,
        val after: NeutralType,
        override val phase: DiffPhase = DiffPhase.COLUMNS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC_WITH_DATA_RISK,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(dataLossPossible = true),
            down = OperationRisk(dataLossPossible = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class AlterColumnNullability(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: Boolean,
        val after: Boolean,
        override val phase: DiffPhase = DiffPhase.COLUMNS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = if (after) OperationRisk(requiresManualConfirmation = true) else OperationRisk.SAFE,
            down = if (before) OperationRisk(requiresManualConfirmation = true) else OperationRisk.SAFE,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class AlterColumnDefault(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: DefaultValue?,
        val after: DefaultValue?,
        override val phase: DiffPhase = DiffPhase.COLUMNS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    /**
     * Plan-2 §F.4 second slice: collapses a `(DropColumn, AddColumn)`
     * pair within the same `tablesChanged` entry into a native column
     * rename when an active `RenameMappingOverlayEntry` binds the
     * names and the [ColumnDefinition]s are structurally identical
     * (compared via [CanonicalPayload]). `objectRef.path` is
     * `[tableName, toName]`.
     */
    data class RenameColumn(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val fromName: String,
        val toName: String,
        val overlaySource: String,
        /** T6 entry-provenance: see [RenameTable.overlayEntryId]. */
        val overlayEntryId: String,
        val overlayHash: String?,
        override val phase: DiffPhase = DiffPhase.COLUMNS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Primary keys ────────────────────────────────────────────────

    data class AddPrimaryKey(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val columns: List<String>,
        override val phase: DiffPhase = DiffPhase.CONSTRAINTS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropPrimaryKey(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val columns: List<String>,
        override val phase: DiffPhase = DiffPhase.CONSTRAINTS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Constraints ─────────────────────────────────────────────────

    data class AddConstraint(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val constraint: ConstraintDefinition,
        override val phase: DiffPhase = DiffPhase.CONSTRAINTS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
        /**
         * F.5 Sub-Slice F: when a constraint change is mapped as
         * `DropConstraint(before) + AddConstraint(after)`, both ops
         * share a deterministic [replacePairId] derived from
         * `table | name | canonicalBefore | canonicalAfter`. Op ids
         * remain unique (dependency-sort, artefact binding,
         * `renderedStatements.operationIds` depend on that); the pair
         * id is a separate group identity for reporters and the
         * `ConstraintReplaceContract` post-pass.
         */
        val replacePairId: String? = null,
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropConstraint(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val constraint: ConstraintDefinition,
        override val phase: DiffPhase = DiffPhase.CONSTRAINTS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
        /** See [AddConstraint.replacePairId]. */
        val replacePairId: String? = null,
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Indices ─────────────────────────────────────────────────────

    data class AddIndex(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val index: IndexDefinition,
        override val phase: DiffPhase = DiffPhase.INDEXES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropIndex(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val index: IndexDefinition,
        override val phase: DiffPhase = DiffPhase.INDEXES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Custom types ────────────────────────────────────────────────

    data class CreateCustomType(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val customType: CustomTypeDefinition,
        override val phase: DiffPhase = DiffPhase.TYPES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk.SAFE,
            down = OperationRisk(destructive = true, requiresManualConfirmation = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class AlterCustomType(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: CustomTypeDefinition,
        val after: CustomTypeDefinition,
        override val phase: DiffPhase = DiffPhase.TYPES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.MANUAL_REQUIRED,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(requiresManualConfirmation = true),
            down = null,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropCustomType(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val customType: CustomTypeDefinition,
        override val phase: DiffPhase = DiffPhase.TYPES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.NOT_REVERSIBLE,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true, requiresManualConfirmation = true),
            down = null,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Sequences ───────────────────────────────────────────────────

    data class CreateSequence(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val sequence: SequenceDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.SEQUENCES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk.SAFE,
            down = OperationRisk(destructive = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class AlterSequence(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: SequenceDefinition,
        val after: SequenceDefinition,
        override val phase: DiffPhase = DiffPhase.SEQUENCES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropSequence(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val sequence: SequenceDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.SEQUENCES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC_WITH_DATA_RISK,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true, requiresManualConfirmation = true),
            down = OperationRisk.SAFE,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Views ───────────────────────────────────────────────────────

    data class CreateView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val view: ViewDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.VIEWS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk.SAFE,
            down = OperationRisk(destructive = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class ReplaceView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: ViewDefinition,
        val after: ViewDefinition,
        override val phase: DiffPhase = DiffPhase.VIEWS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val view: ViewDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.VIEWS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true),
            down = OperationRisk.SAFE,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Materialized views (Plan-2 §8 D.3b Sub-Slices A/B) ──────────

    data class CreateMaterializedView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val view: ViewDefinition,
        override val phase: DiffPhase = DiffPhase.VIEWS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk.SAFE,
            down = OperationRisk(destructive = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    /**
     * Plan-2 §8 D.3b Sub-Slice B: body- or columns-diff on a
     * materialized view. The renderer emits two statements (DROP +
     * CREATE) sharing the same [id] so Workstream-G's
     * `executionStatementGroups` treats them as one atomic unit.
     *
     * Body-availability rules (per §6.4.1):
     *
     * - Up needs [after].query to reconstruct the new MV — absent body
     *   blocks with `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`
     *   (BLOCKER severity; the forward DDL genuinely cannot render).
     * - Down needs [before].query to reconstruct the original MV —
     *   absent body blocks with `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN`
     *   (WARNING severity; the forward DDL still runs, only the
     *   rollback contract is affected).
     */
    data class ReplaceMaterializedView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: ViewDefinition,
        val after: ViewDefinition,
        override val phase: DiffPhase = DiffPhase.VIEWS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true),
            down = OperationRisk(destructive = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropMaterializedView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val view: ViewDefinition,
        override val phase: DiffPhase = DiffPhase.VIEWS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true),
            down = OperationRisk.SAFE,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Functions ───────────────────────────────────────────────────

    data class CreateFunction(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val function: FunctionDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.ROUTINES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk.SAFE,
            down = OperationRisk(destructive = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class ReplaceFunction(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: FunctionDefinition,
        val after: FunctionDefinition,
        override val phase: DiffPhase = DiffPhase.ROUTINES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropFunction(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val function: FunctionDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.ROUTINES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true),
            down = OperationRisk.SAFE,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Procedures ──────────────────────────────────────────────────

    data class CreateProcedure(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val procedure: ProcedureDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.ROUTINES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk.SAFE,
            down = OperationRisk(destructive = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class ReplaceProcedure(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: ProcedureDefinition,
        val after: ProcedureDefinition,
        override val phase: DiffPhase = DiffPhase.ROUTINES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropProcedure(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val procedure: ProcedureDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.ROUTINES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true),
            down = OperationRisk.SAFE,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── Triggers ────────────────────────────────────────────────────

    data class CreateTrigger(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val trigger: TriggerDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.TRIGGERS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk.SAFE,
            down = OperationRisk(destructive = true),
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class ReplaceTrigger(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: TriggerDefinition,
        val after: TriggerDefinition,
        override val phase: DiffPhase = DiffPhase.TRIGGERS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    data class DropTrigger(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val trigger: TriggerDefinition,
        val renameProvenance: RenameProvenance? = null,
        override val phase: DiffPhase = DiffPhase.TRIGGERS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(
            up = OperationRisk(destructive = true),
            down = OperationRisk.SAFE,
        ),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    // ── F.4 Sub-Slice A.2: object-level renames ─────────────────────

    /**
     * F.4 view rename. Only valid for regular views — the
     * Mapper/Planner blocks the operation when the underlying
     * `ViewDefinition.materialized = true` (a dedicated
     * materialized-view rename contract is part of D.3b, not F.4).
     */
    data class RenameView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val fromName: String,
        val toName: String,
        val overlaySource: String,
        val overlayEntryId: String,
        val overlayHash: String?,
        override val phase: DiffPhase = DiffPhase.VIEWS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    /**
     * F.4 trigger rename. PostgreSQL renders
     * `ALTER TRIGGER <fromName> ON <tableName> RENAME TO <toName>` —
     * the table context is part of the SQL template, the renderer
     * does not derive it from `objectRef.path` because the canonical
     * trigger key (`table::name`) lives there. [tableName] must be
     * the same on both sides of the rename: cross-table moves are a
     * different object, not a rename.
     *
     * [bodyHash] enables body-drift detection. The Mapper-/Planner-
     * phase only emits a `RenameTrigger` when source and target body
     * hashes are equal; an unequal pair falls back to Drop+Create
     * (with `RenameProvenance`) or blocks with
     * `OBJECT_RENAME_UNSUPPORTED` when the prior body is missing.
     */
    data class RenameTrigger(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val tableName: String,
        val fromName: String,
        val toName: String,
        val bodyHash: String?,
        val overlaySource: String,
        val overlayEntryId: String,
        val overlayHash: String?,
        override val phase: DiffPhase = DiffPhase.TRIGGERS,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    /**
     * F.4 function rename. PostgreSQL renders
     * `ALTER FUNCTION <fromName>(<signature>) RENAME TO <toName>` —
     * the signature is part of the SQL identifier (overloaded
     * routines coexist), so the renderer needs the
     * [ParameterDefinition] list explicitly; `objectRef.path[0]`
     * carries the canonical `ObjectKeyCodec.routineKey(toName,
     * signature)` key for plan/report ID stability.
     *
     * Same-signature constraint: both sides of the rename must share
     * the signature. A signature change is a different routine, not
     * a rename.
     */
    data class RenameFunction(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val fromName: String,
        val toName: String,
        val signature: List<dev.dmigrate.core.model.ParameterDefinition>,
        val bodyHash: String?,
        val overlaySource: String,
        val overlayEntryId: String,
        val overlayHash: String?,
        override val phase: DiffPhase = DiffPhase.ROUTINES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    /**
     * F.4 procedure rename. Mirrors [RenameFunction] — procedures
     * share the same canonical key and rendering shape on
     * PostgreSQL, the only difference is the SQL keyword
     * (`ALTER PROCEDURE` vs. `ALTER FUNCTION`).
     */
    data class RenameProcedure(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val fromName: String,
        val toName: String,
        val signature: List<dev.dmigrate.core.model.ParameterDefinition>,
        val bodyHash: String?,
        val overlaySource: String,
        val overlayEntryId: String,
        val overlayHash: String?,
        override val phase: DiffPhase = DiffPhase.ROUTINES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }

    /**
     * F.4 sequence rename. Declarative-only attributes (no body),
     * so the operation does not carry a body-hash field. The
     * sequence-default-reprojection rules in Sub-Slice D ensure that
     * `SequenceNextVal`-defaults pointing at the renamed sequence
     * are rewritten to the new name before the plan is finalised.
     */
    data class RenameSequence(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val fromName: String,
        val toName: String,
        val overlaySource: String,
        val overlayEntryId: String,
        val overlayHash: String?,
        override val phase: DiffPhase = DiffPhase.SEQUENCES,
        override val dependencies: Set<String> = emptySet(),
        override val reversibility: Reversibility = Reversibility.AUTOMATIC,
        override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
    ) : DiffOperation {
        override fun withDependencies(dependencies: Set<String>): DiffOperation = copy(dependencies = dependencies)
        override fun withId(id: String): DiffOperation = copy(id = id)
    }
}
