package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * 0.9.7 preserve-current-value Sub-Slice D (2026-05-21): end-to-end
 * wiring coverage for the SequencePreserveStage as plumbed into
 * [SchemaMigrateRunner]. The stage-internal logic is covered by
 * [SequencePreserveStageTest]; here we pin the probe → augmented plan
 * → MigrationDdlResult → SchemaMigrateReport chain so a future
 * refactor cannot drop a follow-up on its way from the dispatcher to
 * the rendered Up SQL.
 *
 * Mirrors [SchemaMigrateRunnerMysqlSequenceCanonicityProbeTest] for
 * cross-workstream diffability.
 */
class SchemaMigrateRunnerSequencePreserveTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-preserve-test")
    val sourcePath = tmpDir.resolve("source.yaml").also { Files.writeString(it, "# source") }

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun schemaWithSequence(preserve: Boolean = true) = SchemaDefinition(
        name = "App",
        version = "1",
        sequences = mapOf(
            "order_seq" to SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = preserve),
        ),
    )

    fun fakeRendered(plan: dev.dmigrate.core.diff.migration.DiffResult): MigrationDdlResult {
        // Renderer emits one statement per operation so the test can
        // observe whether the augmented plan's AlterSequenceCurrentValue
        // follow-up reached the renderer.
        val opIds = plan.operations.map { it.id }.toSet()
        val statements = plan.operations.map { op ->
            val opLabel = op::class.simpleName ?: "Op"
            MigrationDdlStatement(
                sql = "/* $opLabel ${op.id} */;",
                operationIds = setOf(op.id),
                risk = dev.dmigrate.core.diff.migration.OperationRisk.SAFE,
                phase = dev.dmigrate.core.diff.migration.DiffPhase.SEQUENCES,
            )
        }
        return MigrationDdlResult(statements = statements, operationsRendered = opIds)
    }

    @Suppress("LongParameterList")
    fun runnerWith(
        dialect: DatabaseDialect,
        probe: SequenceCurrentValueProbeFn? = null,
        executor: ExecutorFn? = null,
        sourceSchema: SchemaDefinition = schemaWithSequence(),
        targetSchema: SchemaDefinition = emptySchema(),
        capturedPlan: PreserveCapturedRef<dev.dmigrate.core.diff.migration.DiffResult?> = PreserveCapturedRef(null),
        capturedReport: PreserveCapturedRef<SchemaMigrateReport?> = PreserveCapturedRef(null),
    ): SchemaMigrateRunner {
        val dbLoadCalls = PreserveCapturedRef(0)
        return SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = sourceSchema, validation = ValidationResult())
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
                        capturedPlan.value = diff
                        return fakeRendered(diff)
                    }

                    override fun generateDown(
                        diff: dev.dmigrate.core.diff.migration.DiffResult,
                        options: DdlGenerationOptions,
                    ) = fakeRendered(diff)
                }
            },
            executor = executor,
            sequenceCurrentValueProbe = probe,
            atomicWriter = { p, c -> Files.writeString(p, c) },
            renderReport = { report, _ ->
                capturedReport.value = report
                "{}"
            },
            printError = { _, _ -> },
        )
    }

    // Both source and target carry the sequence with
    // preserveCurrentValue=true, but with different increments so
    // the diff produces an AlterSequence (which is always a probe
    // candidate per §6.4.1 — unlike CreateSequence which requires
    // renameProvenance != null in this conservative tranche).
    fun alterSchemas(): Pair<SchemaDefinition, SchemaDefinition> {
        val sourceSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(start = 1L, increment = 5L, preserveCurrentValue = true)),
        )
        val targetSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = true)),
        )
        return sourceSchema to targetSchema
    }

    test("PG + --execute + Read → augmented plan has AlterSequenceCurrentValue follow-up; report exposes it") {
        val capturedPlan = PreserveCapturedRef<dev.dmigrate.core.diff.migration.DiffResult?>(null)
        val capturedReport = PreserveCapturedRef<SchemaMigrateReport?>(null)
        val probeCalls = PreserveCapturedRef(0)
        val (sourceSchema, targetSchema) = alterSchemas()
        val runner = runnerWith(
            dialect = DatabaseDialect.POSTGRESQL,
            probe = { _, _, _ ->
                probeCalls.value++
                SequenceCurrentValueProbeResult.Read(value = 42L, isCalled = true)
            },
            executor = { _, statements, _ ->
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = statements.size,
                )
            },
            sourceSchema = sourceSchema,
            targetSchema = targetSchema,
            capturedPlan = capturedPlan,
            capturedReport = capturedReport,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = tmpDir.resolve("preserve-pg-ok.json"),
        )
        runner.execute(request) shouldBe 0
        probeCalls.value shouldBe 1
        // Augmented plan: AlterSequence + AlterSequenceCurrentValue
        // follow-up. The renderer received the augmented op stream.
        val ops = capturedPlan.value!!.operations
        ops.map { it::class.simpleName } shouldBe listOf("AlterSequence", "AlterSequenceCurrentValue")
        val followUp = ops[1] as DiffOperation.AlterSequenceCurrentValue
        followUp.currentValue shouldBe 42L
        followUp.isCalled shouldBe true
        // The report's operations list mirrors the augmented plan
        // (§6.4.7 contract): the operator sees the setval follow-up
        // in `report.operations`.
        capturedReport.value!!.operations.map { it.kind }.toSet() shouldBe setOf(
            "AlterSequence",
            "AlterSequenceCurrentValue",
        )
    }

    test("MySQL + --execute + Read → MySQL renderer sees the follow-up too (isCalled=null)") {
        val capturedPlan = PreserveCapturedRef<dev.dmigrate.core.diff.migration.DiffResult?>(null)
        val (sourceSchema, targetSchema) = alterSchemas()
        val runner = runnerWith(
            dialect = DatabaseDialect.MYSQL,
            probe = { _, _, _ -> SequenceCurrentValueProbeResult.Read(value = 7L, isCalled = null) },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            sourceSchema = sourceSchema,
            targetSchema = targetSchema,
            capturedPlan = capturedPlan,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:mysql://localhost/test",
            dialect = DatabaseDialect.MYSQL,
            execute = true,
            report = tmpDir.resolve("preserve-mysql-ok.json"),
        )
        runner.execute(request) shouldBe 0
        val followUp = capturedPlan.value!!.operations
            .filterIsInstance<DiffOperation.AlterSequenceCurrentValue>()
            .single()
        followUp.currentValue shouldBe 7L
        // MySQL probe never returns isCalled — Renderer ignores it.
        followUp.isCalled shouldBe null
    }

    test("PG + --execute + AlterSequence + NotFound → exit 8 with SEQUENCE_PRESERVE_PROBE_FAILED") {
        val capturedReport = PreserveCapturedRef<SchemaMigrateReport?>(null)
        var executorCalls = 0
        // Both source and target carry the sequence with preserve=true
        // so the diff produces an AlterSequence (or no-op if equal —
        // but the AlterSequence path needs an actual delta to fire).
        // Easier: just use the same schema with diff=alter-increment.
        val sourceSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(start = 1L, increment = 5L, preserveCurrentValue = true)),
        )
        val targetSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = true)),
        )
        val runner = runnerWith(
            dialect = DatabaseDialect.POSTGRESQL,
            probe = { _, _, _ -> SequenceCurrentValueProbeResult.NotFound },
            executor = { _, _, _ ->
                executorCalls++
                ExecutionTrace(executionStarted = true, executionCompleted = true)
            },
            sourceSchema = sourceSchema,
            targetSchema = targetSchema,
            capturedReport = capturedReport,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = tmpDir.resolve("preserve-pg-notfound-alter.json"),
        )
        runner.execute(request) shouldBe 8
        executorCalls shouldBe 0
        val codes = capturedReport.value?.diagnostics?.map { it.code }.orEmpty()
        ("SEQUENCE_PRESERVE_PROBE_FAILED" in codes) shouldBe true
    }

    test("PG + --execute + CreateSequence + NotFound → exit 0, SEQUENCE_PRESERVE_NOT_FOUND INFO surfaces") {
        // CreateSequence without renameProvenance is not a probe
        // candidate (shouldProbeCreateSequence returns false), so the
        // stage emits no diagnostic for it — it's filtered out before
        // probing. Therefore probe is never called in this scenario.
        var probeCalls = 0
        val runner = runnerWith(
            dialect = DatabaseDialect.POSTGRESQL,
            probe = { _, _, _ ->
                probeCalls++
                SequenceCurrentValueProbeResult.NotFound
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = tmpDir.resolve("preserve-pg-notfound-create.json"),
        )
        runner.execute(request) shouldBe 0
        probeCalls shouldBe 0 // Filtered before probe.
    }

    test("Probe throws → exit 8 with PROBE_FAILED diagnostic; executor never runs") {
        val capturedReport = PreserveCapturedRef<SchemaMigrateReport?>(null)
        var executorCalls = 0
        val sourceSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(increment = 5L, preserveCurrentValue = true)),
        )
        val targetSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(increment = 1L, preserveCurrentValue = true)),
        )
        val runner = runnerWith(
            dialect = DatabaseDialect.POSTGRESQL,
            probe = { _, _, _ -> error("permission denied") },
            executor = { _, _, _ ->
                executorCalls++
                ExecutionTrace(executionStarted = true, executionCompleted = true)
            },
            sourceSchema = sourceSchema,
            targetSchema = targetSchema,
            capturedReport = capturedReport,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = tmpDir.resolve("preserve-pg-throws.json"),
        )
        runner.execute(request) shouldBe 8
        executorCalls shouldBe 0
        val codes = capturedReport.value?.diagnostics?.map { it.code }.orEmpty()
        ("SEQUENCE_PRESERVE_PROBE_FAILED" in codes) shouldBe true
    }

    test("SQLite + --execute + preserveCurrentValue + no helper_table opt-in → exit 8 with OPT_IN_REQUIRED") {
        // 0.9.7 Folge-Slice: SQLite is now in the SequencePreserveStage
        // allowlist; without `--sqlite-named-sequences helper_table`
        // the stage blocks with OPT_IN_REQUIRED instead of the legacy
        // NOT_SUPPORTED_BY_DIALECT.
        var probeCalls = 0
        val capturedReport = PreserveCapturedRef<SchemaMigrateReport?>(null)
        val sourceSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(increment = 5L, preserveCurrentValue = true)),
        )
        val targetSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(increment = 1L, preserveCurrentValue = true)),
        )
        val runner = runnerWith(
            dialect = DatabaseDialect.SQLITE,
            probe = { _, _, _ ->
                probeCalls++
                SequenceCurrentValueProbeResult.Read(value = 1L)
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            sourceSchema = sourceSchema,
            targetSchema = targetSchema,
            capturedReport = capturedReport,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:sqlite:/tmp/test.db",
            dialect = DatabaseDialect.SQLITE,
            execute = true,
            report = tmpDir.resolve("preserve-sqlite.json"),
        )
        runner.execute(request) shouldBe 8
        probeCalls shouldBe 0
        val codes = capturedReport.value?.diagnostics?.map { it.code }.orEmpty()
        ("SEQUENCE_PRESERVE_OPT_IN_REQUIRED" in codes) shouldBe true
    }

    test("SQLite + --execute + preserveCurrentValue + helper_table opt-in → exit 0, follow-up emitted") {
        // 0.9.7 Folge-Slice: with the opt-in, the probe runs and the
        // augmented plan carries the AlterSequenceCurrentValue
        // follow-up. The fake renderer surfaces every op as a
        // statement; the report mirrors the augmented plan.
        val capturedPlan = PreserveCapturedRef<dev.dmigrate.core.diff.migration.DiffResult?>(null)
        var probeCalls = 0
        val sourceSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(increment = 5L, preserveCurrentValue = true)),
        )
        val targetSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(increment = 1L, preserveCurrentValue = true)),
        )
        val runner = runnerWith(
            dialect = DatabaseDialect.SQLITE,
            probe = { _, _, _ ->
                probeCalls++
                SequenceCurrentValueProbeResult.Read(value = 42L)
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            sourceSchema = sourceSchema,
            targetSchema = targetSchema,
            capturedPlan = capturedPlan,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:sqlite:/tmp/test.db",
            dialect = DatabaseDialect.SQLITE,
            execute = true,
            report = tmpDir.resolve("preserve-sqlite-ok.json"),
            sqliteNamedSequences = "helper_table",
        )
        runner.execute(request) shouldBe 0
        probeCalls shouldBe 1
        val followUp = capturedPlan.value!!.operations
            .filterIsInstance<DiffOperation.AlterSequenceCurrentValue>()
            .single()
        followUp.currentValue shouldBe 42L
        followUp.isCalled shouldBe null
    }

    test("No probe wired + --execute + preserveCurrentValue → exit 0, NOT_RUN_POLICY INFO surfaces") {
        val capturedReport = PreserveCapturedRef<SchemaMigrateReport?>(null)
        val sourceSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(increment = 5L, preserveCurrentValue = true)),
        )
        val targetSchema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to SequenceDefinition(increment = 1L, preserveCurrentValue = true)),
        )
        val runner = runnerWith(
            dialect = DatabaseDialect.POSTGRESQL,
            probe = null,
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            sourceSchema = sourceSchema,
            targetSchema = targetSchema,
            capturedReport = capturedReport,
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = tmpDir.resolve("preserve-no-probe.json"),
        )
        runner.execute(request) shouldBe 0
        val codes = capturedReport.value?.diagnostics?.map { it.code }.orEmpty()
        ("SEQUENCE_PRESERVE_NOT_RUN_POLICY" in codes) shouldBe true
    }
})

/** File-local mutable holder so closures can publish values out. */
private class PreserveCapturedRef<T>(@Volatile var value: T)
