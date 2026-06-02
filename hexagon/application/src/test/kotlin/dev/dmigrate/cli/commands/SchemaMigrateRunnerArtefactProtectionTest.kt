package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.SqliteCastPreflightStatus
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * F.6.a — Atomic-Writer-Edge-Cases per Plan §F.6:
 *
 * "Render-/Blocker-/Execution-Fehler duerfen bestehende Zielpfade
 * nicht ueberschreiben."
 *
 * The runner's protection is structural — failure paths in
 * `finalize` short-circuit before the artefact-write helpers fire.
 * `defaultAtomicWriter` itself adds an additional layer of
 * temp-file-rename atomicity, but F.6.a is about the runner's
 * **control flow**: pre-existing `--output` / `--rollback-output`
 * files must remain bit-for-bit unchanged after a failed run, and
 * the atomicWriter must NEVER even be called for those paths in
 * the failure modes covered here.
 *
 * `--report` is intentionally outside this contract — the runner
 * always finalises a report (success or failure) so the structured
 * signal reaches the operator.
 */
class SchemaMigrateRunnerArtefactProtectionTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-artefact-protection")

    val sourcePath = tmpDir.resolve("source.yaml")
    Files.writeString(sourcePath, "# source")

    fun fakeRendered(): MigrationDdlResult = MigrationDdlResult(
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

    fun fakeRenderedDestructive(): MigrationDdlResult = MigrationDdlResult(
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

    fun fakeBlocked(): MigrationDdlResult = MigrationDdlResult(
        statements = emptyList(),
        operationsRendered = emptySet(),
        blockers = listOf(
            MigrationBlocker(
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                operationIds = setOf("op-1"),
            ),
        ),
        primaryBlockedReason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
    )

    fun fakeSqliteCastPreflight(operationId: String, status: SqliteCastPreflightStatus) =
        SqliteCastPreflightDeclaration(
            operationId = operationId,
            table = "orders",
            column = "amount",
            sourceType = "TEXT",
            targetType = "INTEGER",
            status = status,
            sqlHash = "f".repeat(64),
        )

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

    /**
     * Helper: run `runner.execute(request)` with both paths
     * pre-populated and assert that neither was touched. The
     * `--report` path is also pre-populated but explicitly NOT
     * asserted-untouched — see class KDoc.
     */
    fun assertArtefactsUntouched(
        rollbackPath: Path,
        outputPath: Path?,
        runner: SchemaMigrateRunner,
        request: SchemaMigrateRequest,
        expectedExit: Int,
    ): MutableMap<String, String> {
        val rollbackOriginal = "PRE-EXISTING ROLLBACK BYTES — MUST NOT BE OVERWRITTEN\n"
        val outputOriginal = "PRE-EXISTING UP-SQL BYTES — MUST NOT BE OVERWRITTEN\n"
        Files.writeString(rollbackPath, rollbackOriginal)
        outputPath?.let { Files.writeString(it, outputOriginal) }
        val capture = mutableMapOf<String, String>()
        // Replace the runner's atomicWriter via a fresh runner; can't
        // override after construction. Caller passes a runner already
        // wired with a capturing writer to this helper — use the
        // pre-existing-content check on disk as the SOURCE OF TRUTH.
        runner.execute(request) shouldBe expectedExit
        Files.readString(rollbackPath) shouldBe rollbackOriginal
        outputPath?.let { Files.readString(it) shouldBe outputOriginal }
        return capture
    }

    test("F.6.a — execute-error leaves pre-existing --rollback-output untouched") {
        val rollbackPath = tmpDir.resolve("e6a-exec-err-rollback.sql")
        val reportPath = tmpDir.resolve("e6a-exec-err-report.json")
        val original = "PRE-EXISTING ROLLBACK BYTES — MUST NOT BE OVERWRITTEN\n"
        Files.writeString(rollbackPath, original)
        val capture = mutableMapOf<String, Int>()
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
            executor = { _, _, _, _, _ ->
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = 1,
                    transactionRolledBack = true,
                    sideEffectsPossible = false,
                    executionError = "syntax error",
                )
            },
            atomicWriter = { p, c ->
                capture.merge("wrote:$p", 1) { a, b -> a + b }
                Files.writeString(p, c)
            },
            renderReport = { _, _ -> "{}" },
            printError = { _, _ -> },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            generateRollback = true,
            rollbackOutput = rollbackPath,
            report = reportPath,
        )
        runner.execute(request) shouldBe 5
        // Disk-level invariant: rollback file's content unchanged.
        Files.readString(rollbackPath) shouldBe original
        // Control-flow invariant: atomicWriter was NEVER called for the
        // rollback path.
        (capture["wrote:$rollbackPath"] ?: 0) shouldBe 0
    }

    test("F.6.a — blocker (destructive without --allow-destructive) leaves pre-existing --rollback-output AND --output untouched") {
        val outputPath = tmpDir.resolve("e6a-blocker-output.sql")
        val rollbackPath = tmpDir.resolve("e6a-blocker-rollback.sql")
        val reportPath = tmpDir.resolve("e6a-blocker-report.json")
        val outputOriginal = "PRE-EXISTING UP-SQL\n"
        val rollbackOriginal = "PRE-EXISTING ROLLBACK\n"
        Files.writeString(outputPath, outputOriginal)
        Files.writeString(rollbackPath, rollbackOriginal)
        val capture = mutableMapOf<String, Int>()
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
                        fakeRenderedDestructive()
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                }
            },
            atomicWriter = { p, c ->
                capture.merge("wrote:$p", 1) { a, b -> a + b }
                Files.writeString(p, c)
            },
            renderReport = { _, _ -> "{}" },
            printError = { _, _ -> },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "file:${tmpDir.resolve("ignored-target.yaml")}",
            dialect = DatabaseDialect.POSTGRESQL,
            output = outputPath,
            generateRollback = true,
            rollbackOutput = rollbackPath,
            report = reportPath,
            // No --allow-destructive → destructive guard injects a blocker.
        )
        runner.execute(request) shouldBe 8
        // Both pre-existing artefact files unchanged.
        Files.readString(outputPath) shouldBe outputOriginal
        Files.readString(rollbackPath) shouldBe rollbackOriginal
        // Atomic writer was never asked to touch them.
        (capture["wrote:$outputPath"] ?: 0) shouldBe 0
        (capture["wrote:$rollbackPath"] ?: 0) shouldBe 0
    }

    test("F.6.a — render blocker (Down rendering blocked) leaves pre-existing --output AND --rollback-output untouched") {
        val outputPath = tmpDir.resolve("e6a-down-blocked-output.sql")
        val rollbackPath = tmpDir.resolve("e6a-down-blocked-rollback.sql")
        val reportPath = tmpDir.resolve("e6a-down-blocked-report.json")
        val outputOriginal = "PRE-EXISTING UP-SQL\n"
        val rollbackOriginal = "PRE-EXISTING ROLLBACK\n"
        Files.writeString(outputPath, outputOriginal)
        Files.writeString(rollbackPath, rollbackOriginal)
        val capture = mutableMapOf<String, Int>()
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            dbLoader = null,
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered()
                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        // Down render returns blockers — runner short-circuits the
                        // whole pipeline (see mergeDownIntoUp / finalize).
                        fakeBlocked()
                }
            },
            atomicWriter = { p, c ->
                capture.merge("wrote:$p", 1) { a, b -> a + b }
                Files.writeString(p, c)
            },
            renderReport = { _, _ -> "{}" },
            printError = { _, _ -> },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "file:${tmpDir.resolve("ignored-target.yaml")}",
            dialect = DatabaseDialect.POSTGRESQL,
            output = outputPath,
            generateRollback = true,
            rollbackOutput = rollbackPath,
            report = reportPath,
        )
        runner.execute(request) shouldBe 8
        Files.readString(outputPath) shouldBe outputOriginal
        Files.readString(rollbackPath) shouldBe rollbackOriginal
        (capture["wrote:$outputPath"] ?: 0) shouldBe 0
        (capture["wrote:$rollbackPath"] ?: 0) shouldBe 0
    }

    test("B.2 rollback rendering keeps down-side SQLite cast preflights in report") {
        val reportPath = tmpDir.resolve("b2-down-cast-preflight-report.json")
        var report: SchemaMigrateReport? = null
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schemaWithTable("orders"), validation = ValidationResult())
            },
            dbLoader = null,
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered().copy(
                            sqliteCastPreflights = listOf(
                                fakeSqliteCastPreflight("op-up", SqliteCastPreflightStatus.NOT_RUN_FILE_TARGET),
                            ),
                        )

                    override fun generateDown(diff: dev.dmigrate.core.diff.migration.DiffResult, options: DdlGenerationOptions) =
                        fakeRendered().copy(
                            sqliteCastPreflights = listOf(
                                fakeSqliteCastPreflight("op-down", SqliteCastPreflightStatus.NOT_RUN_FILE_TARGET),
                            ),
                        )
                }
            },
            renderReport = { r, _ ->
                report = r
                "{}"
            },
            printError = { _, _ -> },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "file:${tmpDir.resolve("ignored-target.yaml")}",
            dialect = DatabaseDialect.SQLITE,
            planOnly = true,
            generateRollback = true,
            report = reportPath,
        )

        runner.execute(request) shouldBe 0
        report!!.sqliteCastPreflights.map { it.operationId } shouldBe listOf("op-up", "op-down")
    }

    test("F.6.a — validation failure (invalid source schema) leaves pre-existing --output AND --rollback-output untouched") {
        val outputPath = tmpDir.resolve("e6a-validation-output.sql")
        val rollbackPath = tmpDir.resolve("e6a-validation-rollback.sql")
        val reportPath = tmpDir.resolve("e6a-validation-report.json")
        val outputOriginal = "PRE-EXISTING UP-SQL\n"
        val rollbackOriginal = "PRE-EXISTING ROLLBACK\n"
        Files.writeString(outputPath, outputOriginal)
        Files.writeString(rollbackPath, rollbackOriginal)
        val capture = mutableMapOf<String, Int>()
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                // Validation result reports an error → emitValidationFailure
                // exits 3 before any artefact rendering.
                ResolvedSchemaOperand(
                    reference = "file:src",
                    schema = schemaWithTable("orders"),
                    validation = ValidationResult(
                        errors = listOf(
                            dev.dmigrate.core.validation.ValidationError(
                                code = "TEST_INVALID",
                                message = "test-injected validation error",
                                objectPath = "users",
                            ),
                        ),
                    ),
                )
            },
            dbLoader = null,
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
            atomicWriter = { p, c ->
                capture.merge("wrote:$p", 1) { a, b -> a + b }
                Files.writeString(p, c)
            },
            renderReport = { _, _ -> "{}" },
            printError = { _, _ -> },
        )
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "file:${tmpDir.resolve("ignored-target.yaml")}",
            dialect = DatabaseDialect.POSTGRESQL,
            output = outputPath,
            generateRollback = true,
            rollbackOutput = rollbackPath,
            report = reportPath,
        )
        runner.execute(request) shouldBe 3
        Files.readString(outputPath) shouldBe outputOriginal
        Files.readString(rollbackPath) shouldBe rollbackOriginal
        (capture["wrote:$outputPath"] ?: 0) shouldBe 0
        (capture["wrote:$rollbackPath"] ?: 0) shouldBe 0
    }
})
