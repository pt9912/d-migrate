package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceCanonicityDeclaration
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice C: pins the
 * application-layer stage's skip / succeed / fail routing.
 *
 * `mysql-sequenz-kanonizitaet-hinter-einen-port.md`: the stage no
 * longer walks the plan itself — it consumes the already-planned
 * [MigrationPreflightPlan.mysqlSequenceCanonicity] declarations
 * (built by `driver-mysql`'s `MysqlSequenceCanonicityPlanner`,
 * exercised in its own test there). This file pins only the glue:
 * skip conditions, and that a probe exception `copy`s the supplied
 * declarations to `PROBE_RUNTIME_ERROR` without re-deriving them.
 */
class MysqlSequenceCanonicityStageTest : FunSpec({

    val planner = DiffPlanner()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planWithSequenceAdd() = planner.plan(
        emptySchema(),
        emptySchema(),
        SchemaDiff(
            sequencesAdded = listOf(
                NamedSequence("order_seq", SequenceDefinition(start = 1, increment = 1)),
            ),
        ),
    )

    fun planWithoutSequenceOps() = planner.plan(
        emptySchema(),
        emptySchema(),
        SchemaDiff(),
    )

    fun requestExecuteDb(dialect: String) =
        SchemaMigrateRequest(source = "desired.yaml", target = "db:$dialect", execute = true)

    fun requestFile() =
        SchemaMigrateRequest(source = "desired.yaml", target = "file:current.yaml")

    fun dbTarget(dialect: String) = CompareOperand.Database(dialect)
    fun fileTarget() = CompareOperand.File(Path.of("current.yaml"))

    fun notRunRow(operationId: String, objectName: String) = MysqlSequenceCanonicityDeclaration(
        operationId = operationId,
        dialect = "mysql",
        kind = MysqlSequenceCanonicityKind.SEQUENCE_ROW,
        objectName = objectName,
        status = MysqlSequenceCanonicityStatus.NOT_RUN_POLICY,
        sqlHash = "not-run",
    )

    fun preflightPlanWith(vararg declarations: MysqlSequenceCanonicityDeclaration) =
        MigrationPreflightPlan(mysqlSequenceCanonicity = declarations.toList())

    // ── Skip paths return NotRun ────────────────────────────────

    test("file target → NotRun") {
        MysqlSequenceCanonicityStage.run(
            probe = { _, _, _ -> emptyList() },
            request = requestFile(),
            target = fileTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = planWithSequenceAdd(),
            preflightPlan = MigrationPreflightPlan.EMPTY,
        ) shouldBe MysqlSequenceCanonicityStage.Outcome.NotRun
    }

    test("execute against non-MySQL dialect → NotRun (probe is MySQL-only)") {
        for (dialect in listOf(DatabaseDialect.POSTGRESQL, DatabaseDialect.SQLITE)) {
            MysqlSequenceCanonicityStage.run(
                probe = { _, _, _ -> emptyList() },
                request = requestExecuteDb(dialect.name.lowercase()),
                target = dbTarget(dialect.name.lowercase()),
                dialect = dialect,
                plan = planWithSequenceAdd(),
                preflightPlan = MigrationPreflightPlan.EMPTY,
            ) shouldBe MysqlSequenceCanonicityStage.Outcome.NotRun
        }
    }

    test("execute against MySQL without probe wired → NotRun") {
        MysqlSequenceCanonicityStage.run(
            probe = null,
            request = requestExecuteDb("mysql"),
            target = dbTarget("mysql"),
            dialect = DatabaseDialect.MYSQL,
            plan = planWithSequenceAdd(),
            preflightPlan = preflightPlanWith(notRunRow("op-1", "order_seq")),
        ) shouldBe MysqlSequenceCanonicityStage.Outcome.NotRun
    }

    test("execute against MySQL with an empty preflight plan → NotRun (nothing to probe)") {
        MysqlSequenceCanonicityStage.run(
            probe = { _, _, _ -> emptyList() },
            request = requestExecuteDb("mysql"),
            target = dbTarget("mysql"),
            dialect = DatabaseDialect.MYSQL,
            plan = planWithoutSequenceOps(),
            preflightPlan = MigrationPreflightPlan.EMPTY,
        ) shouldBe MysqlSequenceCanonicityStage.Outcome.NotRun
    }

    // ── Column-default-only path (no Sequence-Op in plan) ──────

    val sequenceForDefault = SequenceDefinition(start = 1L, increment = 1L)
    val currentSchemaWithSeq = SchemaDefinition(
        name = "App", version = "1",
        sequences = mapOf("order_seq" to sequenceForDefault),
        tables = mapOf("orders" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            ),
            primaryKey = listOf("id"),
        )),
    )
    val desiredSchemaWithSeqDefault = SchemaDefinition(
        name = "App", version = "1",
        sequences = mapOf("order_seq" to sequenceForDefault),
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
    fun planWithColumnDefaultOnly() = planner.plan(
        currentSchemaWithSeq,
        desiredSchemaWithSeqDefault,
        SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "orders",
                    columnsAdded = mapOf(
                        "number" to ColumnDefinition(
                            type = NeutralType.BigInteger,
                            default = DefaultValue.SequenceNextVal("order_seq"),
                        ),
                    ),
                ),
            ),
        ),
    )

    test("AddColumn with SequenceNextVal default → probe runs (Trigger-Drift-Check requirement)") {
        // Plan-Doc §1.4 / §3.1 Trigger-Body-Drift: a pure column-
        // default migration still renders a DROP + CREATE TRIGGER
        // for the column-bound support trigger. The stage must
        // run the probe even without an explicit Sequence-Op so
        // the gate can block on an operator-modified trigger.
        val plan = planWithColumnDefaultOnly()
        val addColumnOp = plan.operations.filterIsInstance<DiffOperation.AddColumn>().single()
        val canned = MysqlSequenceCanonicityDeclaration(
            operationId = "op-x",
            dialect = "mysql",
            kind = MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
            objectName = "dmg_seq_orders_number_xyz_bi",
            status = MysqlSequenceCanonicityStatus.CANONICAL,
            sqlHash = "h",
        )
        val outcome = MysqlSequenceCanonicityStage.run(
            probe = { _, _, _ -> listOf(canned) },
            request = requestExecuteDb("mysql"),
            target = dbTarget("mysql"),
            dialect = DatabaseDialect.MYSQL,
            plan = plan,
            preflightPlan = preflightPlanWith(
                MysqlSequenceCanonicityDeclaration(
                    operationId = addColumnOp.id,
                    dialect = "mysql",
                    kind = MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
                    objectName = "dmg_seq_orders_number_xyz_bi",
                    status = MysqlSequenceCanonicityStatus.NOT_RUN_POLICY,
                    sqlHash = "not-run",
                ),
            ),
        )
        outcome shouldBe MysqlSequenceCanonicityStage.Outcome.Succeeded(listOf(canned))
    }

    test("AddColumn probe exception → Failed, copies the planned SUPPORT_TRIGGER declaration to PROBE_RUNTIME_ERROR") {
        val plan = planWithColumnDefaultOnly()
        val addColumnOp = plan.operations.filterIsInstance<DiffOperation.AddColumn>().single()
        val planned = MysqlSequenceCanonicityDeclaration(
            operationId = addColumnOp.id,
            dialect = "mysql",
            kind = MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
            objectName = "dmg_seq_orders_number_deadbeef00_bi",
            status = MysqlSequenceCanonicityStatus.NOT_RUN_POLICY,
            sqlHash = "not-run",
        )
        val outcome = MysqlSequenceCanonicityStage.run(
            probe = { _, _, _ -> error("permission denied") },
            request = requestExecuteDb("mysql"),
            target = dbTarget("mysql"),
            dialect = DatabaseDialect.MYSQL,
            plan = plan,
            preflightPlan = preflightPlanWith(planned),
        )
        val failed = outcome as MysqlSequenceCanonicityStage.Outcome.Failed
        failed.message shouldContain "permission denied"
        // Exactly the planned declaration, status flipped, objectName
        // untouched — the stage does not re-derive the trigger name.
        val stamped = failed.declarations.single()
        stamped.operationId shouldBe addColumnOp.id
        stamped.kind shouldBe MysqlSequenceCanonicityKind.SUPPORT_TRIGGER
        stamped.objectName shouldBe "dmg_seq_orders_number_deadbeef00_bi"
        stamped.status shouldBe MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR
        (stamped.problem ?: "") shouldContain "permission denied"
    }

    // ── Happy path ──────────────────────────────────────────────

    test("execute + MySQL + probe + sequence-op plan → Succeeded with probe's declarations") {
        val canned = MysqlSequenceCanonicityDeclaration(
            operationId = "op-1",
            dialect = "mysql",
            kind = MysqlSequenceCanonicityKind.SEQUENCE_ROW,
            objectName = "order_seq",
            status = MysqlSequenceCanonicityStatus.CANONICAL,
            sqlHash = "h",
        )
        val outcome = MysqlSequenceCanonicityStage.run(
            probe = { _, _, _ -> listOf(canned) },
            request = requestExecuteDb("mysql"),
            target = dbTarget("mysql"),
            dialect = DatabaseDialect.MYSQL,
            plan = planWithSequenceAdd(),
            preflightPlan = preflightPlanWith(notRunRow("op-1", "order_seq")),
        )
        outcome shouldBe MysqlSequenceCanonicityStage.Outcome.Succeeded(listOf(canned))
    }

    // ── Exception path: copy every planned declaration as PROBE_RUNTIME_ERROR

    test("probe throws → Failed; copies one PROBE_RUNTIME_ERROR declaration per planned op with the underlying message") {
        val plan = planWithSequenceAdd()
        val outcome = MysqlSequenceCanonicityStage.run(
            probe = { _, _, _ -> error("permission denied for INFORMATION_SCHEMA.COLUMNS") },
            request = requestExecuteDb("mysql"),
            target = dbTarget("mysql"),
            dialect = DatabaseDialect.MYSQL,
            plan = plan,
            preflightPlan = preflightPlanWith(notRunRow("op-1", "order_seq")),
        )
        val failed = outcome as MysqlSequenceCanonicityStage.Outcome.Failed
        failed.message shouldContain "permission denied"
        failed.declarations shouldHaveSize 1
        val decl = failed.declarations.single()
        decl.status shouldBe MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR
        decl.objectName shouldBe "order_seq"
        (decl.problem ?: "") shouldContain "permission denied"
    }

    // ── Failure header for the renderer / runner ────────────────

    test("buildFailureResult produces a MANUAL_ACTION_REQUIRED MigrationDdlResult with MYSQL_SEQUENCE_DRIFT_RUN_FAILED diagnostic") {
        val r = MysqlSequenceCanonicityStage.buildFailureResult(
            message = "connection refused",
            declarations = listOf(
                MysqlSequenceCanonicityDeclaration(
                    operationId = "op-1",
                    dialect = "mysql",
                    kind = MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                    objectName = "order_seq",
                    status = MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR,
                    sqlHash = "stage-failure",
                    problem = "connection refused",
                ),
            ),
        )
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "MYSQL_SEQUENCE_DRIFT_RUN_FAILED" }
            .message shouldContain "connection refused"
        r.mysqlSequenceCanonicity shouldHaveSize 1
        r.mysqlSequenceCanonicity.single().status shouldBe MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR
    }

    test("buildFailureResult with no declarations is still a valid blocker (e.g. stage failed before any op was identifiable)") {
        val r = MysqlSequenceCanonicityStage.buildFailureResult(message = "no JDBC URL configured")
        r.isBlocked shouldBe true
        r.mysqlSequenceCanonicity.size shouldBe 0
        r.diagnostics.single().code shouldBe "MYSQL_SEQUENCE_DRIFT_RUN_FAILED"
    }
})
