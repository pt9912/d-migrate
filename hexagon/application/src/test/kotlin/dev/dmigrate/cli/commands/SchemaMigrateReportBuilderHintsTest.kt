package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffDiagnostic
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
        planDiagnostics: List<DiffDiagnostic> = emptyList(),
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
            diagnostics = planDiagnostics,
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

    test("SQLite cast preflight run failures keep planned declarations machine-readable") {
        val declaration = SqliteCastPreflightDeclaration(
            operationId = "op-cast",
            table = "users",
            column = "age",
            sourceType = "TEXT",
            targetType = "INTEGER",
            status = SqliteCastPreflightStatus.NOT_RUN_POLICY,
            sqlHash = "e".repeat(64),
            problem = "SQLite cast preflight failed before render/execute: boom",
        )
        val rendered = SqliteCastPreflightStage.buildFailureResult(
            message = "boom",
            declarations = listOf(declaration),
        )
        val report = buildReport(rendered = rendered)

        report.status shouldBe "blocked"
        report.sqliteCastPreflights.single().operationId shouldBe "op-cast"
        report.sqliteCastPreflights.single().status shouldBe "NOT_RUN_POLICY"
        report.sqliteCastPreflights.single().problem shouldBe
            "SQLite cast preflight failed before render/execute: boom"
    }

    test("plan-level WARNING diagnostics surface in the merged report diagnostics") {
        val warning = DiffDiagnostic(
            code = "RENAME_OVERLAY_STRUCTURAL_MISMATCH",
            message = "Rename mapping ovl.json entry=e1 for table 'users_old' -> 'users' was ignored.",
            severity = DiffDiagnostic.Severity.WARNING,
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = emptySet(),
            ),
            planDiagnostics = listOf(warning),
        )

        val matching = report.diagnostics.filter { it.code == "RENAME_OVERLAY_STRUCTURAL_MISMATCH" }
        matching.size shouldBe 1
        matching.single().severity shouldBe "WARNING"
        matching.single().message shouldBe warning.message
    }

    test("merge does not duplicate diagnostics already present in renderer output") {
        val shared = DiffDiagnostic(
            code = "CODE_X",
            message = "shared",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "op-1",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = emptySet(),
                diagnostics = listOf(shared),
            ),
            planDiagnostics = listOf(shared),
        )

        report.diagnostics.count { it.code == "CODE_X" } shouldBe 1
    }

    test("ReplaceMaterializedView rendered on PostgreSQL surfaces READY + FRESH_AFTER_REPLACE_REFRESH") {
        val op = DiffOperation.ReplaceMaterializedView(
            id = "mv-replace-1",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("order_summary_mv")),
            before = ViewDefinition(query = "SELECT 1", materialized = true),
            after = ViewDefinition(query = "SELECT 2", materialized = true),
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = setOf("mv-replace-1"),
            ),
            operations = listOf(op),
        )

        report.operations.single().objectType shouldBe "MATERIALIZED_VIEW"
        report.materializedViews.single() shouldBe SchemaMigrateMaterializedViewContractView(
            operationId = "mv-replace-1",
            action = "REPLACE",
            path = listOf("order_summary_mv"),
            dialect = "POSTGRESQL",
            status = "READY",
            stalenessAfterUp = "FRESH_AFTER_REPLACE_REFRESH",
            refreshSteps = listOf("DROP_CREATE_INITIAL_REFRESH"),
            locking = "ACCESS_EXCLUSIVE",
            rollback = "SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED",
            primaryBlockedReason = null,
        )
    }

    test("ReplaceMaterializedView without recoverable before.query → BLOCKED_REPLACE_DOWN_BODY_UNKNOWN") {
        val op = DiffOperation.ReplaceMaterializedView(
            id = "mv-replace-bad",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("order_summary_mv")),
            before = ViewDefinition(query = null, materialized = true),
            after = ViewDefinition(query = "SELECT 2", materialized = true),
        )
        // Severity stays WARNING: the Up DROP+CREATE renders fine — only
        // the Down inverse needs `before.query`. A BLOCKER here would
        // stop the forward DDL.
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_REPLACE_DOWN_BODY_UNKNOWN",
            message = "no before.query",
            severity = DiffDiagnostic.Severity.WARNING,
            operationId = "mv-replace-bad",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = setOf("mv-replace-bad"),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_REPLACE_DOWN_BODY_UNKNOWN"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN"
        contract.refreshSteps shouldBe listOf("BLOCKED_REPLACE_DOWN_BODY_UNKNOWN")
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }

    test("ReplaceMaterializedView Up-blocking missing-after.query → BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED") {
        val op = DiffOperation.ReplaceMaterializedView(
            id = "mv-replace-noafter",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("order_summary_mv")),
            before = ViewDefinition(query = "SELECT 1", materialized = true),
            after = ViewDefinition(query = null, materialized = true),
        )
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
            message = "no after.query",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "mv-replace-noafter",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = setOf("mv-replace-noafter"),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED"
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }

    test("CreateMaterializedView rendered on PostgreSQL surfaces READY contract") {
        val op = DiffOperation.CreateMaterializedView(
            id = "mv-create-1",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = "SELECT 1", materialized = true),
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = setOf("mv-create-1"),
            ),
            operations = listOf(op),
        )

        report.materializedViews.single() shouldBe SchemaMigrateMaterializedViewContractView(
            operationId = "mv-create-1",
            action = "CREATE",
            path = listOf("daily_sales"),
            dialect = "POSTGRESQL",
            status = "READY",
            stalenessAfterUp = "FRESH_AFTER_INITIAL_REFRESH",
            refreshSteps = listOf("INITIAL_REFRESH_VIA_CREATE"),
            locking = "ACCESS_EXCLUSIVE",
            rollback = "DROP_CREATED_MATERIALIZED_VIEW_REFRESH_NOT_REQUIRED",
            primaryBlockedReason = null,
        )
    }

    test("DropMaterializedView rendered on PostgreSQL surfaces READY + SOURCE_QUERY_AVAILABLE rollback") {
        val op = DiffOperation.DropMaterializedView(
            id = "mv-drop-1",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = "SELECT 1", materialized = true),
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = setOf("mv-drop-1"),
            ),
            operations = listOf(op),
        )

        report.materializedViews.single() shouldBe SchemaMigrateMaterializedViewContractView(
            operationId = "mv-drop-1",
            action = "DROP",
            path = listOf("daily_sales"),
            dialect = "POSTGRESQL",
            status = "READY",
            stalenessAfterUp = "NOT_APPLICABLE_DROP",
            refreshSteps = emptyList(),
            locking = "ACCESS_EXCLUSIVE",
            rollback = "SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED",
            primaryBlockedReason = null,
        )
    }

    test("CreateMaterializedView with planner missing-query blocker surfaces dedicated status") {
        val op = DiffOperation.CreateMaterializedView(
            id = "mv-create-bad",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = null, materialized = true),
        )
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
            message = "no query",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "mv-create-bad",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = setOf("mv-create-bad"),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED"
        contract.stalenessAfterUp shouldBe "UNKNOWN_BLOCKED"
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }

    test("DropMaterializedView without recoverable query surfaces BLOCKED_DOWN_QUERY_UNKNOWN") {
        val op = DiffOperation.DropMaterializedView(
            id = "mv-drop-bad",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = null, materialized = true),
        )
        // Severity stays at WARNING: a planner-level BLOCKER would be
        // promoted by the renderer into a `DIALECT_UNSUPPORTED_OPERATION`
        // MigrationBlocker, stopping the forward DROP DDL from executing.
        // The report builder picks up the WARNING-coded code regardless.
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_DOWN_QUERY_UNKNOWN",
            message = "no body",
            severity = DiffDiagnostic.Severity.WARNING,
            operationId = "mv-drop-bad",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = setOf("mv-drop-bad"),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_DOWN_QUERY_UNKNOWN"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN"
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }

    test("View↔MaterializedView conversion surfaces BLOCKED_CONVERSION_UNSUPPORTED + ROLLBACK_NOT_POSSIBLE") {
        // The planner emits a ReplaceView placeholder for a materialized-flag
        // flip plus a BLOCKED_CONVERSION_UNSUPPORTED diagnostic. The report
        // builder turns that into the dedicated contract row so consumers can
        // distinguish a real conversion from a same-type body change.
        val op = DiffOperation.ReplaceView(
            id = "view-conversion",
            objectRef = DiffObjectRef(DiffObjectType.VIEW, listOf("daily_sales")),
            before = ViewDefinition(query = "SELECT 1", materialized = false),
            after = ViewDefinition(query = "SELECT 1", materialized = true),
        )
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_CONVERSION_UNSUPPORTED",
            message = "materialized flag flipped",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "view-conversion",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = setOf("view-conversion"),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_CONVERSION_UNSUPPORTED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_CONVERSION_UNSUPPORTED"
        contract.refreshSteps shouldBe listOf("BLOCKED_CONVERSION_UNSUPPORTED")
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }

    test("BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED surfaces a context-driven OOS contract entry") {
        // Plan §5 Cross-Slice OOS: REFRESH MATERIALIZED VIEW CONCURRENTLY
        // is hard-OOS in D.3b. The trigger is a refresh-contract input
        // outside `ViewDefinition.refresh`; this test pins that the
        // ReportBuilder maps the code to the documented contract row.
        val op = DiffOperation.CreateMaterializedView(
            id = "mv-concurrent",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = "SELECT 1", materialized = true),
        )
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED",
            message = "CONCURRENTLY requested out of scope",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "mv-concurrent",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = setOf("mv-concurrent"),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_CONCURRENT_REFRESH_UNSUPPORTED"
        contract.refreshSteps shouldBe listOf("BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED")
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }

    test("BLOCKED_SCHEMA_REFRESH_UNSUPPORTED surfaces a schema-refresh OOS contract entry") {
        // Plan §2 Aus Scope: `schema refresh materialized-view` is hard-OOS
        // in D.3b. When the orchestrator threads this intent through as a
        // planner diagnostic, the report contract pins the deterministic
        // OOS row so the subcommand surfaces a stable code.
        val op = DiffOperation.CreateMaterializedView(
            id = "mv-schema-refresh",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = "SELECT 1", materialized = true),
        )
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_SCHEMA_REFRESH_UNSUPPORTED",
            message = "schema refresh materialized-view out of scope",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "mv-schema-refresh",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = setOf("mv-schema-refresh"),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_SCHEMA_REFRESH_UNSUPPORTED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_SCHEMA_REFRESH_UNSUPPORTED"
        contract.refreshSteps shouldBe listOf("BLOCKED_SCHEMA_REFRESH_UNSUPPORTED")
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }

    test("BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED surfaces when ViewDefinition.refresh is set") {
        // Plan §2 / §6.4.1: `ViewDefinition.refresh` has no semantic
        // interpretation in D.3b. The mapper emits a WARNING-severity
        // diagnostic so the contract reflects the gap without blocking
        // the Up DDL render.
        val op = DiffOperation.CreateMaterializedView(
            id = "mv-refresh-set",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = "SELECT 1", materialized = true, refresh = "MANUAL"),
        )
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED",
            message = "refresh field set",
            severity = DiffDiagnostic.Severity.WARNING,
            operationId = "mv-refresh-set",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = setOf("mv-refresh-set"),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED"
        contract.primaryBlockedReason shouldBe "VIEW_DEFINITION_REFRESH_SEMANTICS_UNSPECIFIED"
        contract.refreshSteps shouldBe listOf("BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED")
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }

    test("BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED surfaces missing reverse-read metadata") {
        // Plan §2 Aus Scope: live-DB reverse-read of the MV
        // (pre-body, index existence, refreshed-at timestamp) is hard-OOS
        // in D.3b because no `MaterializedViewMetadataQueries` adapter is
        // wired yet. When the adapter surface reports the gap as a
        // diagnostic, the report contract pins the deterministic code.
        val op = DiffOperation.CreateMaterializedView(
            id = "mv-metadata",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = "SELECT 1", materialized = true),
        )
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED",
            message = "live MV metadata adapter missing",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "mv-metadata",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = setOf("mv-metadata"),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_METADATA_UNSUPPORTED"
        contract.refreshSteps shouldBe listOf("BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED")
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }

    test("OOS-Precedence: dialect-block wins over a co-existing concurrent-refresh diagnostic") {
        // §5 Cross-Slice OOS precedence rule 1: BLOCKED_DIALECT_UNSUPPORTED
        // is the highest-priority status — concurrent-refresh and friends
        // never surface for MySQL/SQLite because the op is already
        // un-renderable on the target dialect.
        val op = DiffOperation.CreateMaterializedView(
            id = "mv-mysql-concurrent",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = "SELECT 1", materialized = true),
        )
        val renderDiag = DiffDiagnostic(
            code = "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT",
            message = "MySQL",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "mv-mysql-concurrent",
        )
        val planDiag = DiffDiagnostic(
            code = "BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED",
            message = "CONCURRENTLY requested",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "mv-mysql-concurrent",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = setOf("mv-mysql-concurrent"),
                diagnostics = listOf(renderDiag),
            ),
            operations = listOf(op),
            planDiagnostics = listOf(planDiag),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_DIALECT_UNSUPPORTED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT"
    }

    test("dialect block (MySQL/SQLite) wins over planner-level diagnostics") {
        val op = DiffOperation.CreateMaterializedView(
            id = "mv-mysql",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("daily_sales")),
            view = ViewDefinition(query = "SELECT 1", materialized = true),
        )
        val dialectDiag = DiffDiagnostic(
            code = "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT",
            message = "no MV on MySQL",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "mv-mysql",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                operationsSkipped = setOf("mv-mysql"),
                diagnostics = listOf(dialectDiag),
            ),
            operations = listOf(op),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_DIALECT_UNSUPPORTED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT"
        contract.refreshSteps shouldBe listOf("BLOCKED_DIALECT_UNSUPPORTED")
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
    }
})
