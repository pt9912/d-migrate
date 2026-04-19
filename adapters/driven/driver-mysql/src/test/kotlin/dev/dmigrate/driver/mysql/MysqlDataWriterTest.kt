package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types

class MysqlDataWriterTest : FunSpec({

    // ── shared setup ──────────────────────────────────

    fun buildMocks(): TestMocks {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val pool = mockk<ConnectionPool> {
            every { borrow() } returns conn
        }
        val jdbc = mockk<JdbcOperations>(relaxUnitFun = true)
        val jdbcFactory: (Connection) -> JdbcOperations = { jdbc }
        val writer = MysqlDataWriter(jdbcFactory)

        // Default: autoCommit = true
        every { conn.autoCommit } returns true
        every { conn.catalog } returns "testdb"

        // lowerCaseTableNames(conn) — raw JDBC
        val lctnStmt = mockk<Statement>(relaxUnitFun = true)
        val lctnRs = mockk<ResultSet>(relaxUnitFun = true)
        every { conn.createStatement() } returns lctnStmt
        every { lctnStmt.executeQuery("SELECT @@lower_case_table_names") } returns lctnRs
        every { lctnRs.next() } returns true
        every { lctnRs.getInt(1) } returns 0

        return TestMocks(conn, pool, jdbc, writer)
    }

    /**
     * Stubs loadTargetColumns: conn.prepareStatement("SELECT * FROM ... LIMIT 0")
     * returning a ResultSet whose metaData describes the given columns.
     */
    fun TestMocks.stubTargetColumns(vararg columns: TargetColumn) {
        val ps = mockk<PreparedStatement>(relaxUnitFun = true)
        val rs = mockk<ResultSet>(relaxUnitFun = true)
        val md = mockk<ResultSetMetaData>()

        every { conn.prepareStatement(match { it.contains("LIMIT 0") }) } returns ps
        every { ps.executeQuery() } returns rs
        every { rs.metaData } returns md
        every { md.columnCount } returns columns.size
        columns.forEachIndexed { i, col ->
            val pos = i + 1
            every { md.getColumnLabel(pos) } returns col.name
            every { md.isNullable(pos) } returns
                if (col.nullable) ResultSetMetaData.columnNullable else ResultSetMetaData.columnNoNulls
            every { md.getColumnType(pos) } returns col.jdbcType
            every { md.getColumnTypeName(pos) } returns (col.sqlTypeName ?: "INT")
        }
    }

    /**
     * Stubs loadPrimaryKeyColumns via conn.metaData.getPrimaryKeys().
     */
    fun TestMocks.stubPrimaryKeys(vararg pkColumns: String) {
        val dbMeta = mockk<DatabaseMetaData>()
        val pkRs = mockk<ResultSet>(relaxUnitFun = true)
        every { conn.metaData } returns dbMeta
        every { dbMeta.getPrimaryKeys(any(), any(), any()) } returns pkRs

        var callIndex = 0
        every { pkRs.next() } answers {
            callIndex < pkColumns.size && true.also { callIndex++ }
        }
        pkColumns.forEachIndexed { i, col ->
            // KEY_SEQ is 1-based
            every { pkRs.getShort("KEY_SEQ") } answers {
                // Return based on call order; each next() advances
                (i + 1).toShort()
            }
        }
        // Need per-call answers for ordered PK columns
        val keySeqAnswers = pkColumns.indices.map { (it + 1).toShort() }.iterator()
        val colNameAnswers = pkColumns.iterator()
        every { pkRs.getShort("KEY_SEQ") } answers { keySeqAnswers.next() }
        every { pkRs.getString("COLUMN_NAME") } answers { colNameAnswers.next() }
    }

    fun TestMocks.stubNoPrimaryKeys() {
        val dbMeta = mockk<DatabaseMetaData>()
        val pkRs = mockk<ResultSet>(relaxUnitFun = true)
        every { conn.metaData } returns dbMeta
        every { dbMeta.getPrimaryKeys(any(), any(), any()) } returns pkRs
        every { pkRs.next() } returns false
    }

    // ── tests ─────────────────────────────────────────

    test("dialect is MYSQL") {
        val writer = MysqlDataWriter()
        writer.dialect shouldBe DatabaseDialect.MYSQL
    }

    test("schemaSync returns MysqlSchemaSync") {
        val jdbc = mockk<JdbcOperations>()
        val writer = MysqlDataWriter(jdbcFactory = { jdbc })
        writer.schemaSync().shouldBeInstanceOf<MysqlSchemaSync>()
    }

    test("openTable with defaults creates session with correct targetColumns") {
        val mocks = buildMocks()
        val columns = listOf(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INT"),
            TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
        )
        mocks.stubTargetColumns(*columns.toTypedArray())

        val session = mocks.writer.openTable(mocks.pool, "users", ImportOptions())

        session.targetColumns shouldContainExactly columns
        // autoCommit should be set to false for the transaction
        verify { mocks.conn.autoCommit = false }
        session.close()
    }

    test("openTable with triggerMode=DISABLE throws UnsupportedTriggerModeException") {
        val mocks = buildMocks()
        shouldThrow<UnsupportedTriggerModeException> {
            mocks.writer.openTable(mocks.pool, "users", ImportOptions(triggerMode = TriggerMode.DISABLE))
        }
    }

    test("openTable with triggerMode=STRICT throws UnsupportedTriggerModeException") {
        val mocks = buildMocks()
        shouldThrow<UnsupportedTriggerModeException> {
            mocks.writer.openTable(mocks.pool, "users", ImportOptions(triggerMode = TriggerMode.STRICT))
        }
    }

    test("openTable with disableFkChecks executes SET FOREIGN_KEY_CHECKS = 0") {
        val mocks = buildMocks()
        mocks.stubTargetColumns(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
        )
        every { mocks.jdbc.execute(any(), *anyVararg()) } returns 0

        val session = mocks.writer.openTable(
            mocks.pool, "users",
            ImportOptions(disableFkChecks = true),
        )

        verify { mocks.jdbc.execute("SET FOREIGN_KEY_CHECKS = 0") }
        session.close()
    }

    test("openTable with truncate executes DELETE FROM") {
        val mocks = buildMocks()
        mocks.stubTargetColumns(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
        )
        every { mocks.jdbc.execute(any(), *anyVararg()) } returns 0

        val session = mocks.writer.openTable(
            mocks.pool, "users",
            ImportOptions(truncate = true),
        )

        verify { mocks.jdbc.execute(match { it.contains("DELETE FROM") && it.contains("users") }) }
        session.close()
    }

    test("openTable with onConflict=UPDATE loads primary keys") {
        val mocks = buildMocks()
        mocks.stubTargetColumns(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
            TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR),
        )
        mocks.stubPrimaryKeys("id")

        val session = mocks.writer.openTable(
            mocks.pool, "users",
            ImportOptions(onConflict = OnConflict.UPDATE),
        )

        verify { mocks.conn.metaData }
        session.close()
    }

    test("openTable with onConflict=UPDATE and no PK throws") {
        val mocks = buildMocks()
        mocks.stubTargetColumns(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
        )
        mocks.stubNoPrimaryKeys()

        val ex = shouldThrow<IllegalArgumentException> {
            mocks.writer.openTable(
                mocks.pool, "users",
                ImportOptions(onConflict = OnConflict.UPDATE),
            )
        }
        ex.message shouldBe "Target table 'users' has no primary key; onConflict=update requires a primary key"
    }

    test("openTable cleanup on error resets FK checks and closes connection") {
        val mocks = buildMocks()
        mocks.stubTargetColumns(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
        )
        // disableFkChecks succeeds, then truncate throws
        var fkCallCount = 0
        every { mocks.jdbc.execute(any(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            if (sql.contains("FOREIGN_KEY_CHECKS = 0")) {
                fkCallCount++
                0
            } else if (sql.contains("DELETE FROM")) {
                throw RuntimeException("truncate failed")
            } else if (sql.contains("FOREIGN_KEY_CHECKS = 1")) {
                fkCallCount++
                0
            } else {
                0
            }
        }

        shouldThrow<RuntimeException> {
            mocks.writer.openTable(
                mocks.pool, "users",
                ImportOptions(disableFkChecks = true, truncate = true),
            )
        }

        // FK checks should have been re-enabled in cleanup
        verify { mocks.jdbc.execute("SET FOREIGN_KEY_CHECKS = 1") }
        // Connection should have been closed
        verify { mocks.conn.close() }
    }

    test("openTable cleanup on error without FK checks still closes connection") {
        val mocks = buildMocks()
        // Make loadTargetColumns throw
        every { mocks.conn.prepareStatement(match { it.contains("LIMIT 0") }) } throws
            RuntimeException("metadata load failed")

        shouldThrow<RuntimeException> {
            mocks.writer.openTable(mocks.pool, "users", ImportOptions())
        }

        verify { mocks.conn.close() }
        // FK checks should NOT have been reset (never disabled)
        verify(exactly = 0) { mocks.jdbc.execute("SET FOREIGN_KEY_CHECKS = 1") }
    }
})

private data class TestMocks(
    val conn: Connection,
    val pool: ConnectionPool,
    val jdbc: JdbcOperations,
    val writer: MysqlDataWriter,
)
