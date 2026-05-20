package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.MysqlSequenceCanonicityDeclaration
import dev.dmigrate.driver.MysqlSequenceCanonicityGate
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice D (2026-05-20): pins the
 * renderer-side drift gate routing — every status × intent
 * combination from [MysqlSequenceCanonicityGate.decide] must land
 * as either a proceeded render, an INFO-tagged proceed, or a blocked
 * skip with the right diagnostic code.
 *
 * Lives in its own file (separate from `MysqlDiffSequenceOpsTest`)
 * so the sequence renderer's base contract and the drift-gate's
 * decision routing can be reviewed independently and Detekt's
 * LargeClass threshold is respected without `@Suppress`.
 */
class MysqlDiffSequenceOpsDriftGateTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()
    fun schemaOf(sequences: Map<String, SequenceDefinition> = emptyMap()) =
        SchemaDefinition(name = "App", version = "1", sequences = sequences)

    fun canonicityDecl(
        operationId: String,
        status: MysqlSequenceCanonicityStatus,
        kind: MysqlSequenceCanonicityKind = MysqlSequenceCanonicityKind.SEQUENCE_ROW,
        objectName: String = "order_seq",
        driftField: String? = null,
        expected: String? = null,
        actual: String? = null,
        problem: String? = null,
    ) = MysqlSequenceCanonicityDeclaration(
        operationId = operationId,
        dialect = "mysql",
        kind = kind,
        objectName = objectName,
        status = status,
        sqlHash = "h",
        driftField = driftField,
        expected = expected,
        actual = actual,
        problem = problem,
    )

    fun helperOptionsWith(declarations: List<MysqlSequenceCanonicityDeclaration>) = DdlGenerationOptions(
        mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE,
        mysqlSequenceCanonicity = declarations,
    )

    test("CreateSequence + CANONICAL declaration → proceeds (idempotent re-run)") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(), schemaOf(mapOf("order_seq" to seq)), diff)
        val createOpId = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single().id
        val r = gen.generateUp(plan, helperOptionsWith(listOf(canonicityDecl(createOpId, MysqlSequenceCanonicityStatus.CANONICAL))))
        r.isBlocked shouldBe false
        r.statements.any { it.sql.contains("INSERT INTO `dmg_sequences`") } shouldBe true
    }

    test("CreateSequence + DRIFT (row) → blocked with E124_MYSQL_SEQUENCE_DRIFT_ROW") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(), schemaOf(mapOf("order_seq" to seq)), diff)
        val createOpId = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single().id
        val r = gen.generateUp(
            plan,
            helperOptionsWith(listOf(
                canonicityDecl(
                    createOpId,
                    MysqlSequenceCanonicityStatus.DRIFT,
                    kind = MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                    driftField = "increment_by",
                    expected = "1",
                    actual = "5",
                ),
            )),
        )
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == MysqlSequenceCanonicityGate.DRIFT_ROW_CODE } shouldBe true
        r.diagnostics.single { it.code == MysqlSequenceCanonicityGate.DRIFT_ROW_CODE }
            .message shouldContainStr "increment_by"
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
        // No INSERT must have been emitted for the blocked op.
        r.statements.none {
            it.sql.contains("INSERT INTO `dmg_sequences`") && it.operationIds.contains(createOpId)
        } shouldBe true
    }

    test("CreateSequence + MISSING → proceeds (bootstrap will create)") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(), schemaOf(mapOf("order_seq" to seq)), diff)
        val createOpId = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single().id
        val r = gen.generateUp(plan, helperOptionsWith(listOf(canonicityDecl(createOpId, MysqlSequenceCanonicityStatus.MISSING))))
        r.isBlocked shouldBe false
        r.statements.any { it.sql.contains("INSERT INTO `dmg_sequences`") } shouldBe true
    }

    test("AlterSequence + MISSING → blocked with MISSING_FOR_ALTER (ALTER cannot proceed against absent row)") {
        val before = SequenceDefinition(start = 1L, increment = 1L)
        val after = SequenceDefinition(start = 1L, increment = 2L)
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(
                    name = "order_seq",
                    increment = ValueChange(before.increment, after.increment),
                ),
            ),
        )
        val plan = planner.plan(
            schemaOf(mapOf("order_seq" to before)),
            schemaOf(mapOf("order_seq" to after)),
            diff,
        )
        val alterOpId = plan.operations.filterIsInstance<DiffOperation.AlterSequence>().single().id
        val r = gen.generateUp(plan, helperOptionsWith(listOf(canonicityDecl(alterOpId, MysqlSequenceCanonicityStatus.MISSING))))
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == MysqlSequenceCanonicityGate.MISSING_FOR_ALTER_CODE } shouldBe true
        // No UPDATE was emitted for the blocked op.
        r.statements.none {
            it.sql.startsWith("UPDATE `dmg_sequences`") && it.operationIds.contains(alterOpId)
        } shouldBe true
    }

    test("DropSequence + MISSING (SEQUENCE_ROW) → blocked with MISSING_FOR_DROP") {
        // Plan-Doc §3.1: "Missing → bei UPDATE/DELETE-Path: Block".
        // A DropSequence whose target row is already gone must
        // surface as a blocker so the operator either removes the
        // op from the plan or restores the row before re-running.
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(mapOf("order_seq" to seq)), schemaOf(), diff)
        val dropOpId = plan.operations.filterIsInstance<DiffOperation.DropSequence>().single().id
        val r = gen.generateUp(plan, helperOptionsWith(listOf(canonicityDecl(dropOpId, MysqlSequenceCanonicityStatus.MISSING))))
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == MysqlSequenceCanonicityGate.MISSING_FOR_DROP_CODE } shouldBe true
        // No DELETE statement was emitted for the blocked op.
        r.statements.none {
            it.sql.contains("DELETE FROM `dmg_sequences`") && it.operationIds.contains(dropOpId)
        } shouldBe true
    }

    test("DropSequence + MISSING (NEXTVAL_ROUTINE) → proceeds (collateral state, DELETE does not need the routine)") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(mapOf("order_seq" to seq)), schemaOf(), diff)
        val dropOpId = plan.operations.filterIsInstance<DiffOperation.DropSequence>().single().id
        val r = gen.generateUp(
            plan,
            helperOptionsWith(listOf(canonicityDecl(
                dropOpId,
                MysqlSequenceCanonicityStatus.MISSING,
                kind = MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE,
            ))),
        )
        r.isBlocked shouldBe false
        r.statements.any { it.sql.contains("DELETE FROM `dmg_sequences`") } shouldBe true
    }

    test("DropSequence + DRIFT → blocked (operator must confirm divergence before dropping)") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(mapOf("order_seq" to seq)), schemaOf(), diff)
        val dropOpId = plan.operations.filterIsInstance<DiffOperation.DropSequence>().single().id
        val r = gen.generateUp(
            plan,
            helperOptionsWith(listOf(canonicityDecl(
                dropOpId,
                MysqlSequenceCanonicityStatus.DRIFT,
                driftField = "max_value",
                expected = "999",
                actual = "5000",
            ))),
        )
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == MysqlSequenceCanonicityGate.DRIFT_ROW_CODE } shouldBe true
    }

    test("PROBE_RUNTIME_ERROR → blocked with PROBE_FAILED code regardless of intent") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(), schemaOf(mapOf("order_seq" to seq)), diff)
        val createOpId = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single().id
        val r = gen.generateUp(
            plan,
            helperOptionsWith(listOf(canonicityDecl(
                createOpId,
                MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR,
                problem = "Access denied for user 'foo'@'%'",
            ))),
        )
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == MysqlSequenceCanonicityGate.PROBE_RUNTIME_ERROR_CODE } shouldBe true
    }

    test("NOT_RUN_FILE_TARGET → proceeds with INFO diagnostic (no blocker)") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(), schemaOf(mapOf("order_seq" to seq)), diff)
        val createOpId = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single().id
        val r = gen.generateUp(
            plan,
            helperOptionsWith(listOf(canonicityDecl(createOpId, MysqlSequenceCanonicityStatus.NOT_RUN_FILE_TARGET))),
        )
        r.isBlocked shouldBe false
        r.statements.any { it.sql.contains("INSERT INTO `dmg_sequences`") } shouldBe true
        r.diagnostics.single { it.code == MysqlSequenceCanonicityGate.NOT_RUN_FILE_TARGET_CODE }
            .severity shouldBe dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.INFO
    }

    test("declarations for other operation IDs are ignored (per-op scoping)") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(), schemaOf(mapOf("order_seq" to seq)), diff)
        val r = gen.generateUp(
            plan,
            helperOptionsWith(listOf(canonicityDecl(
                operationId = "some-other-op",
                status = MysqlSequenceCanonicityStatus.DRIFT,
                driftField = "increment_by",
            ))),
        )
        // Drift declaration scoped to a different op-id must not
        // affect rendering for the CreateSequence op.
        r.isBlocked shouldBe false
        r.statements.any { it.sql.contains("INSERT INTO `dmg_sequences`") } shouldBe true
    }

    test("first Block wins: subsequent declarations are not consulted") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(), schemaOf(mapOf("order_seq" to seq)), diff)
        val createOpId = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single().id
        val r = gen.generateUp(
            plan,
            helperOptionsWith(listOf(
                // First: DRIFT on routine → Block with DRIFT_ROUTINE_CODE
                canonicityDecl(
                    createOpId,
                    MysqlSequenceCanonicityStatus.DRIFT,
                    kind = MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE,
                    driftField = "body_marker",
                ),
                // Second: PROBE_RUNTIME_ERROR — should be ignored
                // because the first Block already short-circuited.
                canonicityDecl(
                    createOpId,
                    MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR,
                    problem = "should not be in diagnostics",
                ),
            )),
        )
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == MysqlSequenceCanonicityGate.DRIFT_ROUTINE_CODE } shouldBe true
        r.diagnostics.none { it.code == MysqlSequenceCanonicityGate.PROBE_RUNTIME_ERROR_CODE } shouldBe true
    }

    // ── Column-default-only path (no Sequence-Op in the plan) ──

    test("AddColumn(SequenceNextVal) + SUPPORT_TRIGGER DRIFT → blocked at emitSupportTriggerForColumn") {
        // Plan-Doc §3.1 Trigger-Body-Drift: the column op's
        // canonical trigger drift must surface as a block even
        // when no Sequence-Op is in the plan. Pre-Sub-Slice-F-
        // follow-up the gate at `emitSupportTriggerForColumn`
        // never saw a declaration here, so an operator-modified
        // trigger would be silently overwritten.
        val seq = SequenceDefinition(start = 1L)
        val currentSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to seq),
            tables = mapOf("orders" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                ),
                primaryKey = listOf("id"),
            )),
        )
        val desiredSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to seq),
            tables = mapOf("orders" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                    "number" to ColumnDefinition(
                        type = NeutralType.BigInteger,
                        default = DefaultValue.SequenceNextVal("order_seq"),
                    ),
                ),
                primaryKey = listOf("id"),
            )),
        )
        val plan = planner.plan(
            currentSchema, desiredSchema,
            SchemaDiff(tablesChanged = listOf(TableDiff(
                name = "orders",
                columnsAdded = mapOf("number" to ColumnDefinition(
                    type = NeutralType.BigInteger,
                    default = DefaultValue.SequenceNextVal("order_seq"),
                )),
            ))),
        )
        val addColumnOp = plan.operations.filterIsInstance<DiffOperation.AddColumn>().single()
        val triggerName = dev.dmigrate.driver.MysqlSequenceSupportNaming.triggerName("orders", "number")
        val r = gen.generateUp(
            plan,
            helperOptionsWith(listOf(MysqlSequenceCanonicityDeclaration(
                operationId = addColumnOp.id,
                dialect = "mysql",
                kind = MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
                objectName = triggerName,
                status = MysqlSequenceCanonicityStatus.DRIFT,
                sqlHash = "h",
                driftField = "body_signature",
            ))),
        )
        r.isBlocked shouldBe true
        // The drift diagnostic is BLOCKER-severity and travels both
        // in the top-level diagnostics list AND attached to the
        // MigrationBlocker — matching the sequence-op-side path so
        // the report semantics line up.
        val driftDiagnostic = r.diagnostics.single { it.code == MysqlSequenceCanonicityGate.DRIFT_TRIGGER_CODE }
        driftDiagnostic.severity shouldBe dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.BLOCKER
        driftDiagnostic.operationId shouldBe addColumnOp.id
        val blocker = r.blockers.single { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED }
        blocker.diagnostics.any {
            it.code == MysqlSequenceCanonicityGate.DRIFT_TRIGGER_CODE
        } shouldBe true
        // No CREATE TRIGGER was emitted for the blocked column.
        r.statements.none {
            it.sql.contains("CREATE TRIGGER") && it.operationIds.contains(addColumnOp.id)
        } shouldBe true
    }
})
