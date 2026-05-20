package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
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

    test("DropSequence + MISSING → proceeds (idempotent)") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val plan = planner.plan(schemaOf(mapOf("order_seq" to seq)), schemaOf(), diff)
        val dropOpId = plan.operations.filterIsInstance<DiffOperation.DropSequence>().single().id
        val r = gen.generateUp(plan, helperOptionsWith(listOf(canonicityDecl(dropOpId, MysqlSequenceCanonicityStatus.MISSING))))
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
})
