package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SqliteCatalogProbeMode
import dev.dmigrate.driver.SqliteLiveCatalog
import dev.dmigrate.driver.sqliteContext
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan-2 §A.2: pins the SQLite-live-catalog-probe pre-render hook
 * on [SchemaMigrateRunner].
 *
 * - SQLite + `--execute` + wired probe → probe invoked, catalog
 *   forwarded to renderer via [DdlGenerationOptions.liveSqliteCatalog],
 *   `catalogProbeMode = LIVE_SQLITE_MASTER`.
 * - SQLite + `--execute` + probe throws → synthetic
 *   `SQLITE_LIVE_CATALOG_PROBE_FAILED` blocker, Exit 8, executor
 *   never invoked.
 * - SQLite without `--execute` (or probe not wired) → renderer sees
 *   default options, runner appends
 *   `SQLITE_LIVE_CATALOG_NOT_RUN_FILE_TARGET` INFO diagnostic.
 * - PostgreSQL + `--execute` → probe NOT invoked (SQLite-specific).
 */
class SchemaMigrateRunnerSqliteProbeTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-sqlite-probe-test")
    val sourcePath = tmpDir.resolve("source.yaml").also { Files.writeString(it, "# source") }

    fun schemaWith(table: String) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            table to dev.dmigrate.core.model.TableDefinition(
                columns = mapOf(
                    "id" to dev.dmigrate.core.model.ColumnDefinition(
                        dev.dmigrate.core.model.NeutralType.Integer,
                        required = true,
                    ),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun fakeRendered() = MigrationDdlResult(
        statements = listOf(
            MigrationDdlStatement(
                sql = "CREATE TABLE x (id INT);",
                operationIds = setOf("op-1"),
                risk = dev.dmigrate.core.diff.migration.OperationRisk.SAFE,
                phase = dev.dmigrate.core.diff.migration.DiffPhase.TABLES,
            ),
        ),
        operationsRendered = setOf("op-1"),
    )

    @Suppress("LongParameterList")
    fun runnerWith(
        dialect: DatabaseDialect,
        sqliteLiveCatalogProbe: ((CompareOperand.Database, Path?) -> SqliteLiveCatalog)? = null,
        executor: SegmentAwareExecutorFn? = null,
        capturedOptions: AtomicRef<DdlGenerationOptions?> = AtomicRef(null),
        capturedReport: AtomicRef<SchemaMigrateReport?> = AtomicRef(null),
    ): SchemaMigrateRunner {
        val schema = schemaWith("orders")
        return SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(
                    reference = "file:src",
                    schema = schema,
                    validation = ValidationResult(),
                )
            },
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    // Both pre-render (current target) and post-execute
                    // (drift check) loads return the desired schema, so
                    // post-compare is clean and `--execute` lands on
                    // Exit 0. The probe wiring itself is the test
                    // surface here — the diff being empty is irrelevant
                    // because the mock renderer returns one statement
                    // unconditionally.
                    schema = schema,
                    validation = ValidationResult(),
                    dialect = dialect,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { d ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = d
                    override fun generateUp(
                        diff: dev.dmigrate.core.diff.migration.DiffResult,
                        options: DdlGenerationOptions,
                    ): MigrationDdlResult {
                        capturedOptions.value = options
                        return fakeRendered()
                    }

                    override fun generateDown(
                        diff: dev.dmigrate.core.diff.migration.DiffResult,
                        options: DdlGenerationOptions,
                    ) = fakeRendered()
                }
            },
            executor = executor,
            sqliteLiveCatalogProbe = sqliteLiveCatalogProbe,
            atomicWriter = { p, c -> Files.writeString(p, c) },
            renderReport = { report, _ ->
                capturedReport.value = report
                "{}"
            },
            printError = { _, _ -> },
        )
    }

    test("§A.2 — SQLite + --execute forwards probed catalog and sets LIVE_SQLITE_MASTER mode") {
        val probedCatalog = SqliteLiveCatalog(tables = setOf("ad_hoc_table"))
        val capturedOptions = AtomicRef<DdlGenerationOptions?>(null)
        val capturedReport = AtomicRef<SchemaMigrateReport?>(null)
        val runner = runnerWith(
            dialect = DatabaseDialect.SQLITE,
            sqliteLiveCatalogProbe = { _, _ -> probedCatalog },
            executor = { _, _, segments, _, _ ->
                val statements = segments.flatMap { it.statements }
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = statements.size,
                )
            },
            capturedOptions = capturedOptions,
            capturedReport = capturedReport,
        )
        val reportPath = tmpDir.resolve("sqlite-probe-ok.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:sqlite:/tmp/test.db",
            dialect = DatabaseDialect.SQLITE,
            execute = true,
            report = reportPath,
        )
        runner.execute(request) shouldBe 0
        capturedOptions.value?.sqliteContext?.liveCatalog shouldBe probedCatalog
        capturedOptions.value?.sqliteContext?.catalogProbeMode shouldBe SqliteCatalogProbeMode.LIVE_SQLITE_MASTER
        capturedReport.value?.summary?.catalogProbeMode shouldBe "LIVE_SQLITE_MASTER"
    }

    test("§A.2 — probe throws → Exit 8 with SQLITE_LIVE_CATALOG_PROBE_FAILED diagnostic; executor not invoked") {
        var executorCalls = 0
        val capturedReport = AtomicRef<SchemaMigrateReport?>(null)
        val runner = runnerWith(
            dialect = DatabaseDialect.SQLITE,
            sqliteLiveCatalogProbe = { _, _ -> error("simulated probe failure") },
            executor = { _, _, _, _, _ ->
                executorCalls++
                ExecutionTrace(executionStarted = true, executionCompleted = true)
            },
            capturedReport = capturedReport,
        )
        val reportPath = tmpDir.resolve("sqlite-probe-fail.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:sqlite:/tmp/test.db",
            dialect = DatabaseDialect.SQLITE,
            execute = true,
            report = reportPath,
        )
        runner.execute(request) shouldBe 8
        executorCalls shouldBe 0
        val codes = capturedReport.value?.diagnostics?.map { it.code }.orEmpty()
        ("SQLITE_LIVE_CATALOG_PROBE_FAILED" in codes) shouldBe true
        // Probe-mode stays SCHEMA_ONLY when the probe fails — no live catalog
        // ever reached the renderer (render was skipped entirely).
        capturedReport.value?.summary?.catalogProbeMode shouldBe "SCHEMA_ONLY"
    }

    test("§A.2 — SQLite without --execute appends SQLITE_LIVE_CATALOG_NOT_RUN_FILE_TARGET diagnostic") {
        val capturedOptions = AtomicRef<DdlGenerationOptions?>(null)
        val capturedReport = AtomicRef<SchemaMigrateReport?>(null)
        val runner = runnerWith(
            dialect = DatabaseDialect.SQLITE,
            sqliteLiveCatalogProbe = { _, _ -> SqliteLiveCatalog(tables = setOf("should-not-be-used")) },
            capturedOptions = capturedOptions,
            capturedReport = capturedReport,
        )
        val reportPath = tmpDir.resolve("sqlite-plan-only.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:sqlite:/tmp/test.db",
            dialect = DatabaseDialect.SQLITE,
            planOnly = true,
            report = reportPath,
        )
        runner.execute(request) shouldBe 0
        // Renderer saw default options because the probe is gated by --execute.
        capturedOptions.value?.sqliteContext?.liveCatalog shouldBe null
        capturedOptions.value?.sqliteContext?.catalogProbeMode shouldBe SqliteCatalogProbeMode.SCHEMA_ONLY
        val codes = capturedReport.value?.diagnostics?.map { it.code }.orEmpty()
        ("SQLITE_LIVE_CATALOG_NOT_RUN_FILE_TARGET" in codes) shouldBe true
    }

    test("§A.2 — PostgreSQL + --execute does NOT invoke SQLite probe and emits no probe diagnostic") {
        var probeInvocations = 0
        val capturedOptions = AtomicRef<DdlGenerationOptions?>(null)
        val capturedReport = AtomicRef<SchemaMigrateReport?>(null)
        val runner = runnerWith(
            dialect = DatabaseDialect.POSTGRESQL,
            sqliteLiveCatalogProbe = { _, _ ->
                probeInvocations++
                SqliteLiveCatalog()
            },
            executor = { _, _, _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            capturedOptions = capturedOptions,
            capturedReport = capturedReport,
        )
        val reportPath = tmpDir.resolve("pg-no-probe.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = reportPath,
        )
        runner.execute(request) shouldBe 0
        probeInvocations shouldBe 0
        // Non-SQLite dialects leave dialectContext = None so no SQLite-specific context is set.
        capturedOptions.value?.sqliteContext shouldBe null
        // Non-SQLite dialect is silently skipped — no NOT_RUN diagnostic noise.
        val codes = capturedReport.value?.diagnostics?.map { it.code }.orEmpty()
        ("SQLITE_LIVE_CATALOG_NOT_RUN_FILE_TARGET" in codes) shouldBe false
        ("SQLITE_LIVE_CATALOG_PROBE_FAILED" in codes) shouldBe false
    }
})

/** Test-local mutable holder so closures can publish a value out of generateUp. */
private class AtomicRef<T>(@Volatile var value: T)
