package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.MigrationFingerprint
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

    test("F.5.b — clean post-compare pins observed Post-Up-Fingerprint into the rollback artefact") {
        // Plan §F.5.b / §10 acceptance: the rollback metadata block must
        // carry the OBSERVED Post-Up content fingerprint (not the planned
        // desired one) so that schema rollback's TARGET_STATE_MISMATCH
        // check verifies the same bytes that actually live in the DB.
        val capture = mutableMapOf<String, String>()
        val schema = schemaWithTable("orders")
        val expectedObservedFingerprint = MigrationFingerprint.compute(schema)
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schema, validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
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
        val reportPath = tmpDir.resolve("e4-f5b-report.json")
        val rollbackPath = tmpDir.resolve("e4-f5b-rollback.sql")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            generateRollback = true,
            rollbackOutput = rollbackPath,
            report = reportPath,
        )
        runner.execute(request) shouldBe 0
        val artefactText = capture["wrote:$rollbackPath"]
            ?: error("rollback artefact was not written")
        artefactText shouldContain "\"postUpFingerprint\":\"$expectedObservedFingerprint\""
        artefactText shouldContain "\"postUpVerified\":true"
        // `recovery=false` in the happy path — recovery emission is F.5.e/f.
        artefactText shouldContain "\"recovery\":false"
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

    test("F.5.c — happy path report carries upExecuted=true and rollbackFinalized=true") {
        val capture = mutableMapOf<String, String>()
        val schema = schemaWithTable("orders")
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schema, validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
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
            renderReport = { r, _ ->
                "{\"upExecuted\":${r.execution?.upExecuted},\"rollbackFinalized\":${r.execution?.rollbackFinalized}}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val reportPath = tmpDir.resolve("e4-f5c-ok-report.json")
        val rollbackPath = tmpDir.resolve("e4-f5c-ok-rollback.sql")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            generateRollback = true,
            rollbackOutput = rollbackPath,
            report = reportPath,
        )
        runner.execute(request) shouldBe 0
        val report = capture["wrote:$reportPath"]
            ?: error("report was not written")
        report shouldContain "\"upExecuted\":true"
        report shouldContain "\"rollbackFinalized\":true"
    }

    test("F.5.c — drift path report carries upExecuted=true and rollbackFinalized=false") {
        // Side-effect signal per Plan §10: Up landed in the DB (executor
        // succeeded, no rollback) but the rollback artefact could not be
        // finalised because the post-compare detected drift.
        val capture = mutableMapOf<String, String>()
        val desired = schemaWithTable("orders")
        val drifted = SchemaDefinition(name = "App", version = "1")
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = desired, validation = ValidationResult())
            },
            dbLoader = { op, _ ->
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
            renderReport = { r, _ ->
                "{\"upExecuted\":${r.execution?.upExecuted},\"rollbackFinalized\":${r.execution?.rollbackFinalized}}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val reportPath = tmpDir.resolve("e4-f5c-drift-report.json")
        val rollbackPath = tmpDir.resolve("e4-f5c-drift-rollback.sql")
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
        capture.containsKey("wrote:$rollbackPath") shouldBe false
        val report = capture["wrote:$reportPath"]
            ?: error("report was not written")
        report shouldContain "\"upExecuted\":true"
        report shouldContain "\"rollbackFinalized\":false"
    }

    test("F.5.e — post-introspection failure emits a recovery artefact with desiredFp + recovery=true") {
        // Plan §F.5.e: Up landed in the DB (executor returned cleanly) but
        // the post-introspection dbLoader call threw. The runner MUST NOT
        // overwrite the user's --rollback-output, but MAY write a separate
        // recovery artefact at <rollbackOutput>.recovery.<ts>.rollback.sql
        // with `recovery=true`, `allowedPostUpFingerprints=[desiredFp]`,
        // `postUpVerified=false`. Exit stays 5; report carries
        // `upExecuted=true`, `rollbackFinalized=false`.
        val capture = mutableMapOf<String, String>()
        val schema = schemaWithTable("orders")
        var loadCallNo = 0
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schema, validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                loadCallNo++
                if (loadCallNo == 1) {
                    // Pre-execute: returns the original (current) state.
                    ResolvedSchemaOperand(
                        reference = "db:${op.source}",
                        schema = schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.POSTGRESQL,
                    )
                } else {
                    // Post-execute: introspection failure (e.g. driver crash,
                    // network drop right after Up). Triggers F.5.e.
                    error("simulated post-introspection failure")
                }
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
            renderReport = { r, _ ->
                "{\"upExecuted\":${r.execution?.upExecuted},\"rollbackFinalized\":${r.execution?.rollbackFinalized}}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
            // Pin the timestamp so the recovery filename is deterministic.
            clock = java.time.Clock.fixed(
                java.time.Instant.parse("2026-05-10T14:30:45Z"),
                java.time.ZoneOffset.UTC,
            ),
        )
        val reportPath = tmpDir.resolve("e4-f5e-report.json")
        val rollbackPath = tmpDir.resolve("e4-f5e-rollback.sql")
        val expectedRecoveryPath = tmpDir.resolve("e4-f5e-rollback.sql.recovery.20260510T143045Z.rollback.sql")
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
        // The user's --rollback-output is NEVER overwritten under F.5.e.
        capture.containsKey("wrote:$rollbackPath") shouldBe false
        // The recovery artefact lives at the spec-prescribed path.
        val recoveryArtefact = capture["wrote:$expectedRecoveryPath"]
            ?: error("recovery artefact not written; capture keys = ${capture.keys}")
        // Recovery contract: the metadata block sets recovery=true and pins
        // the desiredFp as the only allowed FP (no observed FP available).
        val expectedDesiredFp = MigrationFingerprint.compute(schema)
        recoveryArtefact shouldContain "\"recovery\":true"
        recoveryArtefact shouldContain "\"allowedPostUpFingerprints\":[\"$expectedDesiredFp\"]"
        recoveryArtefact shouldContain "\"postUpVerified\":false"
        // Report still reflects the side-effect signal.
        val report = capture["wrote:$reportPath"]
            ?: error("report was not written")
        report shouldContain "\"upExecuted\":true"
        report shouldContain "\"rollbackFinalized\":false"
    }

    test("F.5.f — write-fail of --rollback-output after clean compare emits recovery with observedFp") {
        // Plan §F.5.f: Up landed, post-compare clean (observed == desired),
        // but the atomic write of the user-requested --rollback-output
        // failed (FS race / permission denied / etc.). The runner MUST
        // emit a recovery artefact at <output>.recovery.<ts>.rollback.sql
        // pinned to the OBSERVED Post-Up-Fingerprint with
        // `postUpVerified=true`. Exit 7 (write-fail), report carries
        // `upExecuted=true`, `rollbackFinalized=false`.
        val capture = mutableMapOf<String, String>()
        val schema = schemaWithTable("orders")
        val expectedObservedFingerprint = MigrationFingerprint.compute(schema)
        val rollbackPath = tmpDir.resolve("e4-f5f-rollback.sql")
        val expectedRecoveryPath = tmpDir.resolve("e4-f5f-rollback.sql.recovery.20260510T143045Z.rollback.sql")
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schema, validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
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
            executor = { _, _, _ ->
                ExecutionTrace(executionStarted = true, executionCompleted = true, statementsAttempted = 1)
            },
            atomicWriter = { p, c ->
                if (p == rollbackPath) {
                    // Simulate the FS race / permission failure that triggers F.5.f.
                    error("simulated atomic-write failure on --rollback-output")
                }
                capture["wrote:$p"] = c
                Files.writeString(p, c)
            },
            renderReport = { r, _ ->
                "{\"upExecuted\":${r.execution?.upExecuted},\"rollbackFinalized\":${r.execution?.rollbackFinalized}}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
            clock = java.time.Clock.fixed(
                java.time.Instant.parse("2026-05-10T14:30:45Z"),
                java.time.ZoneOffset.UTC,
            ),
        )
        val reportPath = tmpDir.resolve("e4-f5f-report.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            generateRollback = true,
            rollbackOutput = rollbackPath,
            report = reportPath,
        )
        // Atomic write failure → Exit 7 per finalize contract.
        runner.execute(request) shouldBe 7
        // The user's --rollback-output was never finalised (the atomicWriter
        // throw means the try-block in writeRollbackArtefact returned false
        // before `capture["wrote:$rollbackPath"]` was populated).
        capture.containsKey("wrote:$rollbackPath") shouldBe false
        // The recovery artefact lives at the spec-prescribed path with the
        // OBSERVED Post-Up-Fingerprint pinned (postUpVerified=true because
        // the post-compare succeeded before the write-fail).
        val recoveryArtefact = capture["wrote:$expectedRecoveryPath"]
            ?: error("recovery artefact not written; capture keys = ${capture.keys}")
        recoveryArtefact shouldContain "\"recovery\":true"
        recoveryArtefact shouldContain "\"allowedPostUpFingerprints\":[\"$expectedObservedFingerprint\"]"
        recoveryArtefact shouldContain "\"postUpVerified\":true"
        // Side-effect signal in the report.
        val report = capture["wrote:$reportPath"]
            ?: error("report was not written")
        report shouldContain "\"upExecuted\":true"
        report shouldContain "\"rollbackFinalized\":false"
    }

})
