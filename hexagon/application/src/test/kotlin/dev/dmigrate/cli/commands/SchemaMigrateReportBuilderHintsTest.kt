package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionBehavior
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Plan-2 §A.1: SchemaMigrateReportBuilder aggregates per-statement
 * [DialectExecutionHints] into plan-level summary flags
 * (`planHasImplicitCommitDdl`, `planFullyRollbackable`,
 * `planRequiresExclusiveAccess`). The renderer-side hint plumbing is
 * pinned per-dialect in `*DiffDdlGeneratorTest`; this test focuses on
 * the aggregation logic.
 */
class SchemaMigrateReportBuilderHintsTest : FunSpec({

    fun stmt(opId: String, hints: DialectExecutionHints) = MigrationDdlStatement(
        sql = "-- $opId",
        operationIds = setOf(opId),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
        hints = hints,
    )

    fun render(stmts: List<MigrationDdlStatement>): MigrationDdlResult {
        val opIds = stmts.flatMap { it.operationIds }.toSet()
        return MigrationDdlResult(
            statements = stmts,
            operationsRendered = opIds,
        )
    }

    fun build(rendered: MigrationDdlResult): SchemaMigrateSummary {
        val schema = SchemaDefinition(name = "App", version = "1")
        val operand = ResolvedSchemaOperand(
            reference = "file:test.yaml",
            schema = schema,
            validation = ValidationResult(),
        )
        val plan = DiffResult(
            current = DiffEndpoint(schemaName = "App"),
            desired = DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = emptyList(),
        )
        val request = SchemaMigrateRequest(source = operand.reference, target = operand.reference)
        return SchemaMigrateReportBuilder.build(
            request = request,
            source = operand,
            target = operand,
            plan = plan,
            rendered = rendered,
            dialect = DatabaseDialect.POSTGRESQL,
            renderedDown = null,
        ).summary
    }

    val pg = DialectExecutionHints(
        transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
        lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
        requiresExclusiveAccess = true,
    )
    val mysql = DialectExecutionHints(
        transactionBehavior = TransactionBehavior.IMPLICIT_COMMIT,
        lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
        implicitCommitPossible = true,
        sideEffectsPossible = true,
        requiresExclusiveAccess = true,
    )
    val sqliteOutsideTx = DialectExecutionHints(
        transactionBehavior = TransactionBehavior.NOT_TRANSACTIONAL,
        lockBehavior = LockBehavior.NONE,
        sideEffectsPossible = true,
    )

    test("empty plan is trivially rollbackable and has no implicit-commit DDL") {
        val s = build(render(emptyList()))
        s.planHasImplicitCommitDdl shouldBe false
        s.planFullyRollbackable shouldBe true
        s.planRequiresExclusiveAccess shouldBe false
    }

    test("all-PostgreSQL plan aggregates to fully-rollbackable + exclusive access + no implicit commit") {
        val s = build(render(listOf(stmt("op-1", pg), stmt("op-2", pg))))
        s.planHasImplicitCommitDdl shouldBe false
        s.planFullyRollbackable shouldBe true
        s.planRequiresExclusiveAccess shouldBe true
    }

    test("any MySQL statement flips planHasImplicitCommitDdl and clears planFullyRollbackable") {
        val s = build(render(listOf(stmt("op-1", pg), stmt("op-2", mysql))))
        s.planHasImplicitCommitDdl shouldBe true
        s.planFullyRollbackable shouldBe false
        s.planRequiresExclusiveAccess shouldBe true
    }

    test("NOT_TRANSACTIONAL statement (SQLite outside-tx PRAGMA) clears planFullyRollbackable") {
        val s = build(render(listOf(stmt("op-1", pg), stmt("op-2", sqliteOutsideTx))))
        s.planHasImplicitCommitDdl shouldBe false
        s.planFullyRollbackable shouldBe false
        // sqliteOutsideTx doesn't require exclusive access, but pg does → true.
        s.planRequiresExclusiveAccess shouldBe true
    }

    test("UNKNOWN hints (no renderer claim) clear planFullyRollbackable") {
        val s = build(render(listOf(stmt("op-1", DialectExecutionHints.UNKNOWN))))
        s.planHasImplicitCommitDdl shouldBe false
        s.planFullyRollbackable shouldBe false
        s.planRequiresExclusiveAccess shouldBe false
    }
})
