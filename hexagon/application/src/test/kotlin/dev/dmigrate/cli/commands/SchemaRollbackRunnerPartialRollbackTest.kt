package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class SchemaRollbackRunnerPartialRollbackTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("rollback-partial-test")

    fun stmt(sql: String) = MigrationDdlStatement(
        sql = sql,
        operationIds = setOf("op-1"),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    fun buildPartialArtefact(postUpFp: String = "fp-desired"): String =
        RollbackArtefactBuilder.build(
            RollbackArtefactBuilder.Input(
                dialect = DatabaseDialect.POSTGRESQL,
                currentFingerprint = "fp-current",
                desiredFingerprint = postUpFp,
                postUpFingerprint = postUpFp,
                operationIds = setOf("op-1"),
                risk = RollbackArtefactBuilder.Risk(
                    destructive = false,
                    dataLossPossible = false,
                    requiresManualConfirmation = false,
                    operationIds = setOf("op-1"),
                ),
                downStatements = listOf(stmt("DROP TABLE x;")),
                createdByVersion = "test/0.0.0",
                partialRollback = true,
                skippedOperationIds = setOf("op-manual"),
            ),
        )

    fun writeArtefact(name: String, text: String): Path {
        val p = tmpDir.resolve(name)
        Files.writeString(p, text)
        return p
    }

    test("F.3 partial rollback execute blocks without explicit allow flag before DB load") {
        val artefactPath = writeArtefact("partial.sql", buildPartialArtefact())
        val loaderCalls = mutableListOf<CompareOperand.Database>()
        val errors = mutableListOf<String>()
        val runner = SchemaRollbackRunner(
            dbLoader = { op, _ ->
                loaderCalls += op
                error("DB loader must not run for unapproved partial rollback")
            },
            executor = { _, _, _ -> error("executor must not run") },
            printError = { msg, _ -> errors += msg },
        )

        runner.execute(
            SchemaRollbackRequest(
                source = artefactPath,
                target = "db:postgres://localhost",
                execute = true,
            ),
        ) shouldBe 8
        loaderCalls.shouldBeEmpty()
        errors.single().contains("--allow-partial-rollback") shouldBe true
    }

    test("F.3 partial rollback execute proceeds with explicit allow flag") {
        val matchingSchema = SchemaDefinition(name = "App", version = "1")
        val fp = MigrationFingerprint.compute(matchingSchema)
        val artefactPath = writeArtefact("partial-allowed.sql", buildPartialArtefact(postUpFp = fp))
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
                allowPartialRollback = true,
            ),
        ) shouldBe 0
        captured.single().sql shouldBe "DROP TABLE x;"
    }
})
