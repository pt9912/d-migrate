package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProjectionCapabilities
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as stringShouldContain
import java.nio.file.Files

/**
 * Plan-2 §F.4 dependency-projection T1: pins that the migration
 * overlay gate runs **before** `DiffPlanner.plan(...)`. A broken
 * overlay must yield Exit 8 *without* the planner being invoked.
 */
class SchemaMigratePrePlanOverlayGateTest : FunSpec({

    val tmpDir = Files.createTempDirectory("migrate-preplan-test")
    val sourcePath = tmpDir.resolve("source.yaml")
    val targetPath = tmpDir.resolve("target.yaml")
    Files.writeString(sourcePath, "# source")
    Files.writeString(targetPath, "# target")

    fun schemaWithTable(name: String) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            name to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                primaryKey = listOf("id"),
            ),
        ),
    )

    /** A rename overlay whose fingerprints intentionally do NOT match the actual schemas. */
    fun staleRenameOverlay(): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "stale-source-fp",
            targetFingerprint = "stale-target-fp",
            dialect = "postgresql",
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-orders-to-orders_v2",
                    objectType = "table",
                    fromName = "orders",
                    toName = "orders_v2",
                ),
            ),
            createdAt = "2026-05-14T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = "ovl/stale.json", overlay = overlay)
    }

    @Suppress("LongParameterList")
    fun runnerWith(
        plannerCallCount: IntArray = intArrayOf(0),
        executorCallCount: IntArray = intArrayOf(0),
        capturedReports: MutableList<SchemaMigrateReport> = mutableListOf(),
    ): Pair<SchemaMigrateRunner, MutableMap<String, String>> {
        val capture = mutableMapOf<String, String>()
        val spyPlanner = object : DiffPlanner() {
            override fun plan(
                current: SchemaDefinition,
                desired: SchemaDefinition,
                schemaDiff: SchemaDiff,
                migrationOverlays: List<MigrationOverlayDocument>,
                capabilities: RenameProjectionCapabilities,
                triggerPlanningContext: dev.dmigrate.core.diff.migration.TriggerPlanningContext,
                canonicalizeType: (dev.dmigrate.core.model.NeutralType) -> dev.dmigrate.core.model.NeutralType,
            ): DiffResult {
                plannerCallCount[0]++
                return super.plan(
                    current, desired, schemaDiff, migrationOverlays, capabilities, triggerPlanningContext,
                    canonicalizeType,
                )
            }
        }
        val runner = SchemaMigrateRunner(
            fileLoader = { op ->
                val schema = if (op.path == sourcePath) schemaWithTable("orders") else schemaWithTable("orders_v2")
                ResolvedSchemaOperand(
                    reference = "file:${op.path.fileName}",
                    schema = schema,
                    validation = ValidationResult(),
                )
            },
            dbLoader = { _, _ ->
                // Pretend the DB looks like the target file so --execute can
                // pass validate-request; the test never actually expects the
                // executor to fire.
                ResolvedSchemaOperand(
                    reference = "db:fake",
                    schema = schemaWithTable("orders_v2"),
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            normalizer = { it },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            planner = spyPlanner,
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
                        MigrationDdlResult(statements = emptyList(), operationsRendered = emptySet())
                    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
                        MigrationDdlResult(statements = emptyList(), operationsRendered = emptySet())
                }
            },
            executor = { _, _, _, _, _ ->
                executorCallCount[0]++
                ExecutionTrace(executionStarted = true, executionCompleted = true)
            },
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
            renderReport = { r, _ ->
                capturedReports += r
                "{\"status\":\"${r.status}\",\"exitCode\":${r.exitCode}," +
                    "\"diagnostics\":[${r.diagnostics.joinToString(",") { "\"${it.code}\"" }}]}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
            stdout = { capture.merge("stdout", it) { a, b -> "$a\n$b" } },
            stderr = { capture.merge("stderr", it) { a, b -> "$a\n$b" } },
        )
        return runner to capture
    }

    fun runnerWithSpyPlanner(plannerCallCount: IntArray) = runnerWith(plannerCallCount = plannerCallCount)

    test("broken overlay blocks before DiffPlanner.plan() is called") {
        val plannerCallCount = intArrayOf(0)
        val (runner, capture) = runnerWithSpyPlanner(plannerCallCount)
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
            migrationOverlays = listOf(staleRenameOverlay()),
        )

        val exit = runner.execute(request)

        exit shouldBe 8
        plannerCallCount[0] shouldBe 0
        capture["stdout"] stringShouldContain "OVERLAY_STALE_SOURCE_FINGERPRINT"
    }

    test("clean overlay path still calls the planner exactly once") {
        val plannerCallCount = intArrayOf(0)
        val (runner, _) = runnerWithSpyPlanner(plannerCallCount)
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
        )

        runner.execute(request)

        plannerCallCount[0] shouldBe 1
    }

    test("pre-plan blocker leaves operationsSkipped empty and blocker.operationIds empty") {
        val capturedReports = mutableListOf<SchemaMigrateReport>()
        val (runner, _) = runnerWith(capturedReports = capturedReports)
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
            migrationOverlays = listOf(staleRenameOverlay()),
        )

        runner.execute(request) shouldBe 8

        val report = capturedReports.single()
        report.operations.shouldBeEmpty()
        report.summary.operationsSkipped shouldBe 0
        // F.4 rename-mapping-invalid-enum: the stale rename overlay
        // emits both an `OVERLAY_STALE_SOURCE_FINGERPRINT` (generic
        // doc-level) and an `OVERLAY_RENAME_MAPPING_STALE_FINGERPRINT`
        // (rename-bound) blocker, so the result lists TWO blockers
        // (one MANUAL_ACTION_REQUIRED, one RENAME_MAPPING_INVALID).
        // Both must carry an empty operationIds list because the
        // pre-plan gate runs before DiffPlanner.plan(...) — no
        // operations have been authorised yet.
        report.blockers.shouldNotBeEmpty()
        report.blockers.forAll { it.operationIds.shouldBeEmpty() }
        // End-to-end pin: primaryBlockedReason actually surfaces as
        // the new reason string in the report JSON, not just at the
        // unit-test level. The view exposes it as a name-string so
        // tooling clients can read it without an enum dependency.
        report.summary.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID.name
        report.blockers.map { it.reason }.toSet() shouldBe setOf(
            MigrationBlockedReason.RENAME_MAPPING_INVALID.name,
            MigrationBlockedReason.MANUAL_ACTION_REQUIRED.name,
        )
    }

    test("pre-plan blocker surfaces both stale-fingerprint diagnostics") {
        val capturedReports = mutableListOf<SchemaMigrateReport>()
        val (runner, _) = runnerWith(capturedReports = capturedReports)
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
            migrationOverlays = listOf(staleRenameOverlay()),
        )

        runner.execute(request) shouldBe 8

        val codes = capturedReports.single().diagnostics.map { it.code }
        codes shouldContainAll listOf(
            "OVERLAY_STALE_SOURCE_FINGERPRINT",
            "OVERLAY_STALE_TARGET_FINGERPRINT",
        )
    }

    test("F.4 cli-inline-overlay: --rename-table flag drives a RenameTable op without a file overlay") {
        // End-to-end pin: the operator runs `schema migrate
        // --rename-table orders_v2:orders` and the runner builds an
        // inline overlay that folds the resulting Drop+Create pair
        // (current=orders_v2 → desired=orders) into a single
        // RenameTable op. No file overlay needed.
        val plannerCallCount = intArrayOf(0)
        val capturedReports = mutableListOf<SchemaMigrateReport>()
        val (runner, _) = runnerWith(plannerCallCount, capturedReports = capturedReports)
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
            renameTableFlags = listOf("orders_v2:orders"),
        )

        runner.execute(request) shouldBe 0
        plannerCallCount[0] shouldBe 1
        val report = capturedReports.single()
        // Rename was synthesised — operations carry one RenameTable.
        report.operations.map { it.kind } shouldContain "RenameTable"
        // Provenance: the inline overlay surfaces as
        // `cli-inline` in `overlays[]` with OVERLAY_ACCEPTED INFO.
        val inlineProvenance = report.overlays.single { it.source == "cli-inline" }
        inlineProvenance.diagnosticCode shouldBe "OVERLAY_ACCEPTED"
        inlineProvenance.entryId shouldBe "rename-table-0"
    }

    test("F.4 cli-inline-overlay: malformed --rename-table exits 2 without touching DiffPlanner or DB") {
        val plannerCallCount = intArrayOf(0)
        val executorCallCount = intArrayOf(0)
        val (runner, capture) = runnerWith(plannerCallCount, executorCallCount)
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
            renameTableFlags = listOf("not_a_valid_flag_value"),
        )

        runner.execute(request) shouldBe 2
        plannerCallCount[0] shouldBe 0
        executorCallCount[0] shouldBe 0
        capture.keys.any { it.startsWith("error:") } shouldBe true
    }

    test("F.4 cli-inline-overlay: cross-document conflict (file + inline rename-table) exits 8 before plan()") {
        val plannerCallCount = intArrayOf(0)
        val capturedReports = mutableListOf<SchemaMigrateReport>()
        val (runner, _) = runnerWith(plannerCallCount, capturedReports = capturedReports)
        // File overlay that's compatible with the schema fixtures
        // (current=orders_v2 → desired=orders) and the inline
        // `--rename-table` flag describes the same rename — the
        // cross-doc gate must flag the duplication.
        val currentFingerprint = dev.dmigrate.core.diff.migration.MigrationFingerprint.compute(schemaWithTable("orders_v2"))
        val desiredFingerprint = dev.dmigrate.core.diff.migration.MigrationFingerprint.compute(schemaWithTable("orders"))
        val fileOverlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = currentFingerprint,
            targetFingerprint = desiredFingerprint,
            dialect = "postgresql",
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "file-rename",
                    objectType = "table",
                    fromName = "orders_v2",
                    toName = "orders",
                ),
            ),
            createdAt = "2026-05-14T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()

        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
            migrationOverlays = listOf(MigrationOverlayDocument(source = "ovl/file.json", overlay = fileOverlay)),
            renameTableFlags = listOf("orders_v2:orders"),
        )

        runner.execute(request) shouldBe 8
        plannerCallCount[0] shouldBe 0
        val report = capturedReports.single()
        // Both sources are listed in the report.
        report.overlays.map { it.source }.toSet() shouldBe setOf("ovl/file.json", "cli-inline")
        report.summary.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID.name
    }

    test("--execute with a pre-plan blocker exits 8 WITHOUT touching the live DB") {
        val plannerCallCount = intArrayOf(0)
        val executorCallCount = intArrayOf(0)
        val (runner, _) = runnerWith(plannerCallCount, executorCallCount)
        val reportPath = tmpDir.resolve("preplan-execute.report.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:fake://localhost/db",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = reportPath,
            migrationOverlays = listOf(staleRenameOverlay()),
        )

        val exit = runner.execute(request)

        exit shouldBe 8
        plannerCallCount[0] shouldBe 0
        executorCallCount[0] shouldBe 0
    }
})
