package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types

class AbstractTableImportSessionTest : FunSpec({

    val targetColumns = listOf(
        TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
        TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR),
    )

    val columns = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = true),
    )

    fun chunk(
        table: String = "t",
        cols: List<ColumnDescriptor> = columns,
        rows: List<Array<Any?>> = listOf(arrayOf(1, "Alice")),
    ) = DataChunk(table, cols, rows, chunkIndex = 0)

    fun fakePs(): PreparedStatement = Proxy.newProxyInstance(
        PreparedStatement::class.java.classLoader,
        arrayOf(PreparedStatement::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "setNull", "setObject", "addBatch", "clearBatch", "close" -> null
            "executeBatch" -> intArrayOf(1)
            "toString" -> "FakePS"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    } as PreparedStatement

    fun fakeConn(
        preparedStatement: PreparedStatement = fakePs(),
        rollbackThrows: Boolean = false,
        autoCommitThrows: Boolean = false,
    ): Connection {
        var autoCommit = true
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> {
                    if (autoCommitThrows) throw RuntimeException("autoCommit restore failed")
                    autoCommit = args?.get(0) as Boolean
                    null
                }
                "prepareStatement" -> preparedStatement
                "commit" -> null
                "rollback" -> {
                    if (rollbackThrows) throw RuntimeException("rollback failed")
                    null
                }
                "close" -> null
                "abort" -> null
                "isClosed" -> false
                "unwrap" -> null
                "isWrapperFor" -> false
                "toString" -> "FakeConn"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as Connection
    }

    fun session(
        conn: Connection = fakeConn(),
        table: String = "t",
        targets: List<TargetColumn> = targetColumns,
        pkColumns: List<String> = emptyList(),
        options: ImportOptions = ImportOptions(),
        reseedResult: List<SequenceAdjustment> = emptyList(),
        finishCleanupFailure: Throwable? = null,
        closePreFinallyAction: (() -> Unit)? = null,
        closeFinallyAction: (() -> Unit)? = null,
    ) = TestTableImportSession(
        conn = conn,
        savedAutoCommit = true,
        table = table,
        targetColumns = targets,
        primaryKeyColumns = pkColumns,
        options = options,
        reseedResult = reseedResult,
        finishCleanupFailure = finishCleanupFailure,
        closePreFinallyAction = closePreFinallyAction,
        closeFinallyAction = closeFinallyAction,
    )

    // ── write ──────────────────────────────────────────────

    test("write transitions OPEN to WRITTEN") {
        val s = session()
        val result = s.write(chunk())
        result.rowsInserted shouldBe 1
        s.commitChunk()  // proves state is WRITTEN→OPEN
    }

    test("write with mismatched table name throws") {
        val s = session(table = "t")
        shouldThrow<ImportSchemaMismatchException> {
            s.write(chunk(table = "other"))
        }
    }

    test("write with mismatched table transitions to FAILED") {
        val s = session(table = "t")
        shouldThrow<ImportSchemaMismatchException> { s.write(chunk(table = "x")) }
        shouldThrow<IllegalStateException> { s.write(chunk()) }
    }

    test("write with duplicate columns throws") {
        val s = session()
        val dupes = listOf(ColumnDescriptor("id", false), ColumnDescriptor("id", false))
        shouldThrow<ImportSchemaMismatchException> {
            s.write(chunk(cols = dupes, rows = listOf(arrayOf(1, 2))))
        }
    }

    test("write with unknown column throws") {
        val s = session()
        val unknown = listOf(ColumnDescriptor("nonexistent", false))
        shouldThrow<ImportSchemaMismatchException> {
            s.write(chunk(cols = unknown, rows = listOf(arrayOf(1))))
        }
    }

    test("write validates row widths") {
        val s = session()
        val tooWide = chunk(rows = listOf(arrayOf(1, "a", "extra")))
        shouldThrow<ImportSchemaMismatchException> {
            s.write(tooWide)
        }
    }

    test("write validates consistent column layout across chunks") {
        val s = session()
        s.write(chunk())
        s.commitChunk()

        val differentLayout = listOf(ColumnDescriptor("name", true))
        shouldThrow<ImportSchemaMismatchException> {
            s.write(chunk(cols = differentLayout, rows = listOf(arrayOf("x"))))
        }
    }

    test("write with execution failure transitions to FAILED") {
        val failPs = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "setNull", "setObject", "addBatch", "clearBatch", "close" -> null
                "executeBatch" -> throw RuntimeException("batch failed")
                "toString" -> "FailPS"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as PreparedStatement
        val s = session(conn = fakeConn(preparedStatement = failPs))
        shouldThrow<RuntimeException> { s.write(chunk()) }
        shouldThrow<IllegalStateException> { s.write(chunk()) }
    }

    // ── commitChunk / rollbackChunk ────────────────────────

    test("commitChunk from OPEN throws") {
        val s = session()
        shouldThrow<IllegalStateException> { s.commitChunk() }
    }

    test("commitChunk transitions WRITTEN to OPEN") {
        val s = session()
        s.write(chunk())
        s.commitChunk()
        s.write(chunk())  // proves state is back to OPEN
    }

    test("rollbackChunk from OPEN throws") {
        val s = session()
        shouldThrow<IllegalStateException> { s.rollbackChunk() }
    }

    test("rollbackChunk transitions WRITTEN to OPEN") {
        val s = session()
        s.write(chunk())
        s.rollbackChunk()
        s.write(chunk())  // proves state is back to OPEN
    }

    // ── markTruncatePerformed ──────────────────────────────

    test("markTruncatePerformed from OPEN succeeds") {
        val s = session()
        s.markTruncatePerformed()
    }

    test("markTruncatePerformed after write throws") {
        val s = session()
        s.write(chunk())
        shouldThrow<IllegalStateException> { s.markTruncatePerformed() }
    }

    test("markTruncatePerformed after write-commit throws (hasWritten is sticky)") {
        val s = session()
        s.write(chunk())
        s.commitChunk()
        shouldThrow<IllegalStateException> { s.markTruncatePerformed() }
    }

    // ── finishTable ────────────────────────────────────────

    test("finishTable from OPEN returns Success") {
        val adj = listOf(SequenceAdjustment("t", "id", "seq", 42))
        val s = session(reseedResult = adj)
        val result = s.finishTable()
        result shouldBe FinishTableResult.Success(adj)
    }

    test("finishTable skips reseed when reseedSequences=false") {
        val s = session(
            options = ImportOptions(reseedSequences = false),
            reseedResult = listOf(SequenceAdjustment("t", "id", "seq", 99)),
        )
        val result = s.finishTable()
        result.shouldBeInstanceOf<FinishTableResult.Success>()
        (result as FinishTableResult.Success).adjustments shouldBe emptyList()
    }

    test("finishTable from WRITTEN throws") {
        val s = session()
        s.write(chunk())
        shouldThrow<IllegalStateException> { s.finishTable() }
    }

    test("finishTable with dialect cleanup failure returns PartialFailure") {
        val cause = RuntimeException("FK reset failed")
        val s = session(finishCleanupFailure = cause)
        val result = s.finishTable()
        result shouldBe FinishTableResult.PartialFailure(emptyList(), cause)
    }

    test("finishTable with reseed exception transitions to FAILED") {
        val s = session()
        s.reseedThrows = RuntimeException("reseed boom")
        shouldThrow<RuntimeException> { s.finishTable() }
        shouldThrow<IllegalStateException> { s.finishTable() }
    }

    // ── close ──────────────────────────────────────────────

    test("close from OPEN rolls back") {
        var rolledBack = false
        val conn = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAutoCommit" -> true
                "setAutoCommit" -> null
                "rollback" -> { rolledBack = true; null }
                "close" -> null
                "abort" -> null
                "isClosed" -> false
                "toString" -> "SpyConn"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as Connection
        val s = session(conn = conn)
        s.close()
        rolledBack shouldBe true
    }

    test("close is idempotent") {
        var closeCount = 0
        val conn = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAutoCommit" -> true
                "setAutoCommit" -> null
                "rollback" -> null
                "close" -> { closeCount++; null }
                "abort" -> null
                "isClosed" -> false
                "toString" -> "SpyConn"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as Connection
        val s = session(conn = conn)
        s.close()
        s.close()
        closeCount shouldBe 1
    }

    test("close calls closePreFinally and closeFinally") {
        var preFinallyCalled = false
        var finallyCalled = false
        val s = session(
            closePreFinallyAction = { preFinallyCalled = true },
            closeFinallyAction = { finallyCalled = true },
        )
        s.close()
        preFinallyCalled shouldBe true
        finallyCalled shouldBe true
    }

    test("close from FINISHED skips rollback") {
        var rolledBack = false
        val conn = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAutoCommit" -> true
                "setAutoCommit" -> null
                "rollback" -> { rolledBack = true; null }
                "close" -> null
                "abort" -> null
                "isClosed" -> false
                "toString" -> "SpyConn"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as Connection
        val s = session(conn = conn)
        s.finishTable()
        s.close()
        rolledBack shouldBe false
    }

    test("close records rollback failure without throwing") {
        val s = session(conn = fakeConn(rollbackThrows = true))
        s.close()  // should not throw
    }

    test("close records autoCommit restore failure without throwing") {
        val s = session(conn = fakeConn(autoCommitThrows = true))
        s.close()  // should not throw
    }

    // ── validateUpsertColumns ──────────────────────────────

    test("validateUpsertColumns rejects missing PK columns") {
        val s = session(
            pkColumns = listOf("id", "missing_pk"),
            options = ImportOptions(onConflict = OnConflict.UPDATE),
        )
        shouldThrow<ImportSchemaMismatchException> {
            s.write(chunk())
        }
    }

    test("validateUpsertColumns passes when all PKs present") {
        val s = session(
            pkColumns = listOf("id"),
            options = ImportOptions(onConflict = OnConflict.UPDATE),
        )
        s.write(chunk())  // should not throw
    }

    test("validateUpsertColumns is skipped for non-UPDATE mode") {
        val s = session(
            pkColumns = listOf("id", "missing"),
            options = ImportOptions(onConflict = OnConflict.ABORT),
        )
        s.write(chunk())  // should not throw
    }

    // ── recordCleanupFailure ───────────────────────────────

    test("recordCleanupFailure stores first failure and suppresses subsequent") {
        val s = session()
        val first = RuntimeException("first")
        val second = RuntimeException("second")
        s.testRecordCleanupFailure(first)
        s.testRecordCleanupFailure(second)
        first.suppressedExceptions shouldHaveSize 1
        first.suppressedExceptions.single() shouldBe second
    }

    // ── full lifecycle ─────────────────────────────────────

    test("write-commit-write-commit-finish-close cycle") {
        val s = session()
        s.write(chunk())
        s.commitChunk()
        s.write(chunk())
        s.commitChunk()
        val result = s.finishTable()
        result.shouldBeInstanceOf<FinishTableResult.Success>()
        s.close()
    }

    test("write-rollback-write-commit-finish-close cycle") {
        val s = session()
        s.write(chunk())
        s.rollbackChunk()
        s.write(chunk())
        s.commitChunk()
        s.finishTable()
        s.close()
    }

    test("zero-chunk path: finish without any write") {
        val s = session()
        s.markTruncatePerformed()
        val result = s.finishTable()
        result.shouldBeInstanceOf<FinishTableResult.Success>()
        s.close()
    }
})

/**
 * Minimal concrete subclass of [AbstractTableImportSession] for testing.
 * No JDBC, no I/O — stub implementations that track calls and return
 * configurable results.
 */
internal class TestTableImportSession(
    conn: Connection,
    savedAutoCommit: Boolean,
    table: String,
    targetColumns: List<TargetColumn>,
    primaryKeyColumns: List<String>,
    options: ImportOptions,
    private val reseedResult: List<SequenceAdjustment> = emptyList(),
    private val finishCleanupFailure: Throwable? = null,
    private val closePreFinallyAction: (() -> Unit)? = null,
    private val closeFinallyAction: (() -> Unit)? = null,
) : AbstractTableImportSession(conn, savedAutoCommit, table, targetColumns, primaryKeyColumns, options) {

    var reseedThrows: Throwable? = null

    fun testRecordCleanupFailure(t: Throwable) = recordCleanupFailure(t)

    override fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String {
        val cols = importedTargetColumns.joinToString(", ") { it.name }
        val placeholders = importedTargetColumns.joinToString(", ") { "?" }
        return "INSERT INTO $table ($cols) VALUES ($placeholders)"
    }

    override fun executeChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        for (row in rows) {
            bindRow(stmt, importedTargetColumns, row)
            stmt.addBatch()
        }
        stmt.executeBatch()
        return WriteResult(rowsInserted = rows.size.toLong(), rowsUpdated = 0, rowsSkipped = 0)
    }

    override fun bindRow(
        stmt: PreparedStatement,
        importedTargetColumns: List<TargetColumn>,
        row: Array<Any?>,
    ) {
        importedTargetColumns.forEachIndexed { index, targetColumn ->
            val value = row[index]
            if (value == null) {
                stmt.setNull(index + 1, targetColumn.jdbcType)
            } else {
                stmt.setObject(index + 1, value)
            }
        }
    }

    override fun reseedSequences(): List<SequenceAdjustment> {
        reseedThrows?.let { throw it }
        return reseedResult
    }

    override fun finishDialectCleanup(): Throwable? = finishCleanupFailure

    override fun closePreFinally() {
        closePreFinallyAction?.invoke()
    }

    override fun closeFinally() {
        closeFinallyAction?.invoke()
    }
}
