package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Types

/**
 * State-Maschine-Vertragstests für [TableImportSession] (M1).
 *
 * Verwendet einen minimalen [StubTableImportSession], der die
 * State-Transitions als In-Memory-Enum verwaltet, ohne JDBC-Abhängigkeit.
 */
class TableImportSessionContractTest : FunSpec({

    fun testChunk(rowCount: Int = 2): DataChunk = DataChunk(
        table = "test_table",
        columns = listOf(ColumnDescriptor("id", false)),
        rows = (1..rowCount).map { arrayOf<Any?>(it) },
        chunkIndex = 0,
    )

    test("initial state is OPEN") {
        val session = StubTableImportSession()
        session.state shouldBe StubTableImportSession.State.OPEN
    }

    test("OPEN - write - WRITTEN") {
        val session = StubTableImportSession()
        val result = session.write(testChunk())
        session.state shouldBe StubTableImportSession.State.WRITTEN
        result.rowsInserted shouldBe 2
    }

    test("WRITTEN - commitChunk - OPEN") {
        val session = StubTableImportSession()
        session.write(testChunk())
        session.commitChunk()
        session.state shouldBe StubTableImportSession.State.OPEN
    }

    test("WRITTEN - rollbackChunk - OPEN") {
        val session = StubTableImportSession()
        session.write(testChunk())
        session.rollbackChunk()
        session.state shouldBe StubTableImportSession.State.OPEN
    }

    test("double write without commit throws") {
        val session = StubTableImportSession()
        session.write(testChunk())
        shouldThrow<IllegalStateException> {
            session.write(testChunk())
        }
    }

    test("commitChunk from OPEN throws") {
        val session = StubTableImportSession()
        shouldThrow<IllegalStateException> {
            session.commitChunk()
        }
    }

    test("rollbackChunk from OPEN throws") {
        val session = StubTableImportSession()
        shouldThrow<IllegalStateException> {
            session.rollbackChunk()
        }
    }

    test("OPEN - finishTable - FINISHED") {
        val session = StubTableImportSession()
        val result = session.finishTable()
        session.state shouldBe StubTableImportSession.State.FINISHED
        result shouldBe FinishTableResult.Success(emptyList())
    }

    test("finishTable from WRITTEN throws") {
        val session = StubTableImportSession()
        session.write(testChunk())
        shouldThrow<IllegalStateException> {
            session.finishTable()
        }
    }

    test("FINISHED - close - CLOSED") {
        val session = StubTableImportSession()
        session.finishTable()
        session.close()
        session.state shouldBe StubTableImportSession.State.CLOSED
    }

    test("close is idempotent") {
        val session = StubTableImportSession()
        session.close()
        session.close()
        session.state shouldBe StubTableImportSession.State.CLOSED
    }

    test("write after close throws") {
        val session = StubTableImportSession()
        session.close()
        shouldThrow<IllegalStateException> {
            session.write(testChunk())
        }
    }

    test("commitChunk after close throws") {
        val session = StubTableImportSession()
        session.close()
        shouldThrow<IllegalStateException> {
            session.commitChunk()
        }
    }

    test("finishTable after close throws") {
        val session = StubTableImportSession()
        session.close()
        shouldThrow<IllegalStateException> {
            session.finishTable()
        }
    }

    test("markTruncatePerformed from OPEN") {
        val session = StubTableImportSession()
        session.markTruncatePerformed()
        session.state shouldBe StubTableImportSession.State.OPEN
        session.truncatePerformed shouldBe true
        // idempotent
        session.markTruncatePerformed()
        session.truncatePerformed shouldBe true
    }

    test("markTruncatePerformed after write throws") {
        val session = StubTableImportSession()
        session.write(testChunk())
        shouldThrow<IllegalStateException> {
            session.markTruncatePerformed()
        }
    }

    test("FAILED state - only close allowed") {
        val session = StubTableImportSession(commitWillThrow = true)
        session.write(testChunk())
        shouldThrow<RuntimeException> {
            session.commitChunk()
        }
        session.state shouldBe StubTableImportSession.State.FAILED
        session.close()
        session.state shouldBe StubTableImportSession.State.CLOSED
    }

    test("write from FAILED throws") {
        val session = StubTableImportSession(commitWillThrow = true)
        session.write(testChunk())
        shouldThrow<RuntimeException> { session.commitChunk() }
        shouldThrow<IllegalStateException> {
            session.write(testChunk())
        }
    }

    test("finishTable from FAILED throws") {
        val session = StubTableImportSession(commitWillThrow = true)
        session.write(testChunk())
        shouldThrow<RuntimeException> { session.commitChunk() }
        shouldThrow<IllegalStateException> {
            session.finishTable()
        }
    }

    test("markTruncatePerformed from FAILED throws") {
        val session = StubTableImportSession(commitWillThrow = true)
        session.write(testChunk())
        shouldThrow<RuntimeException> { session.commitChunk() }
        shouldThrow<IllegalStateException> {
            session.markTruncatePerformed()
        }
    }

    test("write-commit-write-commit-finish cycle") {
        val session = StubTableImportSession()
        val r1 = session.write(testChunk(3))
        r1.rowsInserted shouldBe 3
        session.commitChunk()

        val r2 = session.write(testChunk(5))
        r2.rowsInserted shouldBe 5
        session.commitChunk()

        val finish = session.finishTable()
        finish shouldBe FinishTableResult.Success(emptyList())
        session.state shouldBe StubTableImportSession.State.FINISHED

        session.close()
        session.state shouldBe StubTableImportSession.State.CLOSED
    }

    test("close from OPEN without any write") {
        val session = StubTableImportSession()
        session.state shouldBe StubTableImportSession.State.OPEN
        session.close()
        session.state shouldBe StubTableImportSession.State.CLOSED
    }

    test("markTruncatePerformed after write-commit cycle throws") {
        val session = StubTableImportSession()
        session.write(testChunk())
        session.commitChunk()
        // State is OPEN again, but hasWritten is sticky
        session.state shouldBe StubTableImportSession.State.OPEN
        shouldThrow<IllegalStateException> {
            session.markTruncatePerformed()
        }
    }

    test("rollbackChunk failure transitions to FAILED") {
        val session = StubTableImportSession(rollbackWillThrow = true)
        session.write(testChunk())
        shouldThrow<RuntimeException> {
            session.rollbackChunk()
        }
        session.state shouldBe StubTableImportSession.State.FAILED
        // only close allowed
        shouldThrow<IllegalStateException> { session.write(testChunk()) }
        session.close()
        session.state shouldBe StubTableImportSession.State.CLOSED
    }
})

/**
 * Minimaler Stub, der die State-Maschine aus §3.1.1 (M1)
 * implementiert. Kein JDBC, kein I/O — nur State-Tracking.
 */
private class StubTableImportSession(
    override val targetColumns: List<TargetColumn> = listOf(
        TargetColumn("id", false, Types.INTEGER),
    ),
    private val commitWillThrow: Boolean = false,
    private val rollbackWillThrow: Boolean = false,
) : TableImportSession {

    enum class State { OPEN, WRITTEN, FAILED, FINISHED, CLOSED }

    var state: State = State.OPEN
        private set

    var truncatePerformed: Boolean = false
        private set

    private var hasWritten: Boolean = false

    override fun write(chunk: DataChunk): WriteResult {
        check(state == State.OPEN) {
            "write() requires OPEN, current state: $state"
        }
        state = State.WRITTEN
        hasWritten = true
        return WriteResult(chunk.rows.size.toLong(), 0, 0)
    }

    override fun commitChunk() {
        check(state == State.WRITTEN) {
            "commitChunk() requires WRITTEN, current state: $state"
        }
        if (commitWillThrow) {
            state = State.FAILED
            throw RuntimeException("simulated commit failure")
        }
        state = State.OPEN
    }

    override fun rollbackChunk() {
        check(state == State.WRITTEN) {
            "rollbackChunk() requires WRITTEN, current state: $state"
        }
        if (rollbackWillThrow) {
            state = State.FAILED
            throw RuntimeException("simulated rollback failure")
        }
        state = State.OPEN
    }

    override fun markTruncatePerformed() {
        check(state == State.OPEN && !hasWritten) {
            "markTruncatePerformed() requires OPEN before any write, " +
                "current state: $state, hasWritten: $hasWritten"
        }
        truncatePerformed = true
    }

    override fun finishTable(): FinishTableResult {
        check(state == State.OPEN) {
            "finishTable() requires OPEN, current state: $state"
        }
        state = State.FINISHED
        return FinishTableResult.Success(emptyList())
    }

    override fun close() {
        if (state == State.CLOSED) return // idempotent
        state = State.CLOSED
    }
}
