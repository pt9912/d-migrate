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
}
