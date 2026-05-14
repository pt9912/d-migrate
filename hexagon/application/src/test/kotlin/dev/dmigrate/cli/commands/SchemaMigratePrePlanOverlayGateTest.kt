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
import dev.dmigrate.driver.migration.MigrationDdlResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    fun runnerWithSpyPlanner(plannerCallCount: IntArray): Pair<SchemaMigrateRunner, MutableMap<String, String>> {
        val capture = mutableMapOf<String, String>()
        val spyPlanner = object : DiffPlanner() {
            override fun plan(
                current: SchemaDefinition,
                desired: SchemaDefinition,
                schemaDiff: SchemaDiff,
                migrationOverlays: List<MigrationOverlayDocument>,
                capabilities: RenameProjectionCapabilities,
            ): DiffResult {
                plannerCallCount[0]++
                return super.plan(current, desired, schemaDiff, migrationOverlays, capabilities)
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
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
            renderReport = { r, _ ->
                "{\"status\":\"${r.status}\",\"exitCode\":${r.exitCode}," +
                    "\"diagnostics\":[${r.diagnostics.joinToString(",") { "\"${it.code}\"" }}]}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
            stdout = { capture.merge("stdout", it) { a, b -> "$a\n$b" } },
            stderr = { capture.merge("stderr", it) { a, b -> "$a\n$b" } },
        )
        return runner to capture
    }

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
        capture["stdout"] shouldContain "OVERLAY_STALE_SOURCE_FINGERPRINT"
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
})
