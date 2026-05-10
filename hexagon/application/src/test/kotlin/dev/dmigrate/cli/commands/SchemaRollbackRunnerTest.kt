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
        recovery: Boolean = false,
        postUpVerified: Boolean = false,
        allowedPostUpFingerprints: List<String>? = null,
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
            recovery = recovery,
            postUpVerified = postUpVerified,
            allowedPostUpFingerprints = allowedPostUpFingerprints,
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

    test("F.5.i — recovery artefact with multi-FP allowedPostUpFingerprints accepts a matching observed state") {
        // Plan §F.5.i / §7.1 (Z. 1466-1470): recovery artefacts
        // (`recovery=true`) carry a non-empty `allowedPostUpFingerprints`
        // whitelist instead of a single `postUpFingerprint`. The rollback
        // runner's `verifyTargetMatchesArtefact` (wired since E.5) accepts
        // ANY observed state whose fingerprint is in the whitelist. F.5.i
        // is the confirmation test — the runner-side wiring is already
        // there; we just pin the contract end-to-end with a multi-FP
        // artefact.
        val schemaA = SchemaDefinition(
            name = "A",
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
        val schemaB = SchemaDefinition(
            name = "B",
            version = "1",
            tables = mapOf(
                "orders" to dev.dmigrate.core.model.TableDefinition(
                    columns = mapOf(
                        "id" to dev.dmigrate.core.model.ColumnDefinition(
                            dev.dmigrate.core.model.NeutralType.BigInteger,
                        ),
                    ),
                ),
            ),
        )
        val fpA = MigrationFingerprint.compute(schemaA)
        val fpB = MigrationFingerprint.compute(schemaB)
        val artefactPath = writeArtefact(
            "f5i-multi-fp.sql",
            buildArtefact(
                postUpFp = fpA,        // value still present per format, but ignored when recovery=true
                recovery = true,
                postUpVerified = true,
                allowedPostUpFingerprints = listOf(fpA, fpB),
            ),
        )
        val executorCalls = mutableListOf<List<MigrationDdlStatement>>()
        // dbLoader returns schemaB — the SECOND entry in the whitelist —
        // so the test pins that ANY whitelist member is acceptable, not
        // just the first.
        val runner = SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = schemaB,
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

    test("F.5.i — recovery artefact rejects observed state outside the allowedPostUpFingerprints whitelist") {
        // Negative half: the same recovery artefact with whitelist
        // [fpA, fpB] MUST reject a target whose fingerprint is neither.
        // Pins the SchemaRollbackRunner.verifyTargetMatchesArtefact's
        // fallback to TARGET_STATE_MISMATCH (exit 8) for recovery
        // artefacts, mirroring the non-recovery happy-path.
        val schemaA = SchemaDefinition(
            name = "A",
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
        val schemaB = SchemaDefinition(
            name = "B",
            version = "1",
            tables = mapOf(
                "orders" to dev.dmigrate.core.model.TableDefinition(
                    columns = mapOf(
                        "id" to dev.dmigrate.core.model.ColumnDefinition(
                            dev.dmigrate.core.model.NeutralType.BigInteger,
                        ),
                    ),
                ),
            ),
        )
        val schemaC = SchemaDefinition(
            name = "C",
            version = "1",
            tables = mapOf(
                "products" to dev.dmigrate.core.model.TableDefinition(
                    columns = mapOf(
                        "sku" to dev.dmigrate.core.model.ColumnDefinition(
                            dev.dmigrate.core.model.NeutralType.Text(),
                        ),
                    ),
                ),
            ),
        )
        val fpA = MigrationFingerprint.compute(schemaA)
        val fpB = MigrationFingerprint.compute(schemaB)
        val artefactPath = writeArtefact(
            "f5i-reject.sql",
            buildArtefact(
                postUpFp = fpA,
                recovery = true,
                postUpVerified = true,
                allowedPostUpFingerprints = listOf(fpA, fpB),
            ),
        )
        val executorCalls = mutableListOf<List<MigrationDdlStatement>>()
        val runner = SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    // schemaC fingerprint is NOT in the whitelist.
                    schema = schemaC,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            executor = { _, statements, _ ->
                executorCalls += statements
                ExecutionTrace(executionStarted = true, executionCompleted = true)
            },
            printError = { _, _ -> },
        )
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:postgres://localhost",
            execute = true,
        )
        runner.execute(request) shouldBe 8
        // Executor must NOT have been called when state-check fails.
        executorCalls shouldBe emptyList()
    }
})
