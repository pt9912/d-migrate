package dev.dmigrate.driver.mysql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types

class MysqlTableImportSessionTest : FunSpec({

    // ── shared helpers ────────────────────────────────

    val defaultTargetColumns = listOf(
        TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
        TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR),
    )

    val defaultColumns = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = true),
    )

    fun buildSession(
        conn: Connection = mockk(relaxUnitFun = true),
        savedAutoCommit: Boolean = true,
        table: String = "users",
        targetColumns: List<TargetColumn> = defaultTargetColumns,
        primaryKeyColumns: List<String> = emptyList(),
        options: ImportOptions = ImportOptions(),
        schemaSync: MysqlSchemaSync = mockk(relaxUnitFun = true),
        fkChecksDisabled: Boolean = false,
    ): Pair<MysqlTableImportSession, Connection> {
        val session = MysqlTableImportSession(
            conn = conn,
            savedAutoCommit = savedAutoCommit,
            table = table,
            qualifiedTable = MysqlQualifiedTableName(null, table),
            targetColumns = targetColumns,
            primaryKeyColumns = primaryKeyColumns,
            options = options,
            schemaSync = schemaSync,
            fkChecksDisabled = fkChecksDisabled,
        )
        return session to conn
    }

    fun makeChunk(
        table: String = "users",
        columns: List<ColumnDescriptor> = defaultColumns,
        rows: List<Array<Any?>> = listOf(arrayOf(1, "Alice")),
        chunkIndex: Long = 0,
    ): DataChunk = DataChunk(table, columns, rows, chunkIndex)

    /**
     * Stubs conn.prepareStatement for INSERT SQL and returns the mock PreparedStatement.
     */
    fun stubInsertPreparedStatement(conn: Connection): PreparedStatement {
        val ps = mockk<PreparedStatement>(relaxUnitFun = true)
        every { conn.prepareStatement(any<String>()) } returns ps
        return ps
    }

    /**
     * Stubs conn.createStatement() chain for MysqlDataWriter.setForeignKeyChecks().
     */
    fun stubFkChecksStatement(conn: Connection) {
        val stmt = mockk<Statement>(relaxUnitFun = true)
        every { conn.createStatement() } returns stmt
        every { stmt.execute(any<String>()) } returns true
    }

    // ── state machine tests ───────────────────────────

    test("write transitions OPEN to WRITTEN") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        every { ps.executeBatch() } returns intArrayOf(1)
        val (session, _) = buildSession(conn = conn)

        session.write(makeChunk())

        // Should be in WRITTEN state now: commitChunk should work
        session.commitChunk()
    }

    test("write with mismatched table throws ImportSchemaMismatchException") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val (session, _) = buildSession(conn = conn, table = "users")

        shouldThrow<ImportSchemaMismatchException> {
            session.write(makeChunk(table = "orders"))
        }
    }

    test("write with duplicate columns throws") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val (session, _) = buildSession(conn = conn)

        val duplicateColumns = listOf(
            ColumnDescriptor("id", nullable = false),
            ColumnDescriptor("id", nullable = false),
        )
        val chunk = makeChunk(columns = duplicateColumns, rows = listOf(arrayOf(1, 2)))

        shouldThrow<ImportSchemaMismatchException> {
            session.write(chunk)
        }
    }

    test("write with unknown target column throws") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val (session, _) = buildSession(conn = conn)

        val unknownColumns = listOf(
            ColumnDescriptor("nonexistent", nullable = false),
        )
        val chunk = makeChunk(columns = unknownColumns, rows = listOf(arrayOf(42)))

        shouldThrow<ImportSchemaMismatchException> {
            session.write(chunk)
        }
    }

    test("commitChunk transitions WRITTEN to OPEN") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        every { ps.executeBatch() } returns intArrayOf(1)
        val (session, _) = buildSession(conn = conn)

        session.write(makeChunk())
        session.commitChunk()

        // Should be back in OPEN state: can write again
        every { ps.executeBatch() } returns intArrayOf(1)
        session.write(makeChunk())
        session.commitChunk()
    }

    test("rollbackChunk transitions WRITTEN to OPEN") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        every { ps.executeBatch() } returns intArrayOf(1)
        val (session, _) = buildSession(conn = conn)

        session.write(makeChunk())
        session.rollbackChunk()

        // Should be back in OPEN state: can write again
        every { ps.executeBatch() } returns intArrayOf(1)
        session.write(makeChunk())
        session.commitChunk()
    }

    test("markTruncatePerformed in OPEN state before write succeeds") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val (session, _) = buildSession(conn = conn)

        // Should not throw
        session.markTruncatePerformed()
    }

    // ── SQL generation tests ──────────────────────────

    test("buildInsertSql for ABORT generates INSERT INTO") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        every { ps.executeBatch() } returns intArrayOf(1)
        val (session, _) = buildSession(conn = conn, options = ImportOptions(onConflict = OnConflict.ABORT))

        session.write(makeChunk())

        verify { conn.prepareStatement(match { it.startsWith("INSERT INTO") && !it.contains("IGNORE") }) }
    }

    test("buildInsertSql for SKIP generates INSERT IGNORE INTO") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        every { ps.executeUpdate() } returns 1
        val (session, _) = buildSession(conn = conn, options = ImportOptions(onConflict = OnConflict.SKIP))

        session.write(makeChunk())

        verify { conn.prepareStatement(match { it.startsWith("INSERT IGNORE") }) }
    }

    test("buildInsertSql for UPDATE generates INSERT ON DUPLICATE KEY UPDATE") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        every { ps.executeUpdate() } returns 1
        val (session, _) = buildSession(
            conn = conn,
            primaryKeyColumns = listOf("id"),
            options = ImportOptions(onConflict = OnConflict.UPDATE),
        )

        session.write(makeChunk())

        verify {
            conn.prepareStatement(match {
                it.contains("INSERT") && it.contains("ON DUPLICATE KEY UPDATE")
            })
        }
    }

    // ── execute tests ─────────────────────────────────

    test("executeBatchChunk counts correctly") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        // Two rows inserted successfully
        every { ps.executeBatch() } returns intArrayOf(1, 1)
        val (session, _) = buildSession(conn = conn)

        val chunk = makeChunk(rows = listOf(arrayOf(1, "Alice"), arrayOf(2, "Bob")))
        val result = session.write(chunk)

        result shouldBe WriteResult(rowsInserted = 2, rowsUpdated = 0, rowsSkipped = 0)
    }

    test("executeSkipChunk counts inserts vs skips") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        // First row inserted (1), second row skipped (0)
        var callCount = 0
        every { ps.executeUpdate() } answers {
            if (callCount++ == 0) 1 else 0
        }
        val (session, _) = buildSession(conn = conn, options = ImportOptions(onConflict = OnConflict.SKIP))

        val chunk = makeChunk(rows = listOf(arrayOf(1, "Alice"), arrayOf(2, "Bob")))
        val result = session.write(chunk)

        result shouldBe WriteResult(rowsInserted = 1, rowsUpdated = 0, rowsSkipped = 1)
    }

    // ── bind tests ────────────────────────────────────

    test("bindRow with null calls setNull") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        every { ps.executeBatch() } returns intArrayOf(1)
        val (session, _) = buildSession(conn = conn)

        val chunk = makeChunk(rows = listOf(arrayOf(1, null)))
        session.write(chunk)

        verify { ps.setNull(2, Types.VARCHAR) }
        verify { ps.setObject(1, 1) }
    }

    test("bindRow with value calls setObject") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ps = stubInsertPreparedStatement(conn)
        every { ps.executeBatch() } returns intArrayOf(1)
        val (session, _) = buildSession(conn = conn)

        val chunk = makeChunk(rows = listOf(arrayOf(42, "Bob")))
        session.write(chunk)

        verify { ps.setObject(1, 42) }
        verify { ps.setObject(2, "Bob") }
    }

    // ── finish / close tests ──────────────────────────

    test("finishTable with reseedSequences calls schemaSync with truncatePerformed") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val schemaSync = mockk<MysqlSchemaSync>(relaxUnitFun = true)
        every {
            schemaSync.reseedGenerators(
                conn = any(),
                table = any(),
                importedColumns = any<List<ColumnDescriptor>>(),
                truncatePerformed = any(),
            )
        } returns emptyList()
        val (session, _) = buildSession(
            conn = conn,
            schemaSync = schemaSync,
            options = ImportOptions(reseedSequences = true),
        )

        session.markTruncatePerformed()
        val result = session.finishTable()

        result.shouldBeInstanceOf<FinishTableResult.Success>()
        verify {
            schemaSync.reseedGenerators(
                conn = conn,
                table = "users",
                importedColumns = emptyList(),
                truncatePerformed = true,
            )
        }
    }

    test("finishTable resets FK checks on success") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        stubFkChecksStatement(conn)
        val schemaSync = mockk<MysqlSchemaSync>(relaxUnitFun = true)
        every {
            schemaSync.reseedGenerators(
                conn = any(),
                table = any(),
                importedColumns = any<List<ColumnDescriptor>>(),
                truncatePerformed = any(),
            )
        } returns emptyList()
        val (session, _) = buildSession(
            conn = conn,
            schemaSync = schemaSync,
            fkChecksDisabled = true,
            options = ImportOptions(reseedSequences = true, disableFkChecks = true),
        )

        val result = session.finishTable()

        result.shouldBeInstanceOf<FinishTableResult.Success>()
        // setForeignKeyChecks(conn, true) uses raw JDBC
        verify { conn.createStatement() }
    }

    test("close from OPEN rolls back and closes connection") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val (session, _) = buildSession(conn = conn)

        session.close()

        verify { conn.rollback() }
        verify { conn.close() }
        verify { conn.autoCommit = true } // restore savedAutoCommit
    }

    test("close is idempotent") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val (session, _) = buildSession(conn = conn)

        session.close()
        session.close() // second call should be no-op

        // rollback and close should each have been called exactly once
        verify(exactly = 1) { conn.rollback() }
        verify(exactly = 1) { conn.close() }
    }

    // ── existing Proxy-based test (preserved) ─────────

    test("close aborts connection when fk reset retry fails after finishTable partial failure") {
        var autoCommit = false
        var abortCalled = false
        val firstResetFailure = RuntimeException("first fk reset failed")
        val secondResetFailure = RuntimeException("second fk reset failed")
        var resetAttempts = 0
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> {
                    autoCommit = args?.get(0) as Boolean
                    null
                }
                "createStatement" -> failingMysqlStatement {
                    resetAttempts++
                    if (resetAttempts == 1) firstResetFailure else secondResetFailure
                }
                "rollback", "close" -> null
                "abort" -> {
                    abortCalled = true
                    null
                }
                "isClosed" -> false
                "unwrap" -> null
                "isWrapperFor" -> false
                "toString" -> "FakeMysqlConnection"
                "hashCode" -> 0
                "equals" -> false
                else -> error("Unexpected Connection method in test: ${method.name}")
            }
        } as Connection

        val session = MysqlTableImportSession(
            conn = connection,
            savedAutoCommit = false,
            table = "writer_child",
            qualifiedTable = MysqlQualifiedTableName(null, "writer_child"),
            targetColumns = listOf(TargetColumn("id", nullable = false, jdbcType = Types.INTEGER)),
            primaryKeyColumns = emptyList(),
            options = ImportOptions(disableFkChecks = true, reseedSequences = false),
            schemaSync = MysqlSchemaSync(),
            fkChecksDisabled = true,
        )

        val finish = session.finishTable()
        finish shouldBe FinishTableResult.PartialFailure(emptyList(), firstResetFailure)
        session.close()

        abortCalled shouldBe true
        firstResetFailure.suppressedExceptions.shouldHaveSize(1)
        firstResetFailure.suppressedExceptions.single().message shouldBe "second fk reset failed"
    }
})

private fun failingMysqlStatement(nextResetFailure: () -> RuntimeException): Statement =
    Proxy.newProxyInstance(
        Statement::class.java.classLoader,
        arrayOf(Statement::class.java),
    ) { _, method, args ->
        when (method.name) {
            "execute" -> {
                val sql = args?.get(0) as String
                if (sql == "SET FOREIGN_KEY_CHECKS = 1") {
                    throw nextResetFailure()
                }
                true
            }
            "close" -> null
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeMysqlStatement"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected Statement method in test: ${method.name}")
        }
    } as Statement
