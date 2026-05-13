package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ExtensionAvailabilityStatus
import dev.dmigrate.driver.ExtensionDependencyReport
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.SqliteCastPreflightStatus
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

    fun buildReport(
        rendered: MigrationDdlResult,
        operations: List<DiffOperation> = emptyList(),
    ): SchemaMigrateReport {
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
            operations = operations,
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
        )
    }

    fun build(rendered: MigrationDdlResult): SchemaMigrateSummary = buildReport(rendered).summary

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

    test("extension dependencies aggregate into summary fields") {
        val s = build(
            render(emptyList()).copy(
                spatialProfile = "POSTGIS",
                extensionDependencies = listOf(
                    ExtensionDependencyReport(
                        dialect = "postgresql",
                        extension = "postgis",
                        status = ExtensionAvailabilityStatus.UNKNOWN,
                        operationIds = setOf("op-1"),
                    ),
                    ExtensionDependencyReport(
                        dialect = "postgresql",
                        extension = "uuid-ossp",
                        status = ExtensionAvailabilityStatus.VERIFIED_PRESENT,
                        operationIds = setOf("op-2"),
                    ),
                ),
            ),
        )

        s.spatialProfile shouldBe "POSTGIS"
        s.requiredExtensions shouldBe listOf("postgis", "uuid-ossp")
        s.verifiedExtensions shouldBe listOf("uuid-ossp")
        s.missingExtensions shouldBe listOf("postgis")
        s.extensionInstallStatements shouldBe emptyList()
    }

    test("SQLite cast preflights are exposed as machine-readable report items") {
        val report = buildReport(
            rendered = render(emptyList()).copy(
                sqliteCastPreflights = listOf(
                    SqliteCastPreflightDeclaration(
                        operationId = "op-cast",
                        table = "users",
                        column = "age",
                        sourceType = "TEXT",
                        targetType = "INTEGER",
                        status = SqliteCastPreflightStatus.FAILED,
                        sqlHash = "d".repeat(64),
                        totalRows = 10,
                        failingRows = 2,
                        sampleRowIds = listOf("7", "9"),
                        problem = "not safely convertible",
                    ),
                ),
            ),
        )

        report.sqliteCastPreflights.single() shouldBe SchemaMigrateSqliteCastPreflightView(
            operationId = "op-cast",
            dialect = "sqlite",
            table = "users",
            column = "age",
            sourceType = "TEXT",
            targetType = "INTEGER",
            status = "FAILED",
            sqlHash = "d".repeat(64),
            totalRows = 10,
            failingRows = 2,
            sampleRowIds = listOf("7", "9"),
            problem = "not safely convertible",
        )
    }

    test("materialized view operations surface the refresh staleness contract") {
        val op = DiffOperation.ReplaceView(
            id = "view-1",
            objectRef = DiffObjectRef(DiffObjectType.VIEW, listOf("order_summary_mv")),
            before = ViewDefinition(query = "SELECT 1", materialized = true),
            after = ViewDefinition(query = "SELECT 2", materialized = true),
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = setOf("view-1"),
            ),
            operations = listOf(op),
        )

        report.operations.single().objectType shouldBe "MATERIALIZED_VIEW"
        report.materializedViews.single() shouldBe SchemaMigrateMaterializedViewContractView(
            operationId = "view-1",
            action = "REPLACE",
            path = listOf("order_summary_mv"),
            dialect = "POSTGRESQL",
            status = "BLOCKED_UNTIL_REFRESH_STALENESS_CONTRACT",
            stalenessAfterUp = "UNKNOWN_BLOCKED",
            refreshSteps = listOf("BLOCKED_REFRESH_CONTRACT_REQUIRED"),
            locking = "UNKNOWN_REQUIRES_MANUAL_CONTRACT",
            rollback = "SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED",
        )
    }
})
