package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.MaterializedViewDependencyBlocker
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Plan-2 §8 D.3b Sub-Slices A/B/C: end-to-end report-builder pins for
 * the `materializedViews[]` contract entries. Split out of
 * `SchemaMigrateReportBuilderHintsTest` to keep both files under
 * Detekt's `LargeClass` threshold.
 */
class SchemaMigrateMaterializedViewContractBuilderTest : FunSpec({

    fun buildReport(
        rendered: MigrationDdlResult,
        operations: List<DiffOperation> = emptyList(),
        planDiagnostics: List<DiffDiagnostic> = emptyList(),
        mvDependencyBlockers: List<MaterializedViewDependencyBlocker> = emptyList(),
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
            materializedViewDependencyBlockers = mvDependencyBlockers,
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

    test("DropMaterializedView with dependencyBlocker surfaces BLOCKED_DEPENDENCY_UNRESOLVED") {
        // The MV op is in the plan; the planner additionally reports a
        // dependency blocker (e.g. another MV that depends on this one
        // isn't being dropped). The contract row picks up the
        // structured blocker and maps the status accordingly.
        val mvOp = DiffOperation.DropMaterializedView(
            id = "mv-drop-1",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("mv_b")),
            view = ViewDefinition(query = "SELECT 1", materialized = true),
        )
        val blocker = dev.dmigrate.core.diff.migration.MaterializedViewDependencyBlocker(
            materializedViewName = "mv_b",
            materializedViewPath = listOf("mv_b"),
            droppingOperationId = "mv-drop-1",
            droppingObjectType = DiffObjectType.MATERIALIZED_VIEW,
            droppingPath = listOf("mv_b"),
            droppingKind = "MATERIALIZED_VIEW",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = setOf("mv-drop-1"),
            ),
            operations = listOf(mvOp),
            mvDependencyBlockers = listOf(blocker),
        )

        val contract = report.materializedViews.single()
        contract.status shouldBe "BLOCKED_DEPENDENCY_UNRESOLVED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED"
        contract.dependencyBlockers.size shouldBe 1
        contract.dependencyBlockers.single().droppingKind shouldBe "MATERIALIZED_VIEW"
    }

    test("Orphaned MV (no in-plan op) is synthesised into materializedViews[]") {
        // Plan-2 §8 D.3b Sub-Slice C: DropTable orphans an MV that has
        // no op of its own. The report builder synthesises a contract
        // entry from the planner's structured blocker so the operator
        // sees the orphan in `materializedViews[]`.
        val dropTable = DiffOperation.DropTable(
            id = "drop-users",
            objectRef = DiffObjectRef(DiffObjectType.TABLE, listOf("users")),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val blocker = dev.dmigrate.core.diff.migration.MaterializedViewDependencyBlocker(
            materializedViewName = "mv_users",
            materializedViewPath = listOf("mv_users"),
            droppingOperationId = "drop-users",
            droppingObjectType = DiffObjectType.TABLE,
            droppingPath = listOf("users"),
            droppingKind = "TABLE",
        )
        val report = buildReport(
            rendered = MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = setOf("drop-users"),
            ),
            operations = listOf(dropTable),
            mvDependencyBlockers = listOf(blocker),
        )

        val contract = report.materializedViews.single()
        contract.path shouldBe listOf("mv_users")
        contract.action shouldBe "ORPHAN"
        contract.operationId shouldBe "drop-users"
        contract.status shouldBe "BLOCKED_DEPENDENCY_UNRESOLVED"
        contract.primaryBlockedReason shouldBe "MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED"
        contract.refreshSteps shouldBe listOf("BLOCKED_DEPENDENCY_UNRESOLVED")
        contract.rollback shouldBe "ROLLBACK_NOT_POSSIBLE"
        contract.dependencyBlockers.single().droppingKind shouldBe "TABLE"
        contract.dependencyBlockers.single().droppingPath shouldBe listOf("users")
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
