package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class SchemaRollbackRunnerTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("rollback-runner-test")

    fun stmt(sql: String) = MigrationDdlStatement(
        sql = sql,
        operationIds = setOf("op-1"),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    fun buildArtefact(
        dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL,
        currentFp: String = "fp-current",
        desiredFp: String = "fp-desired",
        postUpFp: String = "fp-desired",
        sql: String = "DROP TABLE x;",
        destructive: Boolean = false,
    ): String = RollbackArtefactBuilder.build(
        RollbackArtefactBuilder.Input(
            dialect = dialect,
            currentFingerprint = currentFp,
            desiredFingerprint = desiredFp,
            postUpFingerprint = postUpFp,
            operationIds = setOf("op-1"),
            risk = RollbackArtefactBuilder.Risk(
                destructive = destructive,
                dataLossPossible = false,
                requiresManualConfirmation = false,
                operationIds = setOf("op-1"),
            ),
            downStatements = listOf(stmt(sql)),
            createdByVersion = "test/0.0.0",
        ),
    )

    fun writeArtefact(name: String, text: String): Path {
        val p = tmpDir.resolve(name)
        Files.writeString(p, text)
        return p
    }

    test("dry-run path with valid artefact exits 0 (parse + hash verification only)") {
        val artefactPath = writeArtefact("a.sql", buildArtefact())
        val capture = mutableMapOf<String, String>()
        val runner = SchemaRollbackRunner(
            printError = { msg, src -> capture["error:$src"] = msg },
        )
        val request = SchemaRollbackRequest(source = artefactPath, target = "db:postgres://localhost")
        runner.execute(request) shouldBe 0
    }

    test("invalid artefact (corrupt hash) exits 7 before any DB access") {
        val good = buildArtefact()
        val tampered = good.replace("DROP TABLE x;", "DROP TABLE y;")
        val artefactPath = writeArtefact("tampered.sql", tampered)
        val runner = SchemaRollbackRunner(printError = { _, _ -> })
        val request = SchemaRollbackRequest(source = artefactPath, target = "db:postgres://localhost")
        runner.execute(request) shouldBe 7
    }

    test("--target must be a DB connection (file rejected with exit 2)") {
        val artefactPath = writeArtefact("a2.sql", buildArtefact())
        val runner = SchemaRollbackRunner(printError = { _, _ -> })
        val request = SchemaRollbackRequest(source = artefactPath, target = tmpDir.resolve("db.yaml").toString())
        runner.execute(request) shouldBe 2
    }

    test("--execute and --dry-run are mutually exclusive (exit 2)") {
        val artefactPath = writeArtefact("a3.sql", buildArtefact())
        val runner = SchemaRollbackRunner(printError = { _, _ -> })
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:postgres://localhost",
            execute = true,
            dryRun = true,
        )
        runner.execute(request) shouldBe 2
    }

    test("--execute without dbLoader is exit 2") {
        val artefactPath = writeArtefact("a4.sql", buildArtefact())
        val runner = SchemaRollbackRunner(
            executor = { _, _, _ -> ExecutionTrace(executionStarted = false, executionCompleted = false) },
            printError = { _, _ -> },
        )
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:postgres://localhost",
            execute = true,
        )
        runner.execute(request) shouldBe 2
    }

    test("--execute with target dialect mismatch yields TARGET_DIALECT_MISMATCH (exit 8)") {
        val artefactPath = writeArtefact("a5.sql", buildArtefact(dialect = DatabaseDialect.POSTGRESQL))
        val runner = SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = SchemaDefinition(name = "App", version = "1"),
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.MYSQL,
                )
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = false, executionCompleted = false) },
            printError = { _, _ -> },
        )
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:mysql://localhost",
            execute = true,
        )
        runner.execute(request) shouldBe 8
    }

    test("--execute with target state drift yields TARGET_STATE_MISMATCH (exit 8)") {
        // Build an artefact whose postUpFingerprint matches the *desired* schema's fingerprint;
        // the runner re-fingerprints whatever the dbLoader returns. Fingerprint a content-
        // different schema in the loader to force drift. (Schema name/version are NOT part
        // of the fingerprint — drift must be content-level, e.g. a table that doesn't exist.)
        val desiredSchema = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf(
                "users" to dev.dmigrate.core.model.TableDefinition(
                    columns = mapOf(
                        "id" to dev.dmigrate.core.model.ColumnDefinition(
                            dev.dmigrate.core.model.NeutralType.Integer,
                        ),
                    ),
                ),
            ),
        )
        val desiredFp = MigrationFingerprint.compute(desiredSchema)
        val artefactPath = writeArtefact(
            "a6.sql",
            buildArtefact(currentFp = "fp-old", desiredFp = "fp-new", postUpFp = desiredFp),
        )
        val driftedSchema = SchemaDefinition(name = "Drift", version = "1")
        val runner = SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = driftedSchema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = false, executionCompleted = false) },
            printError = { _, _ -> },
        )
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:postgres://localhost",
            execute = true,
        )
        runner.execute(request) shouldBe 8
    }

    test("destructive Down without --allow-destructive yields exit 8") {
        val matchingSchema = SchemaDefinition(name = "App", version = "1")
        val fp = MigrationFingerprint.compute(matchingSchema)
        val artefactPath = writeArtefact(
            "a7.sql",
            buildArtefact(currentFp = "fp-pre", desiredFp = fp, postUpFp = fp, destructive = true),
        )
        val runner = SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = matchingSchema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = false, executionCompleted = false) },
            printError = { _, _ -> },
        )
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:postgres://localhost",
            execute = true,
        )
        runner.execute(request) shouldBe 8
    }

    test("--execute with valid artefact + matching state runs the executor and exits 0") {
        val matchingSchema = SchemaDefinition(name = "App", version = "1")
        val fp = MigrationFingerprint.compute(matchingSchema)
        val artefactPath = writeArtefact(
            "a8.sql",
            buildArtefact(currentFp = "fp-pre", desiredFp = fp, postUpFp = fp),
        )
        val executorCalls = mutableListOf<List<MigrationDdlStatement>>()
        val runner = SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = matchingSchema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            executor = { _, statements, _ ->
                executorCalls += statements
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = statements.size,
                )
            },
            printError = { _, _ -> },
        )
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:postgres://localhost",
            execute = true,
        )
        runner.execute(request) shouldBe 0
        executorCalls.size shouldBe 1
        executorCalls.single().single().sql shouldBe "DROP TABLE x;"
    }

    test("--execute with executor error returns exit 5") {
        val matchingSchema = SchemaDefinition(name = "App", version = "1")
        val fp = MigrationFingerprint.compute(matchingSchema)
        val artefactPath = writeArtefact(
            "a9.sql",
            buildArtefact(currentFp = "fp-pre", desiredFp = fp, postUpFp = fp),
        )
        val runner = SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = matchingSchema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            executor = { _, _, _ ->
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = 1,
                    executionError = "syntax error",
                )
            },
            printError = { _, _ -> },
        )
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:postgres://localhost",
            execute = true,
        )
        runner.execute(request) shouldBe 5
    }

    test("--execute with connection failure in dbLoader is exit 4") {
        val artefactPath = writeArtefact("a10.sql", buildArtefact())
        val runner = SchemaRollbackRunner(
            dbLoader = { _, _ -> error("connection refused") },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = false, executionCompleted = false) },
            printError = { _, _ -> },
        )
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:postgres://localhost",
            execute = true,
        )
        runner.execute(request) shouldBe 4
    }

    test("artefact-read failure is exit 7") {
        val runner = SchemaRollbackRunner(printError = { _, _ -> })
        val request = SchemaRollbackRequest(
            source = tmpDir.resolve("does-not-exist.sql"),
            target = "db:postgres://localhost",
        )
        runner.execute(request) shouldBe 7
    }
})
