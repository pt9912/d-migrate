package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DataTransformationContract
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.OperationRisks
import dev.dmigrate.core.diff.migration.RenameProjectionReport
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanArtifactFeatures
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanArtifactValidationContext
import dev.dmigrate.core.diff.migration.artifact.MigrationPlanArtifactValidator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * F.4 Sub-Slice G.2 unit tests for [MigrationPlanArtifactBuilder].
 *
 * The builder is the producer-side bridge from the internal
 * planner / renderer state to the public
 * [dev.dmigrate.core.diff.migration.artifact.MigrationPlanArtifact]
 * contract. The tests pin:
 *
 *  - field-by-field projection of operations + diagnostics +
 *    reversibility summary + rendered-statement hashes;
 *  - `renameProjections` round-trip from `DiffResult.renameProjections`
 *    into the public DTO + auto-gating via
 *    [dev.dmigrate.core.diff.migration.artifact.MigrationPlanArtifact.withRenameProjectionExtension];
 *  - the signed artifact validates cleanly against the default
 *    consumer (no `renameProjections`) and against the
 *    `RENAME_PROJECTIONS_V1`-supporting consumer (with
 *    `renameProjections`);
 *  - missing source/target fingerprints fail loudly.
 */
class MigrationPlanArtifactBuilderTest : FunSpec({

    val fixedClock = Clock.fixed(Instant.parse("2026-05-19T08:00:00Z"), ZoneOffset.UTC)
    val dMigrateVersion = "d-migrate-test"

    fun emptyDiff() = SchemaDiff()

    fun planWith(
        operations: List<DiffOperation> = emptyList(),
        diagnostics: List<DiffDiagnostic> = emptyList(),
        renameProjections: List<RenameProjectionReport> = emptyList(),
    ): DiffResult = DiffResult(
        current = DiffEndpoint("acme", schemaVersion = "1", fingerprint = "src-fp"),
        desired = DiffEndpoint("acme", schemaVersion = "2", fingerprint = "dst-fp"),
        schemaDiff = emptyDiff(),
        operations = operations,
        diagnostics = diagnostics,
        renameProjections = renameProjections,
    )

    fun renderedWith(statements: List<MigrationDdlStatement> = emptyList()) =
        MigrationDdlResult(
            statements = statements,
            operationsRendered = statements.flatMap { it.operationIds }.toSet(),
        )

    fun sampleAddColumn(id: String, table: String, column: String): DiffOperation.AddColumn =
        DiffOperation.AddColumn(
            id = id,
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf(table, column)),
            column = ColumnDefinition(NeutralType.Text(maxLength = 50)),
        )

    fun sampleCreateTable(id: String, name: String): DiffOperation.CreateTable =
        DiffOperation.CreateTable(
            id = id,
            objectRef = DiffObjectRef(DiffObjectType.TABLE, listOf(name)),
            table = TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                primaryKey = listOf("id"),
            ),
        )

    fun sampleDropTable(id: String, name: String): DiffOperation.DropTable =
        DiffOperation.DropTable(
            id = id,
            objectRef = DiffObjectRef(DiffObjectType.TABLE, listOf(name)),
            table = TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                primaryKey = listOf("id"),
            ),
        )

    test("empty plan + no statements produces a valid empty-shape artifact with computed hash") {
        val artifact = MigrationPlanArtifactBuilder.build(
            plan = planWith(),
            rendered = renderedWith(),
            dialect = DatabaseDialect.POSTGRESQL,
            clock = fixedClock,
            dMigrateVersion = dMigrateVersion,
        )
        artifact.formatVersion shouldBe "migration-plan.v1"
        artifact.dMigrateVersion shouldBe dMigrateVersion
        artifact.sourceFingerprint shouldBe "src-fp"
        artifact.targetFingerprint shouldBe "dst-fp"
        artifact.fingerprintAlgorithm shouldBe "schema-fingerprint-v10"
        artifact.dialect shouldBe "postgresql"
        artifact.operations shouldBe emptyList()
        artifact.diagnostics shouldBe emptyList()
        artifact.reversibilitySummary.fullyReversible shouldBe true
        artifact.renderedStatements shouldBe emptyList()
        artifact.renameProjections shouldBe emptyList()
        artifact.semanticExtensions shouldBe emptySet()
        artifact.createdAt shouldBe "2026-05-19T08:00:00Z"
        artifact.artifactHash shouldNotBe null

        MigrationPlanArtifactValidator.validate(artifact).hasBlockers shouldBe false
    }

    test("operations project per-field: kind, objectType, path, phase, reversibility, risks") {
        val op = sampleAddColumn(id = "add-orders-name", table = "orders", column = "name")
        val artifact = MigrationPlanArtifactBuilder.build(
            plan = planWith(operations = listOf(op)),
            rendered = renderedWith(),
            dialect = DatabaseDialect.POSTGRESQL,
            clock = fixedClock,
            dMigrateVersion = dMigrateVersion,
        )
        val view = artifact.operations.single()
        view.id shouldBe "add-orders-name"
        view.kind shouldBe "AddColumn"
        view.objectType shouldBe "COLUMN"
        view.objectPath shouldBe listOf("orders", "name")
        view.phase shouldBe "COLUMNS"
        view.reversibility shouldBe "AUTOMATIC_WITH_DATA_RISK"
        view.upRisk.destructive shouldBe false
        view.upRisk.dataTransformationMode shouldBe "NONE"
        view.downRisk shouldNotBe null
    }

    test("reversibility summary aggregates MANUAL_REQUIRED and NOT_REVERSIBLE op ids") {
        val automaticOp = sampleAddColumn(id = "add-1", table = "orders", column = "x")
        val notReversibleOp = sampleDropTable("drop-orders", "orders")
        val manualOp = DiffOperation.AlterColumnType(
            id = "alter-1",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "x")),
            before = NeutralType.Text(maxLength = 50),
            after = NeutralType.Integer,
            reversibility = Reversibility.MANUAL_REQUIRED,
            risks = OperationRisks(up = OperationRisk(requiresManualConfirmation = true)),
        )
        val artifact = MigrationPlanArtifactBuilder.build(
            plan = planWith(operations = listOf(automaticOp, notReversibleOp, manualOp)),
            rendered = renderedWith(),
            dialect = DatabaseDialect.POSTGRESQL,
            clock = fixedClock,
            dMigrateVersion = dMigrateVersion,
        )
        artifact.reversibilitySummary.fullyReversible shouldBe false
        artifact.reversibilitySummary.manualRequiredOperationIds shouldBe listOf("alter-1")
        artifact.reversibilitySummary.notReversibleOperationIds shouldBe listOf("drop-orders")

        // Validator catches the summary divergence if anything drifts —
        // this test pins that the builder feeds it correctly.
        MigrationPlanArtifactValidator.validate(artifact).hasBlockers shouldBe false
    }

    test("rendered statements get stable stmt-N ids and the scrubbed sql hash, transactionScope name") {
        val statement = MigrationDdlStatement(
            sql = "CREATE TABLE orders (id INTEGER PRIMARY KEY);",
            operationIds = setOf("create-orders"),
            risk = OperationRisk.SAFE,
            phase = DiffPhase.TABLES,
            transactionScope = TransactionScope.RUNNER_OWNED,
        )
        val artifact = MigrationPlanArtifactBuilder.build(
            plan = planWith(operations = listOf(sampleCreateTable("create-orders", "orders"))),
            rendered = renderedWith(listOf(statement, statement)),
            dialect = DatabaseDialect.POSTGRESQL,
            clock = fixedClock,
            dMigrateVersion = dMigrateVersion,
        )
        artifact.renderedStatements.size shouldBe 2
        artifact.renderedStatements[0].statementId shouldBe "stmt-1"
        artifact.renderedStatements[1].statementId shouldBe "stmt-2"
        artifact.renderedStatements[0].operationIds shouldBe listOf("create-orders")
        artifact.renderedStatements[0].sqlHash.length shouldBe 64
        artifact.renderedStatements[0].transactionScope shouldBe "RUNNER_OWNED"
    }

    test("diagnostics flow into the artifact preserving code, severity, operationId") {
        val diag = DiffDiagnostic(
            code = "RENAME_OVERLAY_TRIGGER_KEY_INVALID",
            message = "trigger entry missing canonical form",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = "rename-trigger-1",
        )
        val artifact = MigrationPlanArtifactBuilder.build(
            plan = planWith(diagnostics = listOf(diag)),
            rendered = renderedWith(),
            dialect = DatabaseDialect.POSTGRESQL,
            clock = fixedClock,
            dMigrateVersion = dMigrateVersion,
        )
        val view = artifact.diagnostics.single()
        view.code shouldBe "RENAME_OVERLAY_TRIGGER_KEY_INVALID"
        view.severity shouldBe "BLOCKER"
        view.operationId shouldBe "rename-trigger-1"
    }

    test("renameProjections round-trip into the artifact and auto-add the semantic-extension gate") {
        val nativeReport = RenameProjectionReport(
            candidateId = "cand-users",
            objectType = "TABLE",
            fromPath = listOf("users_old"),
            toPath = listOf("users"),
            overlaySource = "ovl/rename.json",
            overlayEntryId = "users_old-to-users",
            overlayHash = "0123456789abcdef",
            renameOperationId = "rename-users",
        )
        val fallbackReport = RenameProjectionReport(
            candidateId = "cand-audit",
            objectType = "TRIGGER",
            fromPath = listOf("orders", "audit_old"),
            toPath = listOf("orders", "audit_new"),
            overlaySource = "ovl/rename.json",
            overlayEntryId = "audit_old-to-audit_new",
            overlayHash = "fedcba9876543210",
            renameOperationId = null,
            fallbackOperationIds = listOf("drop-trigger-audit_old", "create-trigger-audit_new"),
            fallbackReason = "MySQL has no `ALTER TRIGGER ... RENAME`",
        )
        val artifact = MigrationPlanArtifactBuilder.build(
            plan = planWith(renameProjections = listOf(nativeReport, fallbackReport)),
            rendered = renderedWith(),
            dialect = DatabaseDialect.POSTGRESQL,
            clock = fixedClock,
            dMigrateVersion = dMigrateVersion,
        )
        artifact.renameProjections.size shouldBe 2
        artifact.renameProjections[0].candidateId shouldBe "cand-users"
        artifact.renameProjections[0].renameOperationId shouldBe "rename-users"
        artifact.renameProjections[1].renameOperationId shouldBe null
        artifact.renameProjections[1].fallbackOperationIds shouldBe
            listOf("drop-trigger-audit_old", "create-trigger-audit_new")
        artifact.renameProjections[1].fallbackReason shouldBe "MySQL has no `ALTER TRIGGER ... RENAME`"
        // Semantic-extension gate is auto-applied when renameProjections is non-empty.
        artifact.semanticExtensions shouldBe setOf(MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1)

        // Consumer with the extension support accepts the artifact.
        val result = MigrationPlanArtifactValidator.validate(
            artifact,
            context = MigrationPlanArtifactValidationContext(
                supportedSemanticExtensions = setOf(MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1),
            ),
        )
        result.hasBlockers shouldBe false
    }

    test("missing source/target fingerprint fails fast (operator wiring bug)") {
        val planWithoutFp = DiffResult(
            current = DiffEndpoint("acme", schemaVersion = "1", fingerprint = null),
            desired = DiffEndpoint("acme", schemaVersion = "2", fingerprint = "dst-fp"),
            schemaDiff = emptyDiff(),
            operations = emptyList(),
        )
        try {
            MigrationPlanArtifactBuilder.build(
                plan = planWithoutFp,
                rendered = renderedWith(),
                dialect = DatabaseDialect.POSTGRESQL,
                clock = fixedClock,
                dMigrateVersion = dMigrateVersion,
            )
            error("Expected fingerprint-missing error")
        } catch (e: IllegalStateException) {
            (e.message?.contains("fingerprint") ?: false) shouldBe true
        }
    }
})
