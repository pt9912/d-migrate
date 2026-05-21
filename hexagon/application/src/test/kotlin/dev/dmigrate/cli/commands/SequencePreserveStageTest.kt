package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/**
 * 0.9.7 preserve-current-value Sub-Slice D: pins
 * [SequencePreserveStage]'s dispatch logic — skip paths, candidate
 * filter, probe routing, and plan augmentation. Probe adapters are
 * mocked via the [SequenceCurrentValueProbeFn] typealias so this
 * test stays driver-free.
 */
class SequencePreserveStageTest : FunSpec({

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun schemaWithSequence(preserve: Boolean = true) = SchemaDefinition(
        name = "App",
        version = "1",
        sequences = mapOf(
            "order_seq" to SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = preserve),
        ),
    )

    fun synthesisePlan(
        operations: List<DiffOperation>,
        currentSchema: SchemaDefinition = emptySchema(),
        desiredSchema: SchemaDefinition = emptySchema(),
    ): DiffResult = DiffResult(
        current = DiffEndpoint("App", "1", "fp-current"),
        desired = DiffEndpoint("App", "1", "fp-desired"),
        schemaDiff = SchemaDiff(),
        operations = operations,
        currentSchema = currentSchema,
        desiredSchema = desiredSchema,
    )

    fun createSeqOp(name: String = "order_seq", preserve: Boolean = true): DiffOperation.CreateSequence =
        DiffOperation.CreateSequence(
            id = "create:$name",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(name)),
            sequence = SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = preserve),
            renameProvenance = dev.dmigrate.core.diff.migration.RenameProvenance(
                candidateId = "test-$name",
                objectType = DiffObjectType.SEQUENCE,
                fromPath = listOf("old_$name"),
                toPath = listOf(name),
                overlaySource = "test",
                overlayEntryId = "test-entry",
                overlayHash = null,
                fallbackReason = "test-drop-create-fallback",
            ),
        )

    fun createSeqOpWithoutRenameProv(name: String = "order_seq"): DiffOperation.CreateSequence =
        DiffOperation.CreateSequence(
            id = "create:$name",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(name)),
            sequence = SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = true),
        )

    fun alterSeqOp(
        name: String = "order_seq",
        beforePreserve: Boolean = true,
        afterPreserve: Boolean = true,
    ): DiffOperation.AlterSequence = DiffOperation.AlterSequence(
        id = "alter:$name",
        objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(name)),
        before = SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = beforePreserve),
        after = SequenceDefinition(start = 1L, increment = 5L, preserveCurrentValue = afterPreserve),
    )

    fun renameSeqOp(from: String = "old_seq", to: String = "new_seq"): DiffOperation.RenameSequence =
        DiffOperation.RenameSequence(
            id = "rename:$from->$to",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(to)),
            fromName = from,
            toName = to,
            overlaySource = "test",
            overlayEntryId = "test-entry",
            overlayHash = null,
        )

    fun executeRequest() = SchemaMigrateRequest(
        source = "desired.yaml",
        target = "db:mysql://x",
        execute = true,
    )

    fun planOnlyRequest() = SchemaMigrateRequest(
        source = "desired.yaml",
        target = "file:current.yaml",
        planOnly = true,
    )

    fun dbTarget() = CompareOperand.Database("mysql://x")

    fun fileTarget() = CompareOperand.File(Path.of("current.yaml"))

    fun probe(result: SequenceCurrentValueProbeResult): SequenceCurrentValueProbeFn =
        { _, _, _ -> result }

    fun probeByRef(map: Map<String, SequenceCurrentValueProbeResult>): SequenceCurrentValueProbeFn =
        { _, _, ref -> map[ref.name] ?: error("no probe result for ${ref.name}") }

    // ── Skip-paths (§6.4.3) ────────────────────────────────────────────

    test("!request.execute → NotRun, no probe call") {
        var probeCalls = 0
        val outcome = SequencePreserveStage.run(
            probe = { _, _, _ -> probeCalls++; SequenceCurrentValueProbeResult.NotFound },
            request = planOnlyRequest(),
            target = fileTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        outcome shouldBe SequencePreserveStage.Outcome.NotRun
        probeCalls shouldBe 0
    }

    test("execute against file target → NotRun (CLI-Level-Exit-2 catches this earlier)") {
        SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.NotFound),
            request = executeRequest(),
            target = fileTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        ) shouldBe SequencePreserveStage.Outcome.NotRun
    }

    test("SQLite with preserve candidates → Failed(NOT_SUPPORTED_BY_DIALECT) per candidate, no probe") {
        var probeCalls = 0
        val outcome = SequencePreserveStage.run(
            probe = { _, _, _ -> probeCalls++; SequenceCurrentValueProbeResult.NotFound },
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.SQLITE,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Failed>()
        outcome.diagnostics.single().code shouldBe
            "SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT"
        outcome.diagnostics.single().severity shouldBe DiffDiagnostic.Severity.BLOCKER
        probeCalls shouldBe 0
    }

    test("SQLite without preserve candidates → NotRun (no per-op blocker noise)") {
        SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.NotFound),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.SQLITE,
            plan = synthesisePlan(listOf(alterSeqOp(beforePreserve = false, afterPreserve = false))),
        ) shouldBe SequencePreserveStage.Outcome.NotRun
    }

    test("probe == null → Succeeded with NOT_RUN_POLICY INFO per candidate; plan unchanged") {
        val plan = synthesisePlan(listOf(alterSeqOp()))
        val outcome = SequencePreserveStage.run(
            probe = null,
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = plan,
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        outcome.augmentedPlan shouldBe plan
        outcome.infoDiagnostics.single().code shouldBe "SEQUENCE_PRESERVE_NOT_RUN_POLICY"
        outcome.infoDiagnostics.single().severity shouldBe DiffDiagnostic.Severity.INFO
    }

    test("plan without preserve candidates → NotRun (filter rejects DropSequence and preserve=false)") {
        val drop = DiffOperation.DropSequence(
            id = "drop:x",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("x")),
            sequence = SequenceDefinition(start = 1L, preserveCurrentValue = true),
        )
        val alter = alterSeqOp(beforePreserve = false, afterPreserve = false)
        SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.NotFound),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(drop, alter)),
        ) shouldBe SequencePreserveStage.Outcome.NotRun
    }

    // ── Candidate filter (§6.4.1) ──────────────────────────────────────

    test("CreateSequence without renameProvenance → not a probe candidate (NotRun if alone)") {
        SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.NotFound),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(createSeqOpWithoutRenameProv())),
        ) shouldBe SequencePreserveStage.Outcome.NotRun
    }

    test("RenameSequence: source sequence preserve=true → candidate") {
        val rename = renameSeqOp("old_seq", "new_seq")
        val outcome = SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.Read(value = 100L)),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(
                listOf(rename),
                currentSchema = SchemaDefinition(
                    name = "App", version = "1",
                    sequences = mapOf("old_seq" to SequenceDefinition(preserveCurrentValue = true)),
                ),
            ),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        val followUp = outcome.augmentedPlan.operations
            .filterIsInstance<DiffOperation.AlterSequenceCurrentValue>()
            .single()
        followUp.probeSequenceRef.name shouldBe "old_seq"
        followUp.applySequenceRef.name shouldBe "new_seq"
        followUp.revertAfterRename shouldBe true
        followUp.pairId shouldBe "rename:${rename.id}"
    }

    test("RenameSequence: source sequence preserve=false → not a candidate") {
        SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.Read(value = 1L)),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(
                listOf(renameSeqOp("old_seq", "new_seq")),
                currentSchema = SchemaDefinition(
                    name = "App", version = "1",
                    sequences = mapOf("old_seq" to SequenceDefinition(preserveCurrentValue = false)),
                ),
            ),
        ) shouldBe SequencePreserveStage.Outcome.NotRun
    }

    // ── Routing (§6.4.5) ───────────────────────────────────────────────

    test("AlterSequence + Read → FollowUp with currentValue from probe, dependencies on parent") {
        val alter = alterSeqOp()
        val outcome = SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.Read(value = 42L, isCalled = true)),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(alter)),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        val followUp = outcome.augmentedPlan.operations
            .filterIsInstance<DiffOperation.AlterSequenceCurrentValue>()
            .single()
        followUp.currentValue shouldBe 42L
        followUp.isCalled shouldBe true // PG → propagated
        followUp.restoreValue shouldBe 42L
        followUp.restoreIsCalled shouldBe true
        followUp.rollbackImpossible shouldBe false
        followUp.dependencies shouldBe setOf(alter.id)
    }

    test("AlterSequence + Read MySQL → isCalled = null (MySQL helper-table encodes the equivalent)") {
        val outcome = SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.Read(value = 42L, isCalled = null)),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        val followUp = (outcome as SequencePreserveStage.Outcome.Succeeded)
            .augmentedPlan.operations
            .filterIsInstance<DiffOperation.AlterSequenceCurrentValue>()
            .single()
        followUp.isCalled shouldBe null
        followUp.restoreIsCalled shouldBe null
    }

    test("AlterSequence + Read with matchedRows != 1 → Block(PROBE_FAILED)") {
        val outcome = SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.Read(value = 1L, matchedRows = 2)),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Failed>()
        outcome.diagnostics.single().code shouldBe "SEQUENCE_PRESERVE_PROBE_FAILED"
        outcome.diagnostics.single().message shouldContain "2 rows"
    }

    test("AlterSequence + NotFound → Block (deterministic prior state required)") {
        val outcome = SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.NotFound),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Failed>()
        outcome.diagnostics.single().code shouldBe "SEQUENCE_PRESERVE_PROBE_FAILED"
        outcome.diagnostics.single().message shouldContain "NotFound"
    }

    test("CreateSequence + NotFound → Info (no blocker, current-value remains declarative)") {
        val outcome = SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.NotFound),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(createSeqOp())),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        outcome.infoDiagnostics.single().code shouldBe "SEQUENCE_PRESERVE_NOT_FOUND"
        outcome.infoDiagnostics.single().message shouldContain "ROLLBACK_NOT_POSSIBLE"
        outcome.augmentedPlan.operations
            .filterIsInstance<DiffOperation.AlterSequenceCurrentValue>()
            .shouldBeEmpty()
    }

    test("CreateSequence + Read → FollowUp with rollbackImpossible=true (no pre-Up state)") {
        val outcome = SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.Read(value = 50L, isCalled = true)),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(createSeqOp())),
        )
        val followUp = (outcome as SequencePreserveStage.Outcome.Succeeded)
            .augmentedPlan.operations
            .filterIsInstance<DiffOperation.AlterSequenceCurrentValue>()
            .single()
        followUp.rollbackImpossible shouldBe true
        followUp.rollbackImpossibleReason shouldNotBe null
        followUp.restoreValue shouldBe null
        followUp.restoreIsCalled shouldBe null
        followUp.currentValue shouldBe 50L
    }

    test("Failed probe result → Block(PROBE_FAILED) with propagated code in message") {
        val outcome = SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.Failed("PROBE_PERMISSION_DENIED", "no rights")),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Failed>()
        outcome.diagnostics.single().code shouldBe "SEQUENCE_PRESERVE_PROBE_FAILED"
        outcome.diagnostics.single().message shouldContain "PROBE_PERMISSION_DENIED"
        outcome.diagnostics.single().message shouldContain "no rights"
    }

    test("Probe throws → Block(PROBE_FAILED) with exception class + message") {
        val outcome = SequencePreserveStage.run(
            probe = { _, _, _ -> error("connection refused") },
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Failed>()
        outcome.diagnostics.single().code shouldBe "SEQUENCE_PRESERVE_PROBE_FAILED"
        outcome.diagnostics.single().message shouldContain "connection refused"
    }

    // ── Plan augmentation (§6.4.6) ─────────────────────────────────────

    test("Follow-up landet direkt hinter parent-Op im operations stream") {
        // Parent ops: createSeqA (rename-provenance) + an unrelated
        // AddColumn → followup must sit between them.
        val createA = createSeqOp("seq_a")
        val unrelated = DiffOperation.CreateSequence(
            id = "create:unrelated",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("unrelated")),
            sequence = SequenceDefinition(preserveCurrentValue = false),
        )
        val outcome = SequencePreserveStage.run(
            probe = probe(SequenceCurrentValueProbeResult.Read(value = 7L, isCalled = true)),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(createA, unrelated)),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        val ops = outcome.augmentedPlan.operations
        ops.map { it::class.simpleName } shouldBe listOf(
            "CreateSequence",
            "AlterSequenceCurrentValue",
            "CreateSequence",
        )
        ops[1].dependencies shouldBe setOf(createA.id)
    }

    test("Multiple preserve candidates each get their own follow-up positioned behind the parent") {
        val alter = alterSeqOp("seq_a")
        val create = createSeqOp("seq_b")
        val outcome = SequencePreserveStage.run(
            probe = probeByRef(
                mapOf(
                    "seq_a" to SequenceCurrentValueProbeResult.Read(value = 10L, isCalled = true),
                    "seq_b" to SequenceCurrentValueProbeResult.Read(value = 20L, isCalled = false),
                ),
            ),
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(alter, create)),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        outcome.augmentedPlan.operations.map { it::class.simpleName } shouldBe listOf(
            "AlterSequence",
            "AlterSequenceCurrentValue",
            "CreateSequence",
            "AlterSequenceCurrentValue",
        )
        outcome.augmentedPlan.operations[1].dependencies shouldBe setOf(alter.id)
        outcome.augmentedPlan.operations[3].dependencies shouldBe setOf(create.id)
    }

    // ── buildFailureResult ─────────────────────────────────────────────

    test("buildFailureResult groups blockers by classified reason and surfaces them in MigrationDdlResult") {
        val diags = listOf(
            DiffDiagnostic(
                code = "SEQUENCE_PRESERVE_PROBE_FAILED",
                message = "x",
                severity = DiffDiagnostic.Severity.BLOCKER,
            ),
            DiffDiagnostic(
                code = "SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT",
                message = "y",
                severity = DiffDiagnostic.Severity.BLOCKER,
            ),
            DiffDiagnostic(
                code = "SEQUENCE_PRESERVE_NOT_FOUND",
                message = "info — not a blocker",
                severity = DiffDiagnostic.Severity.INFO,
            ),
        )
        val result = SequencePreserveStage.buildFailureResult(diags)
        result.isBlocked shouldBe true
        // Diagnostics flow through unchanged (INFO + BLOCKER).
        result.diagnostics shouldBe diags
        // Blockers grouped by reason: MANUAL_ACTION_REQUIRED +
        // DIALECT_UNSUPPORTED_OPERATION.
        result.blockers.map { it.reason }.toSet() shouldBe setOf(
            MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
        )
    }
})
