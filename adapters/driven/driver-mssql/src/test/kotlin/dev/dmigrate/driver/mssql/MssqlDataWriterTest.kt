package dev.dmigrate.driver.mssql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types

/**
 * Deckt die JDBC-Choreografie des MSSQL-Import-Pfads ab (SET-Optionen,
 * IDENTITY_INSERT, Konfliktmodi, Reseed, Cleanup) — die reine SQL-Erzeugung
 * prüft [MssqlInsertSqlTest] mock-frei.
 */
class MssqlDataWriterTest : FunSpec({

    class Rig {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val jdbc = mockk<JdbcOperations>(relaxed = true)
        val pool = mockk<ConnectionPool>()
        val writer = MssqlDataWriter { jdbc }
        var autoCommit = true

        init {
            every { pool.borrow() } returns JdbcDatabaseConnection(conn)
            every { conn.autoCommit } answers { autoCommit }
            every { conn.autoCommit = any() } answers { autoCommit = firstArg() }
            every { jdbc.querySingle(match { it.contains("SCHEMA_NAME()") }) } returns
                mapOf("schema_name" to "dbo")
            every { jdbc.queryList(match { it.contains("sys.identity_columns") }, any()) } returns emptyList()
            every { jdbc.queryList(match { it.contains("kc.type = 'PK'") }, any()) } returns emptyList()
        }

        fun withColumns(vararg columns: TargetColumn) = apply {
            val ps = mockk<PreparedStatement>(relaxUnitFun = true)
            val rs = mockk<ResultSet>(relaxUnitFun = true)
            val md = mockk<ResultSetMetaData>()
            every { conn.prepareStatement(match { it.contains("WHERE 1 = 0") }) } returns ps
            every { ps.executeQuery() } returns rs
            every { rs.metaData } returns md
            every { md.columnCount } returns columns.size
            columns.forEachIndexed { index, column ->
                val pos = index + 1
                every { md.getColumnLabel(pos) } returns column.name
                every { md.isNullable(pos) } returns
                    if (column.nullable) ResultSetMetaData.columnNullable else ResultSetMetaData.columnNoNulls
                every { md.getColumnType(pos) } returns column.jdbcType
                every { md.getColumnTypeName(pos) } returns (column.sqlTypeName ?: "int")
            }
        }

        fun withIdentityColumns(vararg names: String) = apply {
            every { jdbc.queryList(match { it.contains("sys.identity_columns") }, any()) } returns
                names.map { mapOf<String, Any?>("column_name" to it) }
        }

        fun withPrimaryKey(vararg names: String) = apply {
            every { jdbc.queryList(match { it.contains("kc.type = 'PK'") }, any()) } returns
                names.map { mapOf<String, Any?>("column_name" to it) }
        }

        fun withInsertStatement(): PreparedStatement {
            val ps = mockk<PreparedStatement>(relaxUnitFun = true)
            every { conn.prepareStatement(match { !it.contains("WHERE 1 = 0") }) } returns ps
            return ps
        }
    }

    fun column(name: String, type: String = "int", jdbcType: Int = Types.INTEGER) =
        TargetColumn(name = name, nullable = true, jdbcType = jdbcType, sqlTypeName = type)

    fun chunk(vararg rows: Array<Any?>) = DataChunk(
        table = "orders",
        columns = listOf(ColumnDescriptor("id", nullable = false), ColumnDescriptor("name", nullable = true)),
        rows = rows.toList(),
        chunkIndex = 0,
    )

    test("dialect and schemaSync are MSSQL-specific") {
        val writer = MssqlDataWriter()
        writer.dialect shouldBe DatabaseDialect.MSSQL
        writer.schemaSync()::class.simpleName shouldBe "MssqlSchemaSync"
    }

    test("openTable sets the session options a filtered index needs, then opens a transaction") {
        val rig = Rig().withColumns(column("id"), column("name", "nvarchar", Types.NVARCHAR))
        rig.writer.openTable(rig.pool, "orders", ImportOptions()).use {
            verify { rig.jdbc.execute(match { it.contains("SET ARITHABORT ON") && it.contains("SET QUOTED_IDENTIFIER ON") }) }
            rig.autoCommit shouldBe false
        }
    }

    test("truncate empties the table outside the import transaction") {
        val rig = Rig().withColumns(column("id"))
        rig.writer.openTable(rig.pool, "sales.orders", ImportOptions(truncate = true)).use {
            verifyOrder {
                rig.jdbc.execute("DELETE FROM [sales].[orders]")
            }
            rig.autoCommit shouldBe false
        }
    }

    test("unsupported trigger and FK-check options are rejected before borrowing work") {
        val rig = Rig().withColumns(column("id"))
        shouldThrow<IllegalStateException> {
            rig.writer.openTable(rig.pool, "orders", ImportOptions(triggerMode = TriggerMode.DISABLE))
        }.message!! shouldContain "triggerMode"
        shouldThrow<IllegalStateException> {
            rig.writer.openTable(rig.pool, "orders", ImportOptions(disableFkChecks = true))
        }.message!! shouldContain "disableFkChecks"
    }

    test("conflict modes other than abort require a primary key") {
        val rig = Rig().withColumns(column("id"))
        shouldThrow<IllegalArgumentException> {
            rig.writer.openTable(rig.pool, "orders", ImportOptions(onConflict = OnConflict.UPDATE))
        }.message!! shouldContain "no primary key"
        verify { rig.conn.close() }
    }

    test("failure during openTable restores autoCommit and returns the connection") {
        val rig = Rig()
        every { rig.conn.prepareStatement(match { it.contains("WHERE 1 = 0") }) } throws RuntimeException("boom")
        shouldThrow<RuntimeException> { rig.writer.openTable(rig.pool, "orders", ImportOptions()) }
        rig.autoCommit shouldBe true
        verify { rig.conn.close() }
    }

    test("abort mode batches inserts and reports the inserted rows") {
        val rig = Rig().withColumns(column("id"), column("name", "nvarchar", Types.NVARCHAR))
        val insert = rig.withInsertStatement()
        every { insert.executeBatch() } returns intArrayOf(1, 1)

        rig.writer.openTable(rig.pool, "orders", ImportOptions(reseedSequences = false)).use { session ->
            val result = session.write(chunk(arrayOf(1, "a"), arrayOf(2, "b")))
            result.rowsInserted shouldBe 2
            result.rowsSkipped shouldBe 0
            session.commitChunk()
            session.finishTable() shouldBe FinishTableResult.Success(emptyList())
        }
        verify { insert.setObject(1, 1) }
        verify { insert.setObject(2, "a") }
    }

    test("identity columns in the chunk switch IDENTITY_INSERT on and off again") {
        val rig = Rig().withColumns(column("id"), column("name", "nvarchar", Types.NVARCHAR))
            .withIdentityColumns("id")
        val insert = rig.withInsertStatement()
        every { insert.executeBatch() } returns intArrayOf(1)

        rig.writer.openTable(rig.pool, "orders", ImportOptions(reseedSequences = false)).use { session ->
            session.write(chunk(arrayOf(7, "seven")))
            verify { rig.jdbc.execute("SET IDENTITY_INSERT [dbo].[orders] ON") }
            session.commitChunk()
            session.finishTable()
        }
        verify { rig.jdbc.execute("SET IDENTITY_INSERT [dbo].[orders] OFF") }
    }

    test("skip and update read the MERGE OUTPUT action per row") {
        val rig = Rig().withColumns(column("id"), column("name", "nvarchar", Types.NVARCHAR))
            .withPrimaryKey("id")
        val merge = rig.withInsertStatement()
        val actions = listOf("INSERT", "UPDATE", null).iterator()
        every { merge.executeQuery() } answers {
            val action = actions.next()
            mockk<ResultSet>(relaxUnitFun = true) {
                every { next() } returns (action != null)
                every { getString(1) } returns action
            }
        }

        rig.writer.openTable(
            rig.pool, "orders", ImportOptions(onConflict = OnConflict.UPDATE, reseedSequences = false),
        ).use { session ->
            val result = session.write(chunk(arrayOf(1, "a"), arrayOf(2, "b"), arrayOf(3, "c")))
            result.rowsInserted shouldBe 1
            result.rowsUpdated shouldBe 1
            result.rowsSkipped shouldBe 1
        }
        val statements = mutableListOf<String>()
        verify { rig.conn.prepareStatement(capture(statements)) }
        statements.last() shouldContain "MERGE INTO [dbo].[orders]"
    }

    test("null values bind through setNull with the target JDBC type") {
        val rig = Rig().withColumns(column("id"), column("name", "nvarchar", Types.NVARCHAR))
        val insert = rig.withInsertStatement()
        every { insert.executeBatch() } returns intArrayOf(1)

        rig.writer.openTable(rig.pool, "orders", ImportOptions(reseedSequences = false)).use { session ->
            session.write(chunk(arrayOf(1, null)))
        }
        verify { insert.setNull(2, Types.NVARCHAR) }
    }

    test("geometry values bind as raw WKB bytes") {
        val rig = Rig().withColumns(column("id"), column("name", "geography", Types.VARBINARY))
        val insert = rig.withInsertStatement()
        every { insert.executeBatch() } returns intArrayOf(1)
        val wkb = byteArrayOf(1, 2, 3)

        rig.writer.openTable(rig.pool, "orders", ImportOptions(reseedSequences = false)).use { session ->
            session.write(chunk(arrayOf(1, wkb)))
        }
        verify { insert.setBytes(2, wkb) }
    }

    test("truncateTables suspends the constraints, empties, and re-enables them") {
        val rig = Rig()
        rig.writer.truncateTables(rig.pool, listOf("child", "sales.parent"))
        verifyOrder {
            rig.jdbc.execute("ALTER TABLE [dbo].[child] NOCHECK CONSTRAINT ALL")
            rig.jdbc.execute("ALTER TABLE [sales].[parent] NOCHECK CONSTRAINT ALL")
            rig.jdbc.execute("DELETE FROM [dbo].[child]")
            rig.jdbc.execute("DELETE FROM [sales].[parent]")
            rig.jdbc.execute("ALTER TABLE [dbo].[child] WITH CHECK CHECK CONSTRAINT ALL")
            rig.jdbc.execute("ALTER TABLE [sales].[parent] WITH CHECK CHECK CONSTRAINT ALL")
        }
    }

    test("truncateTables on an empty list touches nothing") {
        val rig = Rig()
        rig.writer.truncateTables(rig.pool, emptyList())
        verify(exactly = 0) { rig.pool.borrow() }
    }
})
