package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * E.4 `--execute` slice tests for [SchemaMigrateRunner]. Lives in a
 * separate file from the E.1-E.3 cases so each file stays under
 * Detekt's `LargeClass` threshold.
 */
class SchemaMigrateRunnerExecuteTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-execute-test")

    fun fakeRendered(): MigrationDdlResult {
        return MigrationDdlResult(
            statements = listOf(
                MigrationDdlStatement(
                    sql = "CREATE TABLE x (...);",
                    operationIds = setOf("op-1"),
                    risk = dev.dmigrate.core.diff.migration.OperationRisk.SAFE,
                    phase = dev.dmigrate.core.diff.migration.DiffPhase.TABLES,
                ),
            ),
            operationsRendered = setOf("op-1"),
        )
    }

    fun schemaWithTable(name: String) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            name to dev.dmigrate.core.model.TableDefinition(
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

    val sourcePath = tmpDir.resolve("source.yaml")
    val targetPath = tmpDir.resolve("target.yaml")
    Files.writeString(sourcePath, "# source")
    Files.writeString(targetPath, "# target")

    fun simpleRunner(
        dbLoader: ((CompareOperand.Database, Path?) -> ResolvedSchemaOperand)? = null,
        executor: ExecutorFn? = null,
        capture: MutableMap<String, String> = mutableMapOf(),
        sourceSchema: SchemaDefinition = schemaWithTable("orders"),
    ): Pair<SchemaMigrateRunner, MutableMap<String, String>> {
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = sourceSchema, validation = ValidationResult())
            },
            dbLoader = dbLoader,
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                }
            },
            executor = executor,
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
            renderReport = { r, _ ->
                "{\"started\":${r.execution?.started},\"error\":\"${r.execution?.executionError}\"}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        return runner to capture
    }

    fun captureRunner(): Pair<SchemaMigrateRunner, MutableMap<String, String>> = simpleRunner()

    test("--execute without --report is exit 2") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
        )
        runner.execute(request) shouldBe 2
    }

    test("--execute with file target is exit 2") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = tmpDir.resolve("e4-r.json"),
        )
        runner.execute(request) shouldBe 2
    }

    test("--execute and --plan-only are mutually exclusive (exit 2)") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            planOnly = true,
            report = tmpDir.resolve("e4-r2.json"),
        )
        runner.execute(request) shouldBe 2
    }

    test("--execute with no executor wired returns exit 5 with executionError populated") {
        val capture = mutableMapOf<String, String>()
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = SchemaDefinition(name = "App", version = "1"),
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                }
            },
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
            renderReport = { r, _ ->
                "{\"executionError\":\"${r.execution?.executionError}\"}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = tmpDir.resolve("e4-noexec.json"),
        )
        runner.execute(request) shouldBe 5
    }

    test("--execute with successful executor + clean post-compare yields exit 0") {
        val capture = mutableMapOf<String, String>()
        val schema = schemaWithTable("orders")
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schema, validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    // Post-execute introspection returns the desired schema, so post-compare is clean.
                    schema = schema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                }
            },
            executor = { _, statements, _ ->
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = statements.size,
                )
            },
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
            renderReport = { _, _ -> "{}" },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val reportPath = tmpDir.resolve("e4-ok.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = reportPath,
        )
        runner.execute(request) shouldBe 0
        // The Up-SQL artefact is NOT written when executing.
        capture.keys.none { it.startsWith("wrote:") && it.endsWith(".sql") } shouldBe true
        // Report still gets written.
        capture.keys.any { it == "wrote:$reportPath" } shouldBe true
    }

    test("--execute with executor failure surfaces executionError + exit 5") {
        val capture = mutableMapOf<String, String>()
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = SchemaDefinition(name = "App", version = "1"),
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                }
            },
            executor = { _, _, _ ->
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = 1,
                    lastStatementOperationIds = setOf("op-1"),
                    transactionRolledBack = true,
                    sideEffectsPossible = false,
                    executionError = "syntax error near LINE 1",
                )
            },
            renderReport = { r, _ ->
                "{\"started\":${r.execution?.started},\"error\":\"${r.execution?.executionError}\"}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
        )
        val reportPath = tmpDir.resolve("e4-err.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = reportPath,
        )
        runner.execute(request) shouldBe 5
        val report = capture["wrote:$reportPath"]!!
        report shouldContain "\"started\":true"
        report shouldContain "\"error\":\"syntax error near LINE 1\""
    }

    test("--execute with post-compare drift exits 5 and skips Down-artefact write") {
        val capture = mutableMapOf<String, String>()
        var loadCallNo = 0
        val desired = schemaWithTable("orders")
        val drifted = SchemaDefinition(name = "App", version = "1") // empty — drift vs desired
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = desired, validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                loadCallNo++
                // First call (pre-execute) returns the current state (empty);
                // second call (post-execute) returns drifted state — not equal to desired.
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = drifted,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                }
            },
            executor = { _, _, _ ->
                ExecutionTrace(executionStarted = true, executionCompleted = true, statementsAttempted = 1)
            },
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
            renderReport = { _, _ -> "{}" },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val reportPath = tmpDir.resolve("e4-drift.json")
        val downPath = tmpDir.resolve("e4-drift-down.sql")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            generateRollback = true,
            rollbackOutput = downPath,
            report = reportPath,
        )
        runner.execute(request) shouldBe 5
        // No Down artefact should be written on drift.
        capture.containsKey("wrote:$downPath") shouldBe false
    }






})
