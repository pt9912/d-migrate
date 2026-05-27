package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlSequenceCanonicityDeclaration
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import dev.dmigrate.driver.mysqlContext
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * E.3 MySQL Sequence Drift-Check follow-up (2026-05-21): end-to-end
 * wiring coverage for [SchemaMigrateRunner] focused on the probe →
 * DdlGenerationOptions → MigrationDdlResult → SchemaMigrateReport
 * chain. The probe-side unit tests (`MysqlSequenceCanonicityStageTest`,
 * `MysqlSequenceCanonicityGateTest`) cover the individual decisions;
 * this test pins the full path so a future refactor cannot drop a
 * declaration on its way from the dispatcher to the rendered YAML/JSON
 * report.
 *
 * Mirrors [SchemaMigrateRunnerSqliteProbeTest]'s shape so the two
 * preflight probes stay diffable: same fake-DB-loader plumbing, same
 * captured-report introspection, same `--execute` / `--plan-only`
 * matrix. The differences live in the dialect (MySQL vs. SQLite) and
 * the plan (CreateSequence + AddColumn-with-default vs. plain
 * CreateTable).
 */
class SchemaMigrateRunnerMysqlSequenceCanonicityProbeTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-mysql-seq-probe-test")
    val sourcePath = tmpDir.resolve("source.yaml").also { Files.writeString(it, "# source") }
    val emptyTargetPath = tmpDir.resolve("target-empty.yaml")
        .also { Files.writeString(it, "# empty target") }

    fun emptySchema(): SchemaDefinition = SchemaDefinition(name = "App", version = "1")

    fun schemaWithSequence(): SchemaDefinition = SchemaDefinition(
        name = "App",
        version = "1",
        sequences = mapOf("order_seq" to SequenceDefinition(start = 1L, increment = 1L)),
        tables = mapOf(
            "orders" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                    "number" to ColumnDefinition(
                        type = NeutralType.BigInteger,
                        default = DefaultValue.SequenceNextVal("order_seq"),
                    ),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun fakeRendered(opIds: Set<String> = setOf("op-1")) = MigrationDdlResult(
        statements = listOf(
            MigrationDdlStatement(
                sql = "INSERT INTO `dmg_sequences` (...);",
                operationIds = opIds,
                risk = dev.dmigrate.core.diff.migration.OperationRisk.SAFE,
                phase = dev.dmigrate.core.diff.migration.DiffPhase.TABLES,
            ),
        ),
        operationsRendered = opIds,
    )

    @Suppress("LongParameterList")
    fun runnerWith(
        dialect: DatabaseDialect,
        probe: MysqlSequenceCanonicityProbeFn? = null,
        executor: ExecutorFn? = null,
        sourceSchema: SchemaDefinition = schemaWithSequence(),
        // Pre-render DB schema (target) — empty by default so the
        // diff produces at least one sequence-related op, which is
        // what triggers the probe per `MysqlSequenceCanonicityStage`.
        targetSchema: SchemaDefinition = emptySchema(),
        capturedOptions: CapturedRef<DdlGenerationOptions?> = CapturedRef(null),
        capturedReport: CapturedRef<SchemaMigrateReport?> = CapturedRef(null),
    ): SchemaMigrateRunner {
        // dbLoader fires twice on `--execute`: once for pre-render
        // (returns the EMPTY target so the diff has CreateSequence)
        // and once for post-compare (returns the DESIRED schema so
        // the runner lands on Exit 0). A counter switches between
        // them; file-mode and blocker paths skip the post-compare
        // call so they never see the second slot.
        val dbLoadCalls = CapturedRef(0)
        return SchemaMigrateRunner(
            fileLoader = { op ->
                val schema = if (op.path == emptyTargetPath) emptySchema() else sourceSchema
                ResolvedSchemaOperand(
                    reference = "file:${op.path.fileName}",
                    schema = schema,
                    validation = ValidationResult(),
                )
            },
            dbLoader = { op, _ ->
                val callIndex = dbLoadCalls.value++
                val schema = if (callIndex == 0) targetSchema else sourceSchema
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
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
            mysqlSequenceCanonicityProbe = probe,
            atomicWriter = { p, c -> Files.writeString(p, c) },
            renderReport = { report, _ ->
                capturedReport.value = report
                "{}"
            },
            printError = { _, _ -> },
        )
    }

    test("MySQL + --execute + canonical probe → declarations flow into report; exit 0") {
        val capturedOptions = CapturedRef<DdlGenerationOptions?>(null)
        val capturedReport = CapturedRef<SchemaMigrateReport?>(null)
        val probeInvocations = CapturedRef(0)
        val cannedDeclarations = listOf(
            MysqlSequenceCanonicityDeclaration(
                operationId = "op-create-seq",
                dialect = "mysql",
                kind = MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                objectName = "order_seq",
                status = MysqlSequenceCanonicityStatus.CANONICAL,
                sqlHash = "hash-1",
            ),
            MysqlSequenceCanonicityDeclaration(
                operationId = "op-create-seq",
                dialect = "mysql",
                kind = MysqlSequenceCanonicityKind.SUPPORT_TABLE,
                objectName = "dmg_sequences",
                status = MysqlSequenceCanonicityStatus.CANONICAL,
                sqlHash = "hash-2",
            ),
        )
        val runner = runnerWith(
            dialect = DatabaseDialect.MYSQL,
            probe = { _, _, _ ->
                probeInvocations.value++
                cannedDeclarations
            },
            executor = { _, statements, _ ->
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = statements.size,
                )
            },
            capturedOptions = capturedOptions,
            capturedReport = capturedReport,
        )
        val reportPath = tmpDir.resolve("mysql-seq-ok.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:mysql://localhost/test",
            dialect = DatabaseDialect.MYSQL,
            execute = true,
            report = reportPath,
        )

        runner.execute(request) shouldBe 0
        probeInvocations.value shouldBe 1
        // Render options carry the probe's declarations verbatim
        // (Sub-Slice E plumbing).
        capturedOptions.value?.mysqlContext?.sequenceCanonicity shouldBe cannedDeclarations
        // Report view carries one entry per probed object — JSON/YAML
        // renderers iterate this directly.
        val views = capturedReport.value?.mysqlSequenceCanonicity.orEmpty()
        views shouldHaveSize cannedDeclarations.size
        views.map { it.kind }.toSet() shouldBe setOf("SEQUENCE_ROW", "SUPPORT_TABLE")
        views.all { it.status == "CANONICAL" } shouldBe true
        views.all { it.driftField == null && it.expected == null && it.actual == null } shouldBe true
    }

    test("MySQL + --execute + DRIFT declaration → exit 8 with E124_MYSQL_SEQUENCE_DRIFT_ROW diagnostic") {
        val capturedReport = CapturedRef<SchemaMigrateReport?>(null)
        // The fake renderer below echoes any drift declaration from
        // options into MigrationDdlResult.blockers / diagnostics —
        // the production renderer does the same via the
        // MysqlSequenceCanonicityGate. Pinning that the gate WILL
        // see DRIFT in options is the contract this test holds; the
        // gate's own unit tests pin the message text.
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schemaWithSequence(), validation = ValidationResult())
            },
            // The blocker short-circuits the runner BEFORE post-
            // compare, so a single empty-schema load is enough to
            // get a CreateSequence diff op into the plan.
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}", schema = emptySchema(),
                    validation = ValidationResult(), dialect = DatabaseDialect.MYSQL,
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
                        // Simulate what the real MySQL renderer does
                        // when the canonicity-gate sees a DRIFT entry:
                        // it surfaces a MANUAL_ACTION_REQUIRED blocker
                        // with the E124_… code and propagates the
                        // declaration into the result.
                        val driftDecl = (options.mysqlContext?.sequenceCanonicity ?: emptyList()).single {
                            it.status == MysqlSequenceCanonicityStatus.DRIFT
                        }
                        val diag = dev.dmigrate.core.diff.migration.DiffDiagnostic(
                            code = "E124_MYSQL_SEQUENCE_DRIFT_ROW",
                            message = "drift on ${driftDecl.kind} `${driftDecl.objectName}`",
                            severity = dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.BLOCKER,
                            operationId = driftDecl.operationId,
                        )
                        return MigrationDdlResult(
                            statements = emptyList(),
                            operationsRendered = emptySet(),
                            operationsSkipped = setOf(driftDecl.operationId),
                            blockers = listOf(
                                dev.dmigrate.driver.migration.MigrationBlocker(
                                    reason = dev.dmigrate.driver.migration.MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                                    operationIds = setOf(driftDecl.operationId),
                                    diagnostics = listOf(diag),
                                ),
                            ),
                            primaryBlockedReason = dev.dmigrate.driver.migration.MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                            // ReportBuilder consumes `MigrationDdlResult.diagnostics` directly
                            // (blocker-level diagnostics surface via `blockers[].diagnosticCodes`),
                            // so lift the gate diagnostic up to the top level — mirror of how
                            // the production MySQL renderer emits drift diagnostics.
                            diagnostics = listOf(diag),
                            mysqlSequenceCanonicity = options.mysqlContext?.sequenceCanonicity ?: emptyList(),
                        )
                    }

                    override fun generateDown(
                        diff: dev.dmigrate.core.diff.migration.DiffResult,
                        options: DdlGenerationOptions,
                    ) = fakeRendered()
                }
            },
            executor = { _, _, _ -> error("executor MUST NOT run on a blocked render") },
            mysqlSequenceCanonicityProbe = { _, _, plan ->
                val createSeqOp = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single()
                listOf(
                    MysqlSequenceCanonicityDeclaration(
                        operationId = createSeqOp.id,
                        dialect = "mysql",
                        kind = MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                        objectName = "order_seq",
                        status = MysqlSequenceCanonicityStatus.DRIFT,
                        sqlHash = "drift-hash",
                        driftField = "increment_by",
                        expected = "1",
                        actual = "5",
                    ),
                )
            },
            atomicWriter = { p, c -> Files.writeString(p, c) },
            renderReport = { report, _ ->
                capturedReport.value = report
                "{}"
            },
            printError = { _, _ -> },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:mysql://localhost/test",
            dialect = DatabaseDialect.MYSQL,
            execute = true,
            report = tmpDir.resolve("mysql-seq-drift.json"),
        )

        runner.execute(request) shouldBe 8
        // Drift declaration reaches the rendered report unchanged so
        // operators see the diff context in YAML/JSON.
        val driftView = capturedReport.value?.mysqlSequenceCanonicity
            ?.single { it.status == "DRIFT" }
        driftView shouldNotBe null
        driftView?.driftField shouldBe "increment_by"
        driftView?.expected shouldBe "1"
        driftView?.actual shouldBe "5"
        val codes = capturedReport.value?.diagnostics?.map { it.code }.orEmpty()
        ("E124_MYSQL_SEQUENCE_DRIFT_ROW" in codes) shouldBe true
    }

    test("probe throws → Exit 8 with MYSQL_SEQUENCE_DRIFT_RUN_FAILED header; executor never runs") {
        val capturedReport = CapturedRef<SchemaMigrateReport?>(null)
        var executorCalls = 0
        val runner = runnerWith(
            dialect = DatabaseDialect.MYSQL,
            probe = { _, _, _ -> error("permission denied for INFORMATION_SCHEMA.COLUMNS") },
            executor = { _, _, _ ->
                executorCalls++
                ExecutionTrace(executionStarted = true, executionCompleted = true)
            },
            capturedReport = capturedReport,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:mysql://localhost/test",
            dialect = DatabaseDialect.MYSQL,
            execute = true,
            report = tmpDir.resolve("mysql-seq-probe-fail.json"),
        )

        runner.execute(request) shouldBe 8
        executorCalls shouldBe 0
        val codes = capturedReport.value?.diagnostics?.map { it.code }.orEmpty()
        ("MYSQL_SEQUENCE_DRIFT_RUN_FAILED" in codes) shouldBe true
        val msg = capturedReport.value?.diagnostics
            ?.single { it.code == "MYSQL_SEQUENCE_DRIFT_RUN_FAILED" }?.message
            .orEmpty()
        msg shouldContain "permission denied"
        // Per-op stamps land in mysqlSequenceCanonicity so the YAML/
        // JSON view still tells the operator which op blocked.
        val stamped = capturedReport.value?.mysqlSequenceCanonicity.orEmpty()
        stamped.isNotEmpty() shouldBe true
        stamped.all { it.status == "PROBE_RUNTIME_ERROR" } shouldBe true
        stamped.all { (it.problem ?: "").contains("permission denied") } shouldBe true
    }

    test("file target → probe never invoked; declarations stay as NOT_RUN_FILE_TARGET in the report") {
        val capturedReport = CapturedRef<SchemaMigrateReport?>(null)
        var probeInvocations = 0
        val runner = runnerWith(
            dialect = DatabaseDialect.MYSQL,
            probe = { _, _, _ ->
                probeInvocations++
                emptyList()
            },
            capturedReport = capturedReport,
        )
        // Source carries the desired sequence; target file is empty
        // so the plan still produces a CreateSequence op. The
        // pre-plan planner stamps it as NOT_RUN_FILE_TARGET because
        // there's no live DB to probe against.
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = emptyTargetPath.toString(),
            dialect = DatabaseDialect.MYSQL,
            planOnly = true,
            report = tmpDir.resolve("mysql-seq-file-target.json"),
        )

        runner.execute(request) shouldBe 0
        probeInvocations shouldBe 0
        // MigrationPreflightPlanner stamps NOT_RUN_FILE_TARGET for
        // every sequence-related op so the report still shows the
        // probe was considered.
        val statuses = capturedReport.value?.mysqlSequenceCanonicity
            ?.map { it.status }
            .orEmpty()
            .toSet()
        statuses shouldBe setOf("NOT_RUN_FILE_TARGET")
    }

    test("PostgreSQL + --execute + sequence plan → probe never invoked, no canonicity views surface") {
        val capturedReport = CapturedRef<SchemaMigrateReport?>(null)
        var probeInvocations = 0
        val runner = runnerWith(
            dialect = DatabaseDialect.POSTGRESQL,
            probe = { _, _, _ ->
                probeInvocations++
                emptyList()
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            capturedReport = capturedReport,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = tmpDir.resolve("mysql-seq-pg-skip.json"),
        )

        runner.execute(request) shouldBe 0
        probeInvocations shouldBe 0
        capturedReport.value?.mysqlSequenceCanonicity?.isEmpty() shouldBe true
    }

    test("MySQL + --execute but no sequence-related ops → probe never invoked") {
        val capturedReport = CapturedRef<SchemaMigrateReport?>(null)
        var probeInvocations = 0
        val plainTableSchema = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "events" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val runner = runnerWith(
            dialect = DatabaseDialect.MYSQL,
            sourceSchema = plainTableSchema,
            probe = { _, _, _ ->
                probeInvocations++
                emptyList()
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            capturedReport = capturedReport,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:mysql://localhost/test",
            dialect = DatabaseDialect.MYSQL,
            execute = true,
            report = tmpDir.resolve("mysql-seq-no-ops.json"),
        )

        runner.execute(request) shouldBe 0
        probeInvocations shouldBe 0
        capturedReport.value?.mysqlSequenceCanonicity?.isEmpty() shouldBe true
    }
})

/**
 * Test-local mutable holder so the renderer/loader closures can
 * publish values out of `generateUp` / `renderReport`. Identical
 * shape to the holder in [SchemaMigrateRunnerSqliteProbeTest] —
 * kept private per file so the two probe tests stay independent.
 */
private class CapturedRef<T>(@Volatile var value: T)
