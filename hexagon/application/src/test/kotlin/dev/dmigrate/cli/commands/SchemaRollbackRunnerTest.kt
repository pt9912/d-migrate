package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionScope
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

    // ── Plan-2 §G.1 transitional fallback: splitArtefactBody scope inference ──
    //
    // `rollback-sql v1` does not carry per-statement TransactionScope.
    // SchemaRollbackRunner.splitArtefactBody stamps the field from a
    // leading-BEGIN sniff so SQLite-rebuild rollback round-trips still
    // dispatch as stream-owned. These tests pin the inference until §G.2
    // replaces the splitter with structured serialization.

    fun runWithBody(body: String): List<MigrationDdlStatement> {
        val matchingSchema = SchemaDefinition(name = "App", version = "1")
        val fp = MigrationFingerprint.compute(matchingSchema)
        val artefactPath = writeArtefact(
            "scope-${body.hashCode()}.sql",
            buildArtefact(currentFp = "fp-pre", desiredFp = fp, postUpFp = fp, sql = body),
        )
        val captured = mutableListOf<MigrationDdlStatement>()
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
                captured += statements
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = statements.size,
                )
            },
            printError = { _, _ -> },
        )
        runner.execute(
            SchemaRollbackRequest(
                source = artefactPath,
                target = "db:postgres://localhost",
                execute = true,
            ),
        ) shouldBe 0
        return captured
    }

    test("splitArtefactBody — body with BEGIN IMMEDIATE stamps every statement STREAM_OWNED") {
        val statements = runWithBody("BEGIN IMMEDIATE;\n\nCREATE TABLE x (id INT);\n\nCOMMIT;")
        statements.map { it.transactionScope } shouldBe listOf(
            TransactionScope.STREAM_OWNED,
            TransactionScope.STREAM_OWNED,
            TransactionScope.STREAM_OWNED,
        )
    }

    test("splitArtefactBody — bare BEGIN; alone is detected") {
        val statements = runWithBody("BEGIN;")
        statements.single().transactionScope shouldBe TransactionScope.STREAM_OWNED
    }

    test("splitArtefactBody — BEGIN TRANSACTION/DEFERRED/EXCLUSIVE variants are detected") {
        listOf("BEGIN TRANSACTION;", "BEGIN DEFERRED;", "BEGIN EXCLUSIVE;").forEach { begin ->
            val statements = runWithBody("$begin\n\nCREATE TABLE t (id INT);\n\nCOMMIT;")
            statements.first().transactionScope shouldBe TransactionScope.STREAM_OWNED
        }
    }

    test("splitArtefactBody — leading whitespace before BEGIN is detected") {
        val statements = runWithBody("  \t  BEGIN IMMEDIATE;\n\nCREATE TABLE t (id INT);")
        statements.first().transactionScope shouldBe TransactionScope.STREAM_OWNED
    }

    test("splitArtefactBody — lowercase begin is detected (case-insensitive)") {
        val statements = runWithBody("begin transaction;\n\ncreate table t (id int);")
        statements.first().transactionScope shouldBe TransactionScope.STREAM_OWNED
    }

    test("splitArtefactBody — pure DDL body without BEGIN stays RUNNER_OWNED") {
        val statements = runWithBody("DROP TABLE x;\n\nALTER TABLE y ADD COLUMN z TEXT;")
        statements.map { it.transactionScope } shouldBe listOf(
            TransactionScope.RUNNER_OWNED,
            TransactionScope.RUNNER_OWNED,
        )
    }

    test("splitArtefactBody — false-prefix BEGINNING_OF_TIME stays RUNNER_OWNED") {
        // Defensive: the token check is BEGIN-token, not BEGIN-substring.
        val statements = runWithBody("ALTER TABLE x ADD COLUMN BEGINNING_OF_TIME TIMESTAMPTZ;")
        statements.single().transactionScope shouldBe TransactionScope.RUNNER_OWNED
    }

    test("splitArtefactBody — column named begin_time stays RUNNER_OWNED") {
        // The trimmed first token of the statement is ALTER, not BEGIN —
        // a column named begin_time must not flip the inference.
        val statements = runWithBody("ALTER TABLE events ADD COLUMN begin_time TIMESTAMPTZ;")
        statements.single().transactionScope shouldBe TransactionScope.RUNNER_OWNED
    }
})
