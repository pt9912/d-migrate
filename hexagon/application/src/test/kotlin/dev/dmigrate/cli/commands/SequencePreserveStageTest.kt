package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProvenance
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.SequenceCapability
import dev.dmigrate.driver.SequenceCapabilityDefaults
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/**
 * Atomic-Preserve Phase C.1 (2026-06-01): pins the atomic-batch-
 * building behaviour of [SequencePreserveStage]. Probe-driven test
 * cases from the 0.9.7 path are gone — the atomic executor runs the
 * probe inside the lock at execute time, not at Stage time.
 *
 * Test buckets:
 *
 * - **Skip paths**: file-target, non-DB target, !execute,
 *   dialect-not-allowlisted, SQLite-without-helper_table.
 * - **Candidate classification**: CreateSequence-with-renameProvenance,
 *   AlterSequence (either side opts in), RenameSequence (source opts
 *   in), DropSequence intentionally not a candidate.
 * - **Atomic batch**: requests carry the [SequenceObjectRef]s,
 *   protectedOperationIds carry the kind names,
 *   internalFollowUpIds carry the synthetic follow-up op IDs.
 * - **Audit follow-up**: [DiffOperation.AlterSequenceCurrentValue] is
 *   appended behind each parent op with the sentinel current-value
 *   + rollbackImpossible=true.
 * - **Capability gate**: a candidate kind outside the dialect's
 *   `transactionalProtectedSequenceOperations` set blocks with
 *   `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` — verified via a synthetic
 *   capability overlay (default allowlist accepts all three kinds).
 */
class SequencePreserveStageTest : FunSpec({

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun schemaWithSequence(name: String = "order_seq", preserve: Boolean = true) = SchemaDefinition(
        name = "App",
        version = "1",
        sequences = mapOf(
            name to SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = preserve),
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
            renameProvenance = RenameProvenance(
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

    fun executeRequest(sqliteNamedSequences: String? = null) = SchemaMigrateRequest(
        source = "desired.yaml",
        target = "db:mysql://x",
        execute = true,
        sqliteNamedSequences = sqliteNamedSequences,
    )

    fun planOnlyRequest() = SchemaMigrateRequest(
        source = "desired.yaml",
        target = "file:current.yaml",
        planOnly = true,
    )

    fun dbTarget() = CompareOperand.Database("mysql://x")
    fun fileTarget() = CompareOperand.File(Path.of("current.yaml"))

    // ── Skip-paths ─────────────────────────────────────────────────────

    test("file target with preserve candidates → REQUIRES_DB_TARGET blocker") {
        val outcome = SequencePreserveStage.run(
            request = planOnlyRequest(),
            target = fileTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Failed>()
        outcome.diagnostics.single().code shouldBe "SEQUENCE_PRESERVE_REQUIRES_DB_TARGET"
        outcome.diagnostics.single().severity shouldBe DiffDiagnostic.Severity.BLOCKER
    }

    test("file target without preserve candidates → NotRun (no noise)") {
        SequencePreserveStage.run(
            request = planOnlyRequest(),
            target = fileTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp(beforePreserve = false, afterPreserve = false))),
        ) shouldBe SequencePreserveStage.Outcome.NotRun
    }

    test("DB target + !request.execute → NotRun regardless of candidates") {
        val outcome = SequencePreserveStage.run(
            request = SchemaMigrateRequest(
                source = "desired.yaml",
                target = "db:mysql://x",
                planOnly = true,
            ),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        outcome shouldBe SequencePreserveStage.Outcome.NotRun
    }

    test("DB target + execute + no candidates → NotRun (no follow-ups, no batch)") {
        SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp(beforePreserve = false, afterPreserve = false))),
        ) shouldBe SequencePreserveStage.Outcome.NotRun
    }

    test("SQLite without --sqlite-named-sequences helper_table → OPT_IN_REQUIRED blocker") {
        val outcome = SequencePreserveStage.run(
            request = executeRequest(sqliteNamedSequences = null),
            target = dbTarget(),
            dialect = DatabaseDialect.SQLITE,
            plan = synthesisePlan(
                listOf(alterSeqOp()),
                currentSchema = schemaWithSequence(),
            ),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Failed>()
        outcome.diagnostics.single().code shouldBe "SEQUENCE_PRESERVE_OPT_IN_REQUIRED"
    }

    test("SQLite with --sqlite-named-sequences helper_table → batch is built") {
        val outcome = SequencePreserveStage.run(
            request = executeRequest(sqliteNamedSequences = "helper_table"),
            target = dbTarget(),
            dialect = DatabaseDialect.SQLITE,
            plan = synthesisePlan(
                listOf(alterSeqOp()),
                currentSchema = schemaWithSequence(),
            ),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        outcome.atomicBatch.requests.size shouldBe 1
    }

    // ── Candidate classification ───────────────────────────────────────

    test("DropSequence is never a candidate (§6.4.1)") {
        SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(
                listOf(
                    DiffOperation.DropSequence(
                        id = "drop:order_seq",
                        objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("order_seq")),
                        sequence = SequenceDefinition(
                            start = 1L,
                            increment = 1L,
                            preserveCurrentValue = true,
                        ),
                    ),
                ),
            ),
        ) shouldBe SequencePreserveStage.Outcome.NotRun
    }

    test("CreateSequence without renameProvenance is not a candidate") {
        SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(createSeqOpWithoutRenameProv())),
        ) shouldBe SequencePreserveStage.Outcome.NotRun
    }

    test("CreateSequence with renameProvenance is a candidate") {
        val outcome = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(createSeqOp())),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        outcome.atomicBatch.requests.size shouldBe 1
    }

    test("AlterSequence is a candidate when EITHER side has preserveCurrentValue=true") {
        listOf(
            alterSeqOp(beforePreserve = true, afterPreserve = false) to true,
            alterSeqOp(beforePreserve = false, afterPreserve = true) to true,
            alterSeqOp(beforePreserve = false, afterPreserve = false) to false,
        ).forEach { (op, expectCandidate) ->
            val outcome = SequencePreserveStage.run(
                request = executeRequest(),
                target = dbTarget(),
                dialect = DatabaseDialect.MYSQL,
                plan = synthesisePlan(listOf(op)),
            )
            if (expectCandidate) {
                outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
            } else {
                outcome shouldBe SequencePreserveStage.Outcome.NotRun
            }
        }
    }

    test("RenameSequence is a candidate only when the SOURCE sequence carries preserveCurrentValue=true") {
        val opPreserve = renameSeqOp(from = "old_seq", to = "new_seq")
        val outcomePreserve = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(
                listOf(opPreserve),
                currentSchema = schemaWithSequence(name = "old_seq", preserve = true),
            ),
        )
        outcomePreserve.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        outcomePreserve.atomicBatch.requests.single().sequenceRef.name shouldBe "new_seq"

        val outcomeNoPreserve = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(
                listOf(renameSeqOp(from = "old_seq2", to = "new_seq2")),
                currentSchema = schemaWithSequence(name = "old_seq2", preserve = false),
            ),
        )
        outcomeNoPreserve shouldBe SequencePreserveStage.Outcome.NotRun
    }

    // ── Atomic batch shape ─────────────────────────────────────────────

    test("multi-sequence plan → batch carries one request + protectedOperationId per kind + followUpId per op") {
        val plan = synthesisePlan(
            listOf(
                alterSeqOp(name = "seq_a"),
                alterSeqOp(name = "seq_b"),
                createSeqOp(name = "seq_c"),
            ),
            currentSchema = schemaWithSequence("seq_a"),
        )
        val outcome = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = plan,
        )
        val succeeded = outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        succeeded.atomicBatch.requests.map { it.sequenceRef.name } shouldBe listOf("seq_a", "seq_b", "seq_c")
        succeeded.atomicBatch.protectedOperationIds.map { it.value } shouldContain "AlterSequence"
        succeeded.atomicBatch.protectedOperationIds.map { it.value } shouldContain "CreateSequence"
        succeeded.atomicBatch.protectedOperationIds.map { it.value }.size shouldBe 2
        succeeded.atomicBatch.internalFollowUpIds.size shouldBe 3
        // Each follow-up op-id ends in :preserve and references the parent op-id.
        succeeded.atomicBatch.internalFollowUpIds.forEach { id ->
            id.endsWith(":preserve") shouldBe true
        }
    }

    test("augmented plan inserts AlterSequenceCurrentValue follow-up directly behind each parent op") {
        val parentOps = listOf(alterSeqOp(name = "x"), alterSeqOp(name = "y"))
        val outcome = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(parentOps),
        )
        val succeeded = outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        val ops = succeeded.augmentedPlan.operations
        ops.size shouldBe 4
        ops[0].id shouldBe "alter:x"
        ops[1].id shouldBe "alter:x:preserve"
        ops[2].id shouldBe "alter:y"
        ops[3].id shouldBe "alter:y:preserve"
    }

    test("audit follow-up carries the sentinel current-value and rollbackImpossible=true") {
        val outcome = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        val succeeded = outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
        val followUp = succeeded.augmentedPlan.operations
            .filterIsInstance<DiffOperation.AlterSequenceCurrentValue>()
            .single()
        followUp.currentValue shouldBe
            DiffOperation.AlterSequenceCurrentValue.ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE
        followUp.isCalled shouldBe true // PG safety default
        followUp.restoreValue shouldBe null
        followUp.rollbackImpossible shouldBe true
        followUp.rollbackImpossibleReason!! shouldContain "Atomic-preserve"
    }

    test("audit follow-up on MySQL leaves isCalled null (MySQL helper-table semantics do not carry is_called)") {
        val outcome = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.MYSQL,
            plan = synthesisePlan(listOf(alterSeqOp())),
        )
        val followUp = (outcome as SequencePreserveStage.Outcome.Succeeded).augmentedPlan.operations
            .filterIsInstance<DiffOperation.AlterSequenceCurrentValue>()
            .single()
        followUp.isCalled shouldBe null
    }

    test("renderRestore closure builds dialect-correct SQL when invoked with a probe result") {
        // Stage doesn't probe at Stage time, but it stores
        // renderRestore closures the atomic executor calls inside the
        // lock. Exercising the closure here pins the SQL shape per
        // dialect without standing up a real DB.
        val outcome = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(alterSeqOp(name = "users_id_seq"))),
        )
        val request = (outcome as SequencePreserveStage.Outcome.Succeeded).atomicBatch.requests.single()
        val sql = request.renderRestore(
            dev.dmigrate.driver.SequenceCurrentValueProbeResult.Read(value = 99L, isCalled = true),
        )
        sql.size shouldBe 1
        sql.single() shouldContain "setval('users_id_seq', 99, true)"
    }

    // ── Capability gate ────────────────────────────────────────────────

    test("candidate kind outside the dialect's allowlist → SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED blocker") {
        // Inject a synthetic capability that only allows `AlterSequence`
        // — a `CreateSequence` candidate must then surface
        // SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED instead of silently
        // skipping or falling through to a non-atomic path.
        val outcome = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(createSeqOp())),
            capabilityResolver = { dialect ->
                SequenceCapabilityDefaults.forDialect(dialect).copy(
                    transactionalProtectedSequenceOperations = setOf(
                        ProtectedOperationId("AlterSequence"),
                    ),
                )
            },
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Failed>()
        outcome.diagnostics.single().code shouldBe "SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED"
        outcome.diagnostics.single().severity shouldBe DiffDiagnostic.Severity.BLOCKER
        outcome.diagnostics.single().message shouldContain "CreateSequence"
    }

    test("empty allowlist blocks every candidate") {
        val outcome = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(listOf(alterSeqOp(name = "a"), alterSeqOp(name = "b"))),
            capabilityResolver = { dialect ->
                SequenceCapabilityDefaults.forDialect(dialect).copy(
                    transactionalProtectedSequenceOperations = emptySet<ProtectedOperationId>(),
                )
            },
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Failed>()
        outcome.diagnostics.size shouldBe 2
        outcome.diagnostics.all { it.code == "SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED" } shouldBe true
    }

    test("default capability resolver accepts the three Stage candidate kinds (no gate blocker on production allowlist)") {
        val outcome = SequencePreserveStage.run(
            request = executeRequest(),
            target = dbTarget(),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = synthesisePlan(
                listOf(
                    alterSeqOp(name = "a"),
                    createSeqOp(name = "b"),
                    renameSeqOp(from = "c_from", to = "c_to"),
                ),
                currentSchema = schemaWithSequence(name = "c_from"),
            ),
        )
        outcome.shouldBeInstanceOf<SequencePreserveStage.Outcome.Succeeded>()
    }
})
