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
 * F.5 Recovery-Rollback-Artefakt slice tests for [SchemaMigrateRunner].
 * Lives in a separate file from the E.4 baseline cases so each file
 * stays under Detekt's `LargeClass` threshold (same precedent as the
 * E.1-E.3 vs E.4 split). Helpers (`fakeRendered`, `schemaWithTable`,
 * `tmpDir`, `sourcePath`) are duplicated locally — they are short
 * fixture builders, not behaviour, and copying keeps each spec file
 * self-contained.
 */
class SchemaMigrateRunnerRecoveryTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-recovery-test")

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
    Files.writeString(sourcePath, "# source")

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

    test("F.5.g+j — drift case must NOT auto-emit a recovery artefact (Plan §F.5.g negative)") {
        // Plan §F.5.g / §7.1 (Z. 1461-1465): when the post-compare detects
        // genuine drift (observed != desired), the runner MUST NOT write
        // any auto-executable recovery rollback artefact. The observed
        // state contradicts the Soll, so an executable Down could push the
        // DB into a worse state. Operator must inspect manually.
        //
        // The previous F.5.c-drift test pinned only "--rollback-output not
        // written"; this test additionally pins the matching
        // `.recovery.<timestamp>.rollback.sql` path, which is the file
        // F.5.e/f WOULD have written under the Introspection-Fail and
        // Write-Fail branches. Drift is the only branch where neither
        // path is touched.
        val capture = mutableMapOf<String, String>()
        val desired = schemaWithTable("orders")
        val drifted = SchemaDefinition(name = "App", version = "1") // empty — content drift vs desired
        val rollbackPath = tmpDir.resolve("e4-f5g-rollback.sql")
        val recoveryPath = tmpDir.resolve("e4-f5g-rollback.sql.recovery.20260510T143045Z.rollback.sql")
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
                val observed = MigrationFingerprint.compute(drifted)
                "{\"upExecuted\":${r.execution?.upExecuted}," +
                    "\"rollbackFinalized\":${r.execution?.rollbackFinalized}," +
                    "\"observedFingerprint\":\"$observed\"}"
            },
            printError = { msg, src -> capture["error:$src"] = msg },
            // Pinned clock so the (would-be) recovery path is deterministic.
            clock = java.time.Clock.fixed(
                java.time.Instant.parse("2026-05-10T14:30:45Z"),
                java.time.ZoneOffset.UTC,
            ),
        )
        val reportPath = tmpDir.resolve("e4-f5g-report.json")
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
        // Negative pinning: NEITHER the user --rollback-output NOR the
        // recovery-shaped path is written under drift.
        capture.containsKey("wrote:$rollbackPath") shouldBe false
        capture.containsKey("wrote:$recoveryPath") shouldBe false
        // No file in the same parent that looks recovery-shaped at all.
        capture.keys.filter { it.startsWith("wrote:") && it.contains(".recovery.") } shouldBe emptyList()
        // Report still carries the side-effect signal so an operator can
        // see exactly what happened (Up landed, observed FP captured,
        // rollback NOT finalised).
        val report = capture["wrote:$reportPath"]
            ?: error("report was not written")
        report shouldContain "\"upExecuted\":true"
        report shouldContain "\"rollbackFinalized\":false"
        report shouldContain "\"observedFingerprint\":\"${MigrationFingerprint.compute(drifted)}\""
    }

    test("F.5.h — recovery-write failure after introspection-fail elevates Exit 5 → Exit 7") {
        // Plan §F.5.h / §7.1 (Z. 1156-1159): Up landed, post-introspection
        // failed (F.5.e trigger), AND the recovery artefact write at the
        // .recovery.<ts>.rollback.sql path also fails (FS race, no atomic-
        // replace in the target dir, …). Exit code MUST escalate from the
        // baseline Exit 5 (introspection-fail) to Exit 7 (local I/O error
        // after a side effect), and the user-facing error must clearly
        // surface the side-effect signal so an operator playbook can
        // distinguish "post-compare drift, untouched FS" from "post-
        // compare drift, recovery I/O dead too".
        val capture = mutableMapOf<String, String>()
        val schema = schemaWithTable("orders")
        var loadCallNo = 0
        val rollbackPath = tmpDir.resolve("e4-f5h-rollback.sql")
        val recoveryPath = tmpDir.resolve("e4-f5h-rollback.sql.recovery.20260510T143045Z.rollback.sql")
        val runner = SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "file:src", schema = schema, validation = ValidationResult())
            },
            dbLoader = { op, _ ->
                loadCallNo++
                if (loadCallNo == 1) {
                    ResolvedSchemaOperand(
                        reference = "db:${op.source}",
                        schema = schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.POSTGRESQL,
                    )
                } else {
                    error("simulated post-introspection failure (F.5.e trigger)")
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
            atomicWriter = { p, c ->
                if (p == recoveryPath) {
                    // Layer the F.5.h failure on top of the F.5.e
                    // introspection-fail trigger: even the recovery write
                    // can't land.
                    error("simulated atomic-write failure on recovery path")
                }
                capture["wrote:$p"] = c
                Files.writeString(p, c)
            },
            renderReport = { r, _ ->
                "{\"upExecuted\":${r.execution?.upExecuted},\"rollbackFinalized\":${r.execution?.rollbackFinalized}}"
            },
            printError = { msg, src ->
                // Multi-error sink so we can pin BOTH the I/O message and
                // the side-effect-signal message that F.5.h adds.
                capture["error:$src:${capture.count { (k, _) -> k.startsWith("error:$src") }}"] = msg
            },
            clock = java.time.Clock.fixed(
                java.time.Instant.parse("2026-05-10T14:30:45Z"),
                java.time.ZoneOffset.UTC,
            ),
        )
        val reportPath = tmpDir.resolve("e4-f5h-report.json")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            generateRollback = true,
            rollbackOutput = rollbackPath,
            report = reportPath,
        )
        // Exit 7 (not 5) per the F.5.h elevation contract.
        runner.execute(request) shouldBe 7
        // Neither the user --rollback-output nor the recovery path was finalised.
        capture.containsKey("wrote:$rollbackPath") shouldBe false
        capture.containsKey("wrote:$recoveryPath") shouldBe false
        // Two stderr messages: the I/O failure cause + the F.5.h-required
        // "Up was executed; manual database recovery may be required" hint.
        val errorMessages = capture.entries.filter { it.key.startsWith("error:") }.map { it.value }
        errorMessages.any { it.contains("Failed to write recovery rollback artefact") } shouldBe true
        errorMessages.any { it.contains("Up was executed against the target") } shouldBe true
        errorMessages.any { it.contains("manual database recovery") } shouldBe true
        // Report still carries the structured side-effect signal.
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
