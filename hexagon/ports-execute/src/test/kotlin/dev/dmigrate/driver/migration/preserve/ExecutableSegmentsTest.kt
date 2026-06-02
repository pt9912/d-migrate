package dev.dmigrate.driver.migration.preserve

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Atomic-Preserve Phase C / Sub-Slice C.2 contract pin for
 * [ExecutableSegment] and [segmentForExecute]. Property-style
 * invariants (every statement in exactly one segment; concatenation
 * reproduces input) are checked across a representative test matrix
 * — the repo does not depend on kotest-property, so the matrix is
 * spelled out as discrete cases.
 */
class ExecutableSegmentsTest : FunSpec({

    fun stmt(id: String, sql: String = "SELECT 1 -- $id") = MigrationDdlStatement(
        sql = sql,
        operationIds = setOf(id),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    fun stmt(ids: Set<String>, sql: String) = MigrationDdlStatement(
        sql = sql,
        operationIds = ids,
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    val pgSeq = SequenceObjectRef(name = "users_id_seq", dialect = RenameProjectionDialect.POSTGRESQL)
    val pgSeq2 = SequenceObjectRef(name = "orders_id_seq", dialect = RenameProjectionDialect.POSTGRESQL)
    val noopRestore: (SequenceCurrentValueProbeResult.Read) -> List<String> = { emptyList() }

    fun batch(
        protectedIds: List<String> = emptyList(),
        followUpIds: List<String> = emptyList(),
        requests: List<AtomicSequencePreserveRequest> = listOf(
            AtomicSequencePreserveRequest(pgSeq, noopRestore),
        ),
    ) = AtomicSequencePreserveBatch(
        requests = requests,
        protectedOperationIds = protectedIds.map { ProtectedOperationId(it) },
        internalFollowUpIds = followUpIds,
    )

    test("empty input → empty segment list") {
        segmentForExecute(emptyList(), atomicBatch = null) shouldBe emptyList()
        segmentForExecute(emptyList(), atomicBatch = batch(protectedIds = listOf("X"))) shouldBe emptyList()
    }

    test("null batch → single PlainSqlSegment covering every statement") {
        val statements = listOf(stmt("op1"), stmt("op2"), stmt("op3"))
        val segments = segmentForExecute(statements, atomicBatch = null)
        segments shouldHaveSize 1
        val plain = segments.single().shouldBeInstanceOf<PlainSqlSegment>()
        plain.statements shouldContainExactly statements
    }

    test("batch without protectedIds/followUpIds → single PlainSqlSegment (degenerate)") {
        val statements = listOf(stmt("op1"), stmt("op2"))
        val segments = segmentForExecute(statements, atomicBatch = batch())
        segments shouldHaveSize 1
        segments.single().shouldBeInstanceOf<PlainSqlSegment>().statements shouldContainExactly statements
    }

    test("batch IDs that do not match any statement → single PlainSqlSegment") {
        val statements = listOf(stmt("op1"), stmt("op2"))
        val segments = segmentForExecute(
            statements,
            atomicBatch = batch(protectedIds = listOf("missing-op"), followUpIds = listOf("missing-followup")),
        )
        segments shouldHaveSize 1
        segments.single().shouldBeInstanceOf<PlainSqlSegment>().statements shouldContainExactly statements
    }

    test("single atomic statement in middle → [Plain, Atomic, Plain]") {
        val s1 = stmt("plain-a")
        val s2 = stmt("protected-op")
        val s3 = stmt("plain-b")
        val b = batch(protectedIds = listOf("protected-op"))
        val segments = segmentForExecute(listOf(s1, s2, s3), atomicBatch = b)
        segments shouldHaveSize 3
        segments[0].shouldBeInstanceOf<PlainSqlSegment>().statements shouldContainExactly listOf(s1)
        val atomic = segments[1].shouldBeInstanceOf<AtomicPreserveSegment>()
        atomic.batch shouldBe b
        atomic.statements shouldContainExactly listOf(s2)
        segments[2].shouldBeInstanceOf<PlainSqlSegment>().statements shouldContainExactly listOf(s3)
    }

    test("atomic at start → [Atomic, Plain]") {
        val s1 = stmt("protected-op")
        val s2 = stmt("plain-a")
        val s3 = stmt("plain-b")
        val segments = segmentForExecute(
            listOf(s1, s2, s3),
            atomicBatch = batch(protectedIds = listOf("protected-op")),
        )
        segments shouldHaveSize 2
        segments[0].shouldBeInstanceOf<AtomicPreserveSegment>().statements shouldContainExactly listOf(s1)
        segments[1].shouldBeInstanceOf<PlainSqlSegment>().statements shouldContainExactly listOf(s2, s3)
    }

    test("atomic at end → [Plain, Atomic]") {
        val s1 = stmt("plain-a")
        val s2 = stmt("plain-b")
        val s3 = stmt("protected-op")
        val segments = segmentForExecute(
            listOf(s1, s2, s3),
            atomicBatch = batch(protectedIds = listOf("protected-op")),
        )
        segments shouldHaveSize 2
        segments[0].shouldBeInstanceOf<PlainSqlSegment>().statements shouldContainExactly listOf(s1, s2)
        segments[1].shouldBeInstanceOf<AtomicPreserveSegment>().statements shouldContainExactly listOf(s3)
    }

    test("all atomic → single AtomicPreserveSegment, no PlainSqlSegment") {
        val s1 = stmt("protected-op-1")
        val s2 = stmt("protected-op-2")
        val segments = segmentForExecute(
            listOf(s1, s2),
            atomicBatch = batch(protectedIds = listOf("protected-op-1", "protected-op-2")),
        )
        segments shouldHaveSize 1
        segments.single().shouldBeInstanceOf<AtomicPreserveSegment>().statements shouldContainExactly listOf(s1, s2)
    }

    test("internalFollowUpId matches a statement → folded into AtomicPreserveSegment") {
        val s1 = stmt("plain-a")
        val s2 = stmt("protected-op")
        val s3 = stmt("alter-seq-followup")
        val b = batch(
            protectedIds = listOf("protected-op"),
            followUpIds = listOf("alter-seq-followup"),
        )
        val segments = segmentForExecute(listOf(s1, s2, s3), atomicBatch = b)
        segments shouldHaveSize 2
        segments[0].shouldBeInstanceOf<PlainSqlSegment>().statements shouldContainExactly listOf(s1)
        segments[1].shouldBeInstanceOf<AtomicPreserveSegment>().statements shouldContainExactly listOf(s2, s3)
    }

    test("statement with multiple operationIds is atomic if ANY id matches the batch") {
        val s1 = stmt("plain-only")
        val s2 = stmt(ids = setOf("unrelated", "protected-op"), sql = "ALTER … sequence-bearing")
        val s3 = stmt("plain-tail")
        val segments = segmentForExecute(
            listOf(s1, s2, s3),
            atomicBatch = batch(protectedIds = listOf("protected-op")),
        )
        segments shouldHaveSize 3
        segments[1].shouldBeInstanceOf<AtomicPreserveSegment>().statements shouldContainExactly listOf(s2)
    }

    test("multi-sequence batch with contiguous atomic statements → single AtomicPreserveSegment") {
        val s1 = stmt("plain-before")
        val s2 = stmt("protected-op-seq1")
        val s3 = stmt("protected-op-seq2")
        val s4 = stmt("plain-after")
        val multiBatch = batch(
            protectedIds = listOf("protected-op-seq1", "protected-op-seq2"),
            requests = listOf(
                AtomicSequencePreserveRequest(pgSeq, noopRestore),
                AtomicSequencePreserveRequest(pgSeq2, noopRestore),
            ),
        )
        val segments = segmentForExecute(listOf(s1, s2, s3, s4), atomicBatch = multiBatch)
        segments shouldHaveSize 3
        val atomic = segments[1].shouldBeInstanceOf<AtomicPreserveSegment>()
        atomic.batch shouldBe multiBatch
        atomic.batch.requests shouldHaveSize 2
        atomic.statements shouldContainExactly listOf(s2, s3)
    }

    test("plain statement between two atomic statements → IllegalStateException, no silent reorder") {
        val s1 = stmt("protected-op-1")
        val s2 = stmt("plain-interleave")
        val s3 = stmt("protected-op-2")
        val ex = shouldThrow<IllegalStateException> {
            segmentForExecute(
                listOf(s1, s2, s3),
                atomicBatch = batch(protectedIds = listOf("protected-op-1", "protected-op-2")),
            )
        }
        ex.message!!.contains("contiguous atomic statements") shouldBe true
        ex.message!!.contains("planner must group") shouldBe true
    }

    test("Source-of-Truth invariant: across the full matrix every statement appears in exactly one segment") {
        // Property-style coverage as a matrix — no kotest-property.
        // Each scenario is (statements, batch, expectedSegmentKinds).
        val cases: List<Triple<List<MigrationDdlStatement>, AtomicSequencePreserveBatch?, List<String>>> = listOf(
            Triple(
                listOf(stmt("a"), stmt("b"), stmt("c")),
                null,
                listOf("plain"),
            ),
            Triple(
                listOf(stmt("a"), stmt("p")),
                batch(protectedIds = listOf("p")),
                listOf("plain", "atomic"),
            ),
            Triple(
                listOf(stmt("p"), stmt("z")),
                batch(protectedIds = listOf("p")),
                listOf("atomic", "plain"),
            ),
            Triple(
                listOf(stmt("a"), stmt("p"), stmt("z")),
                batch(protectedIds = listOf("p")),
                listOf("plain", "atomic", "plain"),
            ),
            Triple(
                listOf(stmt("p1"), stmt("p2")),
                batch(protectedIds = listOf("p1", "p2")),
                listOf("atomic"),
            ),
            Triple(
                listOf(stmt("a"), stmt("p"), stmt("f"), stmt("z")),
                batch(protectedIds = listOf("p"), followUpIds = listOf("f")),
                listOf("plain", "atomic", "plain"),
            ),
        )
        cases.forEach { (statements, b, expectedKinds) ->
            val segments = segmentForExecute(statements, atomicBatch = b)
            withClue(statements, b, expectedKinds) {
                segments.map { if (it is AtomicPreserveSegment) "atomic" else "plain" } shouldBe expectedKinds
                // Invariant 1: concatenation reproduces input in order.
                segments.flatMap { it.statements } shouldContainExactly statements
                // Invariant 2: every statement is in exactly one segment.
                val perStatementCount = statements.associateWith { s ->
                    segments.count { it.statements.contains(s) }
                }
                perStatementCount.forEach { (s, count) ->
                    count shouldBe 1
                    // Sanity: identity-based, not equality on the data class.
                    s shouldBe s
                }
                // Invariant 3: at most one AtomicPreserveSegment.
                segments.count { it is AtomicPreserveSegment } shouldBe expectedKinds.count { it == "atomic" }
            }
        }
    }
})

private inline fun withClue(
    statements: List<MigrationDdlStatement>,
    atomicBatch: AtomicSequencePreserveBatch?,
    expectedKinds: List<String>,
    block: () -> Unit,
) {
    io.kotest.assertions.withClue(
        "statements=${statements.map { it.operationIds }} " +
            "batch=${atomicBatch?.let { it.protectedOperationIds.map { p -> p.value } to it.internalFollowUpIds }} " +
            "expected=$expectedKinds",
        block,
    )
}
