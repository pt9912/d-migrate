package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class SchemaMigrateRunnerTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-runner-test")

    fun fakeRendered(opCount: Int = 1): MigrationDdlResult {
        val opIds = (1..opCount).map { "op-$it" }.toSet()
        return MigrationDdlResult(
            statements = listOf(
                MigrationDdlStatement(
                    sql = "CREATE TABLE x (...);",
                    operationIds = opIds,
                    risk = dev.dmigrate.core.diff.migration.OperationRisk.SAFE,
                    phase = dev.dmigrate.core.diff.migration.DiffPhase.TABLES,
                ),
            ),
            operationsRendered = opIds,
        )
    }

    /** Builds a SchemaDefinition that yields exactly one CreateTable op. */
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

    val sourcePath = tmpDir.resolve("source.yaml")
    val targetPath = tmpDir.resolve("target.yaml")
    Files.writeString(sourcePath, "# source")
    Files.writeString(targetPath, "# target")

    fun captureRunner(
        sourceSchema: SchemaDefinition = schemaWithTable("orders"),
        targetSchema: SchemaDefinition = SchemaDefinition(name = "App", version = "1"),
        sourceValidation: ValidationResult = ValidationResult(),
        targetValidation: ValidationResult = ValidationResult(),
        renderedFor: (DatabaseDialect) -> MigrationDdlResult = { fakeRendered() },
    ): Pair<SchemaMigrateRunner, MutableMap<String, String>> {
        val capture = mutableMapOf<String, String>()
        val runner = SchemaMigrateRunner(
            fileLoader = { op ->
                val (schema, validation) = if (op.path == sourcePath) {
                    sourceSchema to sourceValidation
                } else {
                    targetSchema to targetValidation
                }
                ResolvedSchemaOperand(
                    reference = "file:${op.path.fileName}",
                    schema = schema,
                    validation = validation,
                )
            },
            normalizer = { it },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            planner = DiffPlanner(),
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        renderedFor(dialect)
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        renderedFor(dialect)
                }
            },
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
            renderReport = { r, _ ->
                "{\"status\":\"${r.status}\",\"exitCode\":${r.exitCode}," +
                    "\"primaryBlockedReason\":\"${r.summary.primaryBlockedReason ?: ""}\"," +
                    "\"blockers\":[${r.blockers.joinToString(",") { "\"${it.reason}\"" }}]}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
            stdout = { capture.merge("stdout", it) { a, b -> "$a\n$b" } },
            stderr = { capture.merge("stderr", it) { a, b -> "$a\n$b" } },
        )
        return runner to capture
    }

    test("file-to-file plan-only emits report and exits 0 on a non-empty plan") {
        val (runner, capture) = captureRunner(
            sourceSchema = schemaWithTable("orders"),
            targetSchema = SchemaDefinition(name = "App", version = "1"),
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
        )
        val exit = runner.execute(request)
        exit shouldBe 0
        capture["stdout"] shouldContain "\"status\":\"ok\""
    }

    test("identical schemas yield no_op and exit 0") {
        val same = schemaWithTable("orders")
        val (runner, capture) = captureRunner(sourceSchema = same, targetSchema = same)
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
        )
        runner.execute(request) shouldBe 0
        capture["stdout"] shouldContain "\"status\":\"no_op\""
    }

    test("destructive Up without --allow-destructive yields exit 8 with primary blocked reason") {
        val (runner, capture) = captureRunner(
            renderedFor = {
                MigrationDdlResult(
                    statements = listOf(
                        MigrationDdlStatement(
                            sql = "DROP TABLE x;",
                            operationIds = setOf("op-drop"),
                            risk = dev.dmigrate.core.diff.migration.OperationRisk(destructive = true),
                            phase = dev.dmigrate.core.diff.migration.DiffPhase.TABLES,
                        ),
                    ),
                    operationsRendered = setOf("op-drop"),
                    destructiveOperations = setOf("op-drop"),
                )
            },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
        )
        runner.execute(request) shouldBe 8
        capture["stdout"] shouldContain "DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION"
    }

    test("--allow-destructive on a destructive plan exits 0") {
        val (runner, _) = captureRunner(
            renderedFor = {
                MigrationDdlResult(
                    statements = listOf(
                        MigrationDdlStatement(
                            sql = "DROP TABLE x;",
                            operationIds = setOf("op-drop"),
                            risk = dev.dmigrate.core.diff.migration.OperationRisk(destructive = true),
                            phase = dev.dmigrate.core.diff.migration.DiffPhase.TABLES,
                        ),
                    ),
                    operationsRendered = setOf("op-drop"),
                    destructiveOperations = setOf("op-drop"),
                )
            },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            allowDestructive = true,
            planOnly = true,
        )
        runner.execute(request) shouldBe 0
    }

    test("renderer-side blockers (DIALECT_UNSUPPORTED) yield exit 8") {
        val (runner, capture) = captureRunner(
            renderedFor = {
                MigrationDdlResult(
                    statements = emptyList(),
                    operationsRendered = emptySet(),
                    operationsSkipped = setOf("op-x"),
                    blockers = listOf(
                        MigrationBlocker(reason = MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION),
                    ),
                    primaryBlockedReason = MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
                )
            },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
        )
        runner.execute(request) shouldBe 8
        capture["stdout"] shouldContain "blocked"
    }

    test("--execute without --report is rejected with exit 2 (audit-trail)") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
        )
        runner.execute(request) shouldBe 2
    }

    test("--generate-rollback without --rollback-output and without --plan-only is exit 2") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            generateRollback = true,
        )
        runner.execute(request) shouldBe 2
    }

    test("--rollback-output without --generate-rollback is exit 2") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            rollbackOutput = tmpDir.resolve("down.sql"),
        )
        runner.execute(request) shouldBe 2
    }

    test("--generate-rollback --plan-only is a capability check; report.summary reflects Down-side") {
        val capture = mutableMapOf<String, String>()
        var capturedDownOptions: DdlGenerationOptions? = null
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                    override fun generateDown(
                        diff: dev.dmigrate.core.diff.migration.DiffResult,
                        options: DdlGenerationOptions,
                    ): MigrationDdlResult {
                        capturedDownOptions = options
                        return fakeRendered()
                    }
                }
            },
            renderReport = { r, _ ->
                "{\"downStatementsTotal\":${r.summary.downStatementsTotal},\"downBlocked\":${r.summary.downBlocked}}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
            stdout = { capture.merge("stdout", it) { a, b -> "$a\n$b" } },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.MYSQL,
            generateRollback = true,
            planOnly = true,
        )
        runner.execute(request) shouldBe 0
        capturedDownOptions?.spatialProfile shouldBe dev.dmigrate.driver.SpatialProfile.NATIVE
        capturedDownOptions?.executionMode shouldBe dev.dmigrate.driver.ExecutionMode.STANDALONE
        capture["stdout"] shouldContain "\"downStatementsTotal\":1"
        capture["stdout"] shouldContain "\"downBlocked\":false"
    }

    test("--generate-rollback writes a v1 metadata block + sql body to --rollback-output") {
        val capture = mutableMapOf<String, String>()
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        MigrationDdlResult(
                            statements = listOf(
                                MigrationDdlStatement(
                                    sql = "DROP TABLE x;",
                                    operationIds = setOf("op-1"),
                                    risk = dev.dmigrate.core.diff.migration.OperationRisk.SAFE,
                                    phase = dev.dmigrate.core.diff.migration.DiffPhase.TABLES,
                                ),
                            ),
                            operationsRendered = setOf("op-1"),
                        )
                }
            },
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
            renderReport = { _, _ -> "{}" },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val outPath = tmpDir.resolve("up-2.sql")
        val downPath = tmpDir.resolve("down-2.sql")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            generateRollback = true,
            output = outPath,
            rollbackOutput = downPath,
        )
        runner.execute(request) shouldBe 0
        val artefact = capture["wrote:$downPath"]!!
        artefact shouldContain "-- d-migrate rollback-sql v1 begin"
        artefact shouldContain "-- d-migrate rollback-sql v1 end"
        artefact shouldContain "\"format\":\"d-migrate rollback-sql\""
        artefact shouldContain "\"formatVersion\":\"v1\""
        artefact shouldContain "\"dialect\":\"POSTGRESQL\""
        artefact shouldContain "\"artifactHashAlgorithm\":\"sha256-rollback-artifact-v1\""
        artefact shouldContain "\"recovery\":false"
        artefact shouldContain "\"postUpVerified\":false"
        artefact shouldContain "DROP TABLE x;"
    }

    test("Down-side blockers (NOT_REVERSIBLE) propagate into combined exit 8") {
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        MigrationDdlResult(
                            statements = emptyList(),
                            operationsRendered = emptySet(),
                            blockers = listOf(
                                MigrationBlocker(reason = MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE),
                            ),
                            primaryBlockedReason = MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE,
                        )
                }
            },
            renderReport = { _, _ -> "{}" },
            printError = { _, _ -> },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            generateRollback = true,
            rollbackOutput = tmpDir.resolve("down-blocked.sql"),
        )
        runner.execute(request) shouldBe 8
    }

    test("--plan-only with --rollback-output is exit 2") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
            rollbackOutput = tmpDir.resolve("down.sql"),
        )
        runner.execute(request) shouldBe 2
    }

    test("--output writes Up-SQL atomically to the requested path") {
        val (runner, capture) = captureRunner()
        val outPath = tmpDir.resolve("up.sql")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            output = outPath,
        )
        runner.execute(request) shouldBe 0
        capture["wrote:$outPath"] shouldContain "CREATE TABLE x"
    }

    test("--report writes structured report to the requested path") {
        val (runner, capture) = captureRunner()
        val reportPath = tmpDir.resolve("report.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            report = reportPath,
        )
        runner.execute(request) shouldBe 0
        capture["wrote:$reportPath"] shouldContain "\"status\":\"ok\""
    }

    test("output path that collides with source/target is rejected exit 2") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            output = sourcePath,
        )
        runner.execute(request) shouldBe 2
    }

    test("validation errors surface as exit 3 with structured report") {
        val (runner, _) = captureRunner(
            sourceValidation = ValidationResult(
                errors = listOf(ValidationError("E001", "bad", "tables.x")),
            ),
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
        )
        runner.execute(request) shouldBe 3
    }

    test("missing renderer for chosen dialect yields exit 2") {
        val (_, capture) = captureRunner()
        val runner = SchemaMigrateRunner(
            fileLoader = { ResolvedSchemaOperand(reference = "file:x", schema = schemaWithTable("o"), validation = ValidationResult()) },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { null },
            renderReport = { r, _ -> r.status },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
        )
        runner.execute(request) shouldBe 2
    }

    test("DB target without a wired dbLoader yields exit 2") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost",
            dialect = DatabaseDialect.POSTGRESQL,
        )
        runner.execute(request) shouldBe 2
    }

    test("DB target with a loader supplies the dialect; --dialect omitted is fine") {
        val capture = mutableMapOf<String, String>()
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:source", schema = schemaWithTable("orders"), validation = ValidationResult())
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
            renderReport = { r, _ -> "{\"dialect\":\"${r.dialect}\"}" },
            printError = { msg, src -> capture["error:$src"] = msg },
            stdout = { capture.merge("stdout", it) { a, b -> "$a\n$b" } },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            // dialect intentionally omitted — derived from DB loader
            planOnly = true,
        )
        runner.execute(request) shouldBe 0
        capture["stdout"] shouldContain "POSTGRESQL"
    }

    test("--dialect mismatch with DB-target dialect yields exit 2 (TARGET_DIALECT_MISMATCH)") {
        val capture = mutableMapOf<String, String>()
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:source", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = SchemaDefinition(name = "App", version = "1"),
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.MYSQL,
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
            renderReport = { _, _ -> "{}" },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:mysql://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
        )
        runner.execute(request) shouldBe 2
        capture.values.any { it.contains("TARGET_DIALECT_MISMATCH") } shouldBe true
    }

    test("DB connection error in dbLoader yields exit 4") {
        val capture = mutableMapOf<String, String>()
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:source", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            dbLoader = { _, _ -> error("connection refused") },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { _ -> null },
            renderReport = { _, _ -> "{}" },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
        )
        runner.execute(request) shouldBe 4
    }

    test("DB config error (CompareConfigException) yields exit 7") {
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:source", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            dbLoader = { _, _ -> throw CompareConfigException("alias unresolved") },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { _ -> null },
            renderReport = { _, _ -> "{}" },
            printError = { _, _ -> },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:dburl://x",
            dialect = DatabaseDialect.POSTGRESQL,
        )
        runner.execute(request) shouldBe 7
    }

    test("File-to-file without --dialect yields exit 2") {
        val (runner, _) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = null,
        )
        runner.execute(request) shouldBe 2
    }

    test("a passing run records expected operation views in the report") {
        val (runner, capture) = captureRunner()
        val reportPath = tmpDir.resolve("report-2.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            report = reportPath,
            planOnly = true,
        )
        runner.execute(request) shouldBe 0
        // Report content goes through our trivial JSON renderer; just check status.
        capture["wrote:$reportPath"] shouldContain "\"status\":\"ok\""
        capture.keys.any { it.startsWith("error:") }.let { it shouldBe false }
    }
})
