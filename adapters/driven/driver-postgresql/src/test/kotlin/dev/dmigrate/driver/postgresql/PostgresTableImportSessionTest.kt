package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types

class PostgresTableImportSessionTest : FunSpec({

    // ── Helpers ─────────────────────────────────────────

    fun mockConn(): Connection = mockk<Connection>(relaxUnitFun = true) {
        every { autoCommit } returns false
    }

    fun mockStmt(): PreparedStatement = mockk<PreparedStatement>(relaxUnitFun = true) {
        every { connection } returns mockk {
            every { createArrayOf(any(), any()) } returns mockk()
        }
    }

    fun mockSync(): PostgresSchemaSync = mockk<PostgresSchemaSync>(relaxUnitFun = true) {
        every { reseedGenerators(any(), any(), any()) } returns emptyList()
    }

    val defaultTargetColumns = listOf(
        TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "int4"),
        TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "varchar"),
    )

    val defaultColumns = listOf(
        ColumnDescriptor("id", nullable = false, sqlTypeName = "int4"),
        ColumnDescriptor("name", nullable = true, sqlTypeName = "varchar"),
    )

    fun newSession(
        conn: Connection = mockConn(),
        table: String = "public.users",
        targetColumns: List<TargetColumn> = defaultTargetColumns,
        generatedAlwaysColumns: Set<String> = emptySet(),
        primaryKeyColumns: List<String> = emptyList(),
        options: ImportOptions = ImportOptions(),
        schemaSync: PostgresSchemaSync = mockSync(),
        triggersDisabled: Boolean = false,
        savedAutoCommit: Boolean = true,
    ): PostgresTableImportSession {
        return PostgresTableImportSession(
            conn = conn,
            savedAutoCommit = savedAutoCommit,
            table = table,
            qualifiedTable = parseQualifiedTableName(table),
            targetColumns = targetColumns,
            generatedAlwaysColumns = generatedAlwaysColumns,
            primaryKeyColumns = primaryKeyColumns,
            options = options,
            schemaSync = schemaSync,
            triggersDisabled = triggersDisabled,
        )
    }

    fun makeChunk(
        table: String = "public.users",
        columns: List<ColumnDescriptor> = defaultColumns,
        rows: List<Array<Any?>> = listOf(arrayOf(1, "Alice")),
        chunkIndex: Long = 0L,
    ): DataChunk = DataChunk(table, columns, rows, chunkIndex)

    /**
     * Creates a session with a mockk PreparedStatement wired into the connection,
     * and writes a chunk to it. Returns (session, stmt) for further assertions.
     */
    fun sessionWithWrittenChunk(
        conn: Connection = mockConn(),
        options: ImportOptions = ImportOptions(),
        targetColumns: List<TargetColumn> = defaultTargetColumns,
        generatedAlwaysColumns: Set<String> = emptySet(),
        primaryKeyColumns: List<String> = emptyList(),
        chunk: DataChunk = makeChunk(),
        schemaSync: PostgresSchemaSync = mockSync(),
        triggersDisabled: Boolean = false,
    ): Pair<PostgresTableImportSession, PreparedStatement> {
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(1)

        val session = newSession(
            conn = conn,
            options = options,
            targetColumns = targetColumns,
            generatedAlwaysColumns = generatedAlwaysColumns,
            primaryKeyColumns = primaryKeyColumns,
            schemaSync = schemaSync,
            triggersDisabled = triggersDisabled,
        )
        session.write(chunk)
        return session to stmt
    }

    // ── Existing test: close keeps trigger re-enable retry as suppressed ──

    test("close keeps trigger re-enable retry as suppressed on partial failure cause") {
        val firstEnableFailure = RuntimeException("first enable failed")
        val secondEnableFailure = RuntimeException("second enable failed")
        var enableCalls = 0
        val schemaSync = mockk<PostgresSchemaSync>(relaxUnitFun = true) {
            every { reseedGenerators(any(), any(), any()) } returns emptyList()
            every { enableTriggers(any(), any()) } answers {
                enableCalls++
                throw if (enableCalls == 1) firstEnableFailure else secondEnableFailure
            }
        }

        val session = PostgresTableImportSession(
            conn = fakeConnection(),
            savedAutoCommit = true,
            table = "public.t",
            qualifiedTable = QualifiedTableName("public", "t"),
            targetColumns = emptyList(),
            generatedAlwaysColumns = emptySet(),
            primaryKeyColumns = emptyList(),
            options = ImportOptions(),
            schemaSync = schemaSync,
            triggersDisabled = true,
        )

        val finish = session.finishTable()
        finish shouldBe FinishTableResult.PartialFailure(emptyList(), firstEnableFailure)

        session.close()

        firstEnableFailure.suppressedExceptions.shouldHaveSize(1)
        firstEnableFailure.suppressedExceptions.single().message shouldBe "second enable failed"
    }

    // ── State machine tests ─────────────────────────────

    test("write transitions OPEN to WRITTEN") {
        val conn = mockConn()
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(1)

        val session = newSession(conn = conn)
        val result = session.write(makeChunk())

        result.rowsInserted shouldBe 1

        // Should now be in WRITTEN state - commitChunk should work
        shouldNotThrowAny { session.commitChunk() }
        session.close()
    }

    test("write with mismatched table throws ImportSchemaMismatchException") {
        val session = newSession()

        val ex = shouldThrow<ImportSchemaMismatchException> {
            session.write(makeChunk(table = "other.table"))
        }
        ex.message shouldContain "does not match"
        session.close()
    }

    test("write with duplicate columns throws ImportSchemaMismatchException") {
        val conn = mockConn()
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt

        val session = newSession(conn = conn)
        val duplicateColumns = listOf(
            ColumnDescriptor("id", nullable = false),
            ColumnDescriptor("id", nullable = false),
        )

        val ex = shouldThrow<ImportSchemaMismatchException> {
            session.write(makeChunk(columns = duplicateColumns, rows = listOf(arrayOf(1, 2))))
        }
        ex.message shouldContain "duplicate columns"
        session.close()
    }

    test("write with unknown target column throws ImportSchemaMismatchException") {
        val conn = mockConn()
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt

        val session = newSession(conn = conn)
        val unknownColumns = listOf(
            ColumnDescriptor("id", nullable = false),
            ColumnDescriptor("nonexistent", nullable = true),
        )

        val ex = shouldThrow<ImportSchemaMismatchException> {
            session.write(makeChunk(columns = unknownColumns, rows = listOf(arrayOf(1, "x"))))
        }
        ex.message shouldContain "nonexistent"
        session.close()
    }

    test("commitChunk transitions WRITTEN to OPEN") {
        val conn = mockConn()
        val (session, _) = sessionWithWrittenChunk(conn = conn)

        session.commitChunk()
        verify { conn.commit() }

        // Should be back in OPEN - can write again
        session.close()
    }

    test("rollbackChunk transitions WRITTEN to OPEN") {
        val conn = mockConn()
        val (session, _) = sessionWithWrittenChunk(conn = conn)

        session.rollbackChunk()
        verify { conn.rollback() }

        // Should be back in OPEN - can write again
        session.close()
    }

    test("markTruncatePerformed in OPEN state before write succeeds") {
        val session = newSession()

        shouldNotThrowAny { session.markTruncatePerformed() }
        session.close()
    }

    // ── SQL generation tests ────────────────────────────

    test("buildInsertSql for ABORT generates plain INSERT") {
        val conn = mockConn()
        val stmt = mockStmt()
        val capturedSql = slot<String>()
        every { conn.prepareStatement(capture(capturedSql)) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(1)

        val session = newSession(conn = conn, options = ImportOptions(onConflict = OnConflict.ABORT))
        session.write(makeChunk())

        capturedSql.captured shouldContain "INSERT INTO"
        capturedSql.captured shouldContain "VALUES"
        // Should NOT contain ON CONFLICT
        (capturedSql.captured.contains("ON CONFLICT")) shouldBe false
        session.close()
    }

    test("buildInsertSql for SKIP generates ON CONFLICT DO NOTHING") {
        val conn = mockConn()
        val stmt = mockStmt()
        val capturedSql = slot<String>()
        every { conn.prepareStatement(capture(capturedSql)) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(1)

        val session = newSession(conn = conn, options = ImportOptions(onConflict = OnConflict.SKIP))
        session.write(makeChunk())

        capturedSql.captured shouldContain "ON CONFLICT DO NOTHING"
        session.close()
    }

    test("buildInsertSql for UPDATE generates ON CONFLICT DO UPDATE RETURNING") {
        val conn = mockConn()
        val stmt = mockStmt()
        val capturedSql = slot<String>()
        every { conn.prepareStatement(capture(capturedSql)) } returns stmt

        // Multi-row upsert: executeQuery returns one row per input row
        val rs = mockk<ResultSet>(relaxUnitFun = true)
        every { stmt.executeQuery() } returns rs
        every { rs.next() } returnsMany listOf(true, false)
        every { rs.getBoolean(1) } returns true

        val session = newSession(
            conn = conn,
            options = ImportOptions(onConflict = OnConflict.UPDATE),
            primaryKeyColumns = listOf("id"),
        )
        session.write(makeChunk())

        capturedSql.captured shouldContain "ON CONFLICT"
        capturedSql.captured shouldContain "DO UPDATE SET"
        capturedSql.captured shouldContain "RETURNING"
        session.close()
    }

    test("buildInsertSql with OVERRIDING SYSTEM VALUE for generated-always columns") {
        val conn = mockConn()
        val stmt = mockStmt()
        val capturedSql = slot<String>()
        every { conn.prepareStatement(capture(capturedSql)) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(1)

        val session = newSession(
            conn = conn,
            generatedAlwaysColumns = setOf("id"),
        )
        session.write(makeChunk())

        capturedSql.captured shouldContain "OVERRIDING SYSTEM VALUE"
        session.close()
    }

    // ── Bind value tests ────────────────────────────────

    test("bindValue with null calls setNull") {
        val conn = mockConn()
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(1)

        val session = newSession(conn = conn)
        session.write(makeChunk(rows = listOf(arrayOf(null, null))))

        verify { stmt.setNull(1, Types.INTEGER) }
        verify { stmt.setNull(2, Types.VARCHAR) }
        session.close()
    }

    test("bindValue with json uses PGobject") {
        val conn = mockConn()
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(1)

        val jsonTargetColumns = listOf(
            TargetColumn("data", nullable = true, jdbcType = Types.OTHER, sqlTypeName = "json"),
        )
        val jsonColumns = listOf(
            ColumnDescriptor("data", nullable = true, sqlTypeName = "json"),
        )
        val jsonValue = """{"key":"value"}"""

        val session = newSession(conn = conn, targetColumns = jsonTargetColumns)
        session.write(makeChunk(columns = jsonColumns, rows = listOf(arrayOf(jsonValue))))

        verify {
            stmt.setObject(1, match<PGobject> { it.type == "json" && it.value == jsonValue })
        }
        session.close()
    }

    test("bindValue with generic type calls setObject") {
        val conn = mockConn()
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(1)

        val session = newSession(conn = conn)
        session.write(makeChunk(rows = listOf(arrayOf(42, "Alice"))))

        verify { stmt.setObject(1, 42) }
        verify { stmt.setObject(2, "Alice") }
        session.close()
    }

    // ── Execute tests ───────────────────────────────────

    test("executeInsertChunk uses batch and returns WriteResult") {
        val conn = mockConn()
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(1, 1, 1)

        val session = newSession(conn = conn)
        val rows = listOf(
            arrayOf<Any?>(1, "Alice"),
            arrayOf<Any?>(2, "Bob"),
            arrayOf<Any?>(3, "Charlie"),
        )
        val result = session.write(makeChunk(rows = rows))

        result shouldBe WriteResult(rowsInserted = 3, rowsUpdated = 0, rowsSkipped = 0)
        verify(exactly = 3) { stmt.addBatch() }
        session.close()
    }

    test("toWriteResult handles SUCCESS_NO_INFO") {
        val conn = mockConn()
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt
        every { stmt.executeBatch() } returns intArrayOf(Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO)

        val session = newSession(conn = conn)
        val result = session.write(makeChunk(rows = listOf(arrayOf(1, "Alice"), arrayOf(2, "Bob"))))

        result.rowsUnknown shouldBe 2
        result.rowsInserted shouldBe 0
        session.close()
    }

    test("toWriteResult handles 0 as skipped") {
        val conn = mockConn()
        val stmt = mockStmt()
        every { conn.prepareStatement(any<String>()) } returns stmt
        // count=0 means the row was skipped (ON CONFLICT DO NOTHING path)
        every { stmt.executeBatch() } returns intArrayOf(1, 0, 1)

        val session = newSession(conn = conn)
        val rows = listOf(
            arrayOf<Any?>(1, "Alice"),
            arrayOf<Any?>(2, "Bob"),
            arrayOf<Any?>(3, "Charlie"),
        )
        val result = session.write(makeChunk(rows = rows))

        result.rowsInserted shouldBe 2
        result.rowsSkipped shouldBe 1
        session.close()
    }

    // ── Finish/close tests ──────────────────────────────

    test("finishTable with reseedSequences=true calls schemaSync") {
        val conn = mockConn()
        val sync = mockSync()
        every { sync.reseedGenerators(any(), any(), any()) } returns listOf(
            SequenceAdjustment("public.users", "id", "public.users_id_seq", 43),
        )

        val session = newSession(conn = conn, schemaSync = sync)
        val result = session.finishTable()

        result.shouldBeInstanceOf<FinishTableResult.Success>()
        result.adjustments shouldHaveSize 1
        result.adjustments[0].newValue shouldBe 43

        verify { sync.reseedGenerators(conn, "public.users", any()) }
        session.close()
    }

    test("finishTable with reseedSequences=false skips reseeding") {
        val conn = mockConn()
        val sync = mockSync()

        val session = newSession(
            conn = conn,
            schemaSync = sync,
            options = ImportOptions(reseedSequences = false),
        )
        val result = session.finishTable()

        result.shouldBeInstanceOf<FinishTableResult.Success>()
        result.adjustments shouldHaveSize 0

        verify(exactly = 0) { sync.reseedGenerators(any(), any(), any()) }
        session.close()
    }

    test("close from OPEN rolls back and closes connection") {
        val conn = mockConn()
        val session = newSession(conn = conn)

        session.close()

        verify { conn.rollback() }
        verify { conn.close() }
    }

    test("close is idempotent") {
        val conn = mockConn()
        val session = newSession(conn = conn)

        session.close()
        session.close() // second call should not throw

        // close() on connection should only be called once
        verify(exactly = 1) { conn.close() }
    }
})

private fun fakeConnection(): Connection {
    var autoCommit = true
    return java.lang.reflect.Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAutoCommit" -> autoCommit
            "setAutoCommit" -> {
                autoCommit = args?.get(0) as Boolean
                null
            }
            "close", "rollback" -> null
            "isClosed" -> false
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeConnection"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected Connection method in test: ${method.name}")
        }
    } as Connection
}
