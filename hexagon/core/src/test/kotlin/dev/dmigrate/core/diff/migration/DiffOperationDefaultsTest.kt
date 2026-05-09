package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Pins the default phase / reversibility / risk profile per
 * [DiffOperation] subtype so silent semantic drift fails here. Plan
 * §4.5 / §4.6.
 */
class DiffOperationDefaultsTest : FunSpec({

    val tableRef = DiffObjectRef(DiffObjectType.TABLE, listOf("orders"))
    val columnRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "status"))

    test("CreateTable defaults: phase TABLES, reversible-with-data-risk, safe Up + destructive Down") {
        val op = DiffOperation.CreateTable(
            id = "id",
            objectRef = tableRef,
            table = TableDefinition(),
        )
        op.phase shouldBe DiffPhase.TABLES
        op.reversibility shouldBe Reversibility.AUTOMATIC_WITH_DATA_RISK
        op.risks.up shouldBe OperationRisk.SAFE
        op.risks.down!!.destructive shouldBe true
        op.risks.down!!.dataLossPossible shouldBe true
        op.risks.down!!.requiresManualConfirmation shouldBe true
    }

    test("DropTable defaults: NOT_REVERSIBLE, destructive Up, no Down") {
        val op = DiffOperation.DropTable(
            id = "id",
            objectRef = tableRef,
            table = TableDefinition(),
        )
        op.reversibility shouldBe Reversibility.NOT_REVERSIBLE
        op.risks.up.destructive shouldBe true
        op.risks.down shouldBe null
    }

    test("AddColumn defaults: phase COLUMNS, reversible-with-data-risk, safe Up") {
        val op = DiffOperation.AddColumn(
            id = "id",
            objectRef = columnRef,
            column = ColumnDefinition(NeutralType.Text()),
        )
        op.phase shouldBe DiffPhase.COLUMNS
        op.reversibility shouldBe Reversibility.AUTOMATIC_WITH_DATA_RISK
        op.risks.up shouldBe OperationRisk.SAFE
        op.risks.down!!.destructive shouldBe true
    }

    test("DropColumn defaults: NOT_REVERSIBLE, no Down") {
        val op = DiffOperation.DropColumn(
            id = "id",
            objectRef = columnRef,
            column = ColumnDefinition(NeutralType.Text()),
        )
        op.reversibility shouldBe Reversibility.NOT_REVERSIBLE
        op.risks.up.destructive shouldBe true
        op.risks.down shouldBe null
    }

    test("AlterColumnNullability: nullable→required raises Up confirmation") {
        val op = DiffOperation.AlterColumnNullability(
            id = "id", objectRef = columnRef, before = false, after = true,
        )
        op.risks.up.requiresManualConfirmation shouldBe true
        op.risks.down!!.requiresManualConfirmation shouldBe false
    }

    test("AlterColumnNullability: required→nullable is safe Up, requires confirmation Down") {
        val op = DiffOperation.AlterColumnNullability(
            id = "id", objectRef = columnRef, before = true, after = false,
        )
        op.risks.up.requiresManualConfirmation shouldBe false
        op.risks.down!!.requiresManualConfirmation shouldBe true
    }

    test("AlterColumnDefault: AUTOMATIC + safe Up + safe Down") {
        val op = DiffOperation.AlterColumnDefault(
            id = "id", objectRef = columnRef, before = null, after = null,
        )
        op.reversibility shouldBe Reversibility.AUTOMATIC
        op.risks.up shouldBe OperationRisk.SAFE
        op.risks.down shouldBe OperationRisk.SAFE
    }

    test("AlterColumnType: AUTOMATIC_WITH_DATA_RISK on both sides") {
        val op = DiffOperation.AlterColumnType(
            id = "id",
            objectRef = columnRef,
            before = NeutralType.Text(),
            after = NeutralType.BigInteger,
        )
        op.reversibility shouldBe Reversibility.AUTOMATIC_WITH_DATA_RISK
        op.risks.up.dataLossPossible shouldBe true
        op.risks.down!!.dataLossPossible shouldBe true
    }

    test("AlterCustomType: MANUAL_REQUIRED, no Down") {
        val customType = dev.dmigrate.core.model.CustomTypeDefinition(
            kind = dev.dmigrate.core.model.CustomTypeKind.ENUM,
            values = listOf("a"),
        )
        val op = DiffOperation.AlterCustomType(
            id = "id",
            objectRef = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf("status_t")),
            before = customType,
            after = customType.copy(values = listOf("a", "b")),
        )
        op.reversibility shouldBe Reversibility.MANUAL_REQUIRED
        op.risks.up.requiresManualConfirmation shouldBe true
        op.risks.down shouldBe null
    }

    test("objectType is derived from objectRef.type") {
        val op = DiffOperation.AddColumn(
            id = "id",
            objectRef = columnRef,
            column = ColumnDefinition(NeutralType.Text()),
        )
        op.objectType shouldBe DiffObjectType.COLUMN
    }

    // ── Smoke: instantiate every operation subtype with its phase default ──
    //
    // Pins the full §4.3 catalog so a missing or accidentally-removed
    // subtype fails here instead of much later in the planner.

    test("all operation subtypes can be instantiated with the documented phase") {
        val pkRef = DiffObjectRef(DiffObjectType.PRIMARY_KEY, listOf("orders"))
        val constraintRef = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("orders", "fk_x"))
        val indexRef = DiffObjectRef(DiffObjectType.INDEX, listOf("orders", "idx_x"))
        val customTypeRef = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf("status_t"))
        val sequenceRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("seq_x"))
        val viewRef = DiffObjectRef(DiffObjectType.VIEW, listOf("v_x"))
        val functionRef = DiffObjectRef(DiffObjectType.FUNCTION, listOf("fn_x"))
        val procedureRef = DiffObjectRef(DiffObjectType.PROCEDURE, listOf("sp_x"))
        val triggerRef = DiffObjectRef(DiffObjectType.TRIGGER, listOf("trg_x"))

        val customType = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))
        val constraint = ConstraintDefinition(
            name = "fk_x",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
        )
        val index = IndexDefinition(
            name = "idx_x",
            columns = listOf(IndexColumn("c")),
            type = IndexType.BTREE,
        )
        val sequence = SequenceDefinition(start = 1)
        val view = ViewDefinition(query = "SELECT 1")
        val function = FunctionDefinition()
        val procedure = ProcedureDefinition()
        val trigger = TriggerDefinition(table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER)

        val ops: List<DiffOperation> = listOf(
            DiffOperation.CreateTable("c-tbl", tableRef, TableDefinition()),
            DiffOperation.DropTable("d-tbl", tableRef, TableDefinition()),
            DiffOperation.AddColumn("a-col", columnRef, ColumnDefinition(NeutralType.Text())),
            DiffOperation.DropColumn("d-col", columnRef, ColumnDefinition(NeutralType.Text())),
            DiffOperation.AlterColumnType("at-col", columnRef, NeutralType.Text(), NeutralType.BigInteger),
            DiffOperation.AlterColumnNullability("an-col", columnRef, before = false, after = true),
            DiffOperation.AlterColumnDefault("ad-col", columnRef, before = null, after = null),
            DiffOperation.AddPrimaryKey("a-pk", pkRef, listOf("id")),
            DiffOperation.DropPrimaryKey("d-pk", pkRef, listOf("id")),
            DiffOperation.AddConstraint("a-c", constraintRef, constraint),
            DiffOperation.DropConstraint("d-c", constraintRef, constraint),
            DiffOperation.AddIndex("a-i", indexRef, index),
            DiffOperation.DropIndex("d-i", indexRef, index),
            DiffOperation.CreateCustomType("c-t", customTypeRef, customType),
            DiffOperation.AlterCustomType("at-t", customTypeRef, customType, customType),
            DiffOperation.DropCustomType("d-t", customTypeRef, customType),
            DiffOperation.CreateSequence("c-s", sequenceRef, sequence),
            DiffOperation.AlterSequence("at-s", sequenceRef, sequence, sequence),
            DiffOperation.DropSequence("d-s", sequenceRef, sequence),
            DiffOperation.CreateView("c-v", viewRef, view),
            DiffOperation.ReplaceView("r-v", viewRef, view, view),
            DiffOperation.DropView("d-v", viewRef, view),
            DiffOperation.CreateFunction("c-f", functionRef, function),
            DiffOperation.ReplaceFunction("r-f", functionRef, function, function),
            DiffOperation.DropFunction("d-f", functionRef, function),
            DiffOperation.CreateProcedure("c-p", procedureRef, procedure),
            DiffOperation.ReplaceProcedure("r-p", procedureRef, procedure, procedure),
            DiffOperation.DropProcedure("d-p", procedureRef, procedure),
            DiffOperation.CreateTrigger("c-trg", triggerRef, trigger),
            DiffOperation.ReplaceTrigger("r-trg", triggerRef, trigger, trigger),
            DiffOperation.DropTrigger("d-trg", triggerRef, trigger),
        )
        ops shouldHaveSize 31

        // Phase pinning (Plan §4.4):
        ops.filterIsInstance<DiffOperation.CreateTable>().single().phase shouldBe DiffPhase.TABLES
        ops.filterIsInstance<DiffOperation.AddColumn>().single().phase shouldBe DiffPhase.COLUMNS
        ops.filterIsInstance<DiffOperation.AddIndex>().single().phase shouldBe DiffPhase.INDEXES
        ops.filterIsInstance<DiffOperation.AddConstraint>().single().phase shouldBe DiffPhase.CONSTRAINTS
        ops.filterIsInstance<DiffOperation.AddPrimaryKey>().single().phase shouldBe DiffPhase.CONSTRAINTS
        ops.filterIsInstance<DiffOperation.CreateCustomType>().single().phase shouldBe DiffPhase.TYPES
        ops.filterIsInstance<DiffOperation.CreateSequence>().single().phase shouldBe DiffPhase.SEQUENCES
        ops.filterIsInstance<DiffOperation.CreateView>().single().phase shouldBe DiffPhase.VIEWS
        ops.filterIsInstance<DiffOperation.CreateFunction>().single().phase shouldBe DiffPhase.ROUTINES
        ops.filterIsInstance<DiffOperation.CreateProcedure>().single().phase shouldBe DiffPhase.ROUTINES
        ops.filterIsInstance<DiffOperation.CreateTrigger>().single().phase shouldBe DiffPhase.TRIGGERS

        // Reversibility pinning sample:
        ops.filterIsInstance<DiffOperation.DropTable>().single().reversibility shouldBe Reversibility.NOT_REVERSIBLE
        ops.filterIsInstance<DiffOperation.DropColumn>().single().reversibility shouldBe Reversibility.NOT_REVERSIBLE
        ops.filterIsInstance<DiffOperation.DropCustomType>().single().reversibility shouldBe Reversibility.NOT_REVERSIBLE
        ops.filterIsInstance<DiffOperation.AlterCustomType>().single().reversibility shouldBe Reversibility.MANUAL_REQUIRED

        // withDependencies pinning: every subtype must replace its dependencies set
        // (sealed-interface contract used by DependencyAnalyzer in Phase C).
        val deps = setOf("dep-1", "dep-2")
        for (op in ops) {
            val withDeps = op.withDependencies(deps)
            withDeps.dependencies shouldBe deps
            withDeps.id shouldBe op.id
            withDeps::class shouldBe op::class
        }
    }
})
