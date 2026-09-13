package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * `mysql-sequenz-kanonizitaet-hinter-einen-port.md`: this is the
 * plan-walk that used to live twice in `hexagon/application`
 * (`MigrationPreflightPlanner.planMysqlSequenceCanonicity` and
 * `MysqlSequenceCanonicityStage.stampStageFailure`), differing only
 * in the [MysqlSequenceCanonicityStatus] they stamped. Both callers
 * now go through [MysqlSequenceCanonicityPlanner.plan]; this file
 * pins the walk itself — which op kinds carry a declaration, and how
 * the SUPPORT_TRIGGER name is derived. Ops are constructed directly
 * (not via `DiffPlanner`) for the same reason
 * `MysqlDiffSequenceOpsTest` does it: precise control over the exact
 * shape under test, no dependence on rename-inference heuristics.
 */
class MysqlSequenceCanonicityPlannerTest : FunSpec({

    fun planOf(vararg ops: DiffOperation): DiffResult = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = ops.toList(),
    )

    val seq = SequenceDefinition(start = 1L)

    test("CreateSequence yields one SEQUENCE_ROW declaration named after the sequence") {
        val op = DiffOperation.CreateSequence(
            id = "op-1",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("order_seq")),
            sequence = seq,
        )
        val declarations = MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.NOT_RUN_POLICY, "not-run",
        )

        declarations shouldHaveSize 1
        val decl = declarations.single()
        decl.operationId shouldBe "op-1"
        decl.kind shouldBe MysqlSequenceCanonicityKind.SEQUENCE_ROW
        decl.objectName shouldBe "order_seq"
        decl.dialect shouldBe "mysql"
        decl.status shouldBe MysqlSequenceCanonicityStatus.NOT_RUN_POLICY
        decl.sqlHash shouldBe "not-run"
    }

    test("AlterSequence yields one SEQUENCE_ROW declaration") {
        val op = DiffOperation.AlterSequence(
            id = "op-2",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("order_seq")),
            before = seq,
            after = seq.copy(increment = 5L),
        )
        val declarations = MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR, "stage-failure",
        )

        declarations shouldHaveSize 1
        declarations.single().kind shouldBe MysqlSequenceCanonicityKind.SEQUENCE_ROW
        declarations.single().objectName shouldBe "order_seq"
        declarations.single().status shouldBe MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR
        declarations.single().sqlHash shouldBe "stage-failure"
    }

    test("DropSequence yields one SEQUENCE_ROW declaration") {
        val op = DiffOperation.DropSequence(
            id = "op-3",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("order_seq")),
            sequence = seq,
        )
        val declarations = MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.NOT_RUN_FILE_TARGET, "not-run",
        )

        declarations shouldHaveSize 1
        declarations.single().kind shouldBe MysqlSequenceCanonicityKind.SEQUENCE_ROW
        declarations.single().objectName shouldBe "order_seq"
    }

    test("RenameSequence yields one SEQUENCE_ROW declaration named after the OLD (from) name") {
        val op = DiffOperation.RenameSequence(
            id = "op-4",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("new_seq")),
            fromName = "old_seq",
            toName = "new_seq",
            overlaySource = "test",
            overlayEntryId = "test#0",
            overlayHash = null,
        )
        val declarations = MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.NOT_RUN_POLICY, "not-run",
        )

        declarations shouldHaveSize 1
        declarations.single().kind shouldBe MysqlSequenceCanonicityKind.SEQUENCE_ROW
        declarations.single().objectName shouldBe "old_seq"
    }

    test("AddColumn with a SequenceNextVal default yields one SUPPORT_TRIGGER declaration with the canonical trigger name") {
        val op = DiffOperation.AddColumn(
            id = "op-5",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "number")),
            column = dev.dmigrate.core.model.ColumnDefinition(
                type = dev.dmigrate.core.model.NeutralType.BigInteger,
                default = DefaultValue.SequenceNextVal("order_seq"),
            ),
        )
        val declarations = MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.NOT_RUN_POLICY, "not-run",
        )

        declarations shouldHaveSize 1
        val decl = declarations.single()
        decl.operationId shouldBe "op-5"
        decl.kind shouldBe MysqlSequenceCanonicityKind.SUPPORT_TRIGGER
        decl.objectName shouldBe MysqlSequenceNaming.triggerName("orders", "number")
    }

    test("AddColumn WITHOUT a SequenceNextVal default yields no declaration") {
        val op = DiffOperation.AddColumn(
            id = "op-6",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "note")),
            column = dev.dmigrate.core.model.ColumnDefinition(type = dev.dmigrate.core.model.NeutralType.Text()),
        )
        MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.NOT_RUN_POLICY, "not-run",
        ).shouldBeEmpty()
    }

    test("AlterColumnDefault to a SequenceNextVal default yields one SUPPORT_TRIGGER declaration") {
        val op = DiffOperation.AlterColumnDefault(
            id = "op-7",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "number")),
            before = null,
            after = DefaultValue.SequenceNextVal("order_seq"),
        )
        val declarations = MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.NOT_RUN_POLICY, "not-run",
        )

        declarations shouldHaveSize 1
        val decl = declarations.single()
        decl.kind shouldBe MysqlSequenceCanonicityKind.SUPPORT_TRIGGER
        decl.objectName shouldBe MysqlSequenceNaming.triggerName("orders", "number")
    }

    test("AlterColumnDefault dropping a default (after = null) yields no declaration") {
        val op = DiffOperation.AlterColumnDefault(
            id = "op-8",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "number")),
            before = DefaultValue.SequenceNextVal("order_seq"),
            after = null,
        )
        MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.NOT_RUN_POLICY, "not-run",
        ).shouldBeEmpty()
    }

    test("an unrelated op (AddConstraint CHECK) yields no declaration") {
        val op = DiffOperation.AddConstraint(
            id = "op-9",
            objectRef = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("orders", "chk_age")),
            constraint = ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = "id >= 0"),
        )
        MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.NOT_RUN_POLICY, "not-run",
        ).shouldBeEmpty()
    }

    test("problem text passes through to every declaration") {
        val op = DiffOperation.CreateSequence(
            id = "op-10",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("order_seq")),
            sequence = seq,
        )
        val declarations = MysqlSequenceCanonicityPlanner.plan(
            planOf(op), MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR, "stage-failure", "connection reset",
        )

        declarations.single().problem shouldBe "connection reset"
    }

    test("multiple qualifying ops each yield their own declaration, in order") {
        val createOp = DiffOperation.CreateSequence(
            id = "op-a",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("seq_a")),
            sequence = seq,
        )
        val addColumnOp = DiffOperation.AddColumn(
            id = "op-b",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "number")),
            column = dev.dmigrate.core.model.ColumnDefinition(
                type = dev.dmigrate.core.model.NeutralType.BigInteger,
                default = DefaultValue.SequenceNextVal("seq_a"),
            ),
        )
        val declarations = MysqlSequenceCanonicityPlanner.plan(
            planOf(createOp, addColumnOp), MysqlSequenceCanonicityStatus.NOT_RUN_POLICY, "not-run",
        )

        declarations shouldHaveSize 2
        declarations[0].operationId shouldBe "op-a"
        declarations[0].kind shouldBe MysqlSequenceCanonicityKind.SEQUENCE_ROW
        declarations[1].operationId shouldBe "op-b"
        declarations[1].kind shouldBe MysqlSequenceCanonicityKind.SUPPORT_TRIGGER
    }

    test("a plan with no matching ops yields no declarations") {
        MysqlSequenceCanonicityPlanner.plan(
            planOf(), MysqlSequenceCanonicityStatus.NOT_RUN_POLICY, "not-run",
        ).shouldBeEmpty()
    }
})
