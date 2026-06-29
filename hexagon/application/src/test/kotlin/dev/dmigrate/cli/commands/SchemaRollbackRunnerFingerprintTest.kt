package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * `schema rollback` fingerprint-algorithm verification — split from
 * [SchemaRollbackRunnerTest] (Detekt `LargeClass`). Shares the artefact builders in
 * [buildArtefact]. Pins the guard that an algorithm-version bump (e.g. the ADR 0025
 * fingerprint v5→v6) does not surface as a misleading TARGET_STATE_MISMATCH.
 */
class SchemaRollbackRunnerFingerprintTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("rollback-fingerprint-test")

    fun writeArtefact(name: String, text: String): Path {
        val p = tmpDir.resolve(name)
        Files.writeString(p, text)
        return p
    }

    test("--execute with a stale fingerprintAlgorithm yields ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH, not TARGET_STATE_MISMATCH") {
        // postUpFp matches the target, so state verification WOULD pass — the stale
        // algorithm must short-circuit first with a precise, non-misleading error.
        val matchingSchema = SchemaDefinition(name = "App", version = "1")
        val fp = MigrationFingerprint.compute(matchingSchema)
        val artefactPath = writeArtefact(
            "a-algo.sql",
            buildArtefact(
                currentFp = "fp-pre",
                desiredFp = fp,
                postUpFp = fp,
                fingerprintAlgorithm = "schema-fingerprint-v0-legacy",
            ),
        )
        val errors = mutableListOf<String>()
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
            printError = { msg, _ -> errors += msg },
        )
        val request = SchemaRollbackRequest(
            source = artefactPath,
            target = "db:postgres://localhost",
            execute = true,
        )
        runner.execute(request) shouldBe 8
        errors.any { it.contains("ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH") } shouldBe true
        errors.none { it.contains("TARGET_STATE_MISMATCH") } shouldBe true
    }
})
