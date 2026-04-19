package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types

class PostgresDataWriterTest : FunSpec({

    lateinit var conn: Connection
    lateinit var pool: ConnectionPool
    lateinit var jdbc: JdbcOperations

    fun newWriter(jdbcOps: JdbcOperations = jdbc): PostgresDataWriter =
        PostgresDataWriter(jdbcFactory = { jdbcOps })

    /**
     * Sets up the mock chain for loadTargetColumns:
     * conn.prepareStatement → PreparedStatement → ResultSet → ResultSetMetaData
     *
     * Returns the list of TargetColumn that will be produced.
     */
    fun mockTargetColumns(
        table: QualifiedTableName,
        columns: List<TargetColumn>,
    ) {
        val rsMeta = mockk<ResultSetMetaData>()
        every { rsMeta.columnCount } returns columns.size
        columns.forEachIndexed { i, col ->
            every { rsMeta.getColumnLabel(i + 1) } returns col.name
            every { rsMeta.isNullable(i + 1) } returns
                if (col.nullable) ResultSetMetaData.columnNullable else ResultSetMetaData.columnNoNulls
            every { rsMeta.getColumnType(i + 1) } returns col.jdbcType
            every { rsMeta.getColumnTypeName(i + 1) } returns col.sqlTypeName
        }
        val rs = mockk<ResultSet>(relaxUnitFun = true)
        every { rs.metaData } returns rsMeta

        val ps = mockk<PreparedStatement>(relaxUnitFun = true)
        every { ps.executeQuery() } returns rs

        every {
            conn.prepareStatement("SELECT * FROM ${table.quotedPath()} LIMIT 0")
        } returns ps
    }

    /**
     * Sets up mocks for loadGeneratedAlwaysColumns via JdbcOperations.queryList.
     */
    fun mockGeneratedAlwaysColumns(generatedCols: Set<String>) {
        every {
            jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any())
        } returns generatedCols.map { mapOf("column_name" to it) }
    }

    /**
     * Sets up mocks for currentSchema (needed by schemaOrCurrent when schema is null).
     */
    fun mockCurrentSchema(schema: String = "public") {
        val stmt = mockk<Statement>(relaxUnitFun = true)
        val rs = mockk<ResultSet>(relaxUnitFun = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery("SELECT current_schema()") } returns rs
        every { rs.next() } returns true
        every { rs.getString(1) } returns schema
    }

    /**
     * Sets up mocks for loadPrimaryKeyColumns via conn.metaData.getPrimaryKeys.
     */
    fun mockPrimaryKeys(pkColumns: List<String>) {
        val dbMeta = mockk<DatabaseMetaData>()
        every { conn.metaData } returns dbMeta
        val rs = mockk<ResultSet>(relaxUnitFun = true)
        every { dbMeta.getPrimaryKeys(any(), any(), any()) } returns rs

        val iterator = pkColumns.iterator()
        var callCount = 0
        every { rs.next() } answers {
            iterator.hasNext().also { if (it) callCount++ }
        }
        every { rs.getShort("KEY_SEQ") } answers {
            callCount.toShort()
        }
        every { rs.getString("COLUMN_NAME") } answers {
            iterator.next()
        }
    }

    /**
     * Convenience: set up full "happy path" mocks for a simple table with schema.
     */
    fun setupDefaultMocks(
        tableName: String = "public.users",
        columns: List<TargetColumn> = listOf(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "int4"),
            TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "varchar"),
        ),
        generatedAlways: Set<String> = emptySet(),
        pkColumns: List<String> = emptyList(),
    ) {
        val qualified = parseQualifiedTableName(tableName)

        mockTargetColumns(qualified, columns)
        mockGeneratedAlwaysColumns(generatedAlways)

        // For schema-qualified names, schemaOrCurrent returns the schema directly
        // so currentSchema mock is not needed. For unqualified names, we mock it.
        if (qualified.schema == null) {
            mockCurrentSchema()
        }

        if (pkColumns.isNotEmpty()) {
            mockPrimaryKeys(pkColumns)
        }
    }

    beforeEach {
        conn = mockk<Connection>(relaxUnitFun = true)
        every { conn.autoCommit } returns true
        every { conn.catalog } returns "testdb"

        pool = mockk<ConnectionPool>()
        every { pool.borrow() } returns conn

        jdbc = mockk<JdbcOperations>()
    }

    afterEach {
        clearMocks(conn, pool, jdbc)
    }

    // ── 1. dialect ──────────────────────────────────────

    test("dialect is POSTGRESQL") {
        val writer = newWriter()
        writer.dialect shouldBe DatabaseDialect.POSTGRESQL
    }

    // ── 2. schemaSync ───────────────────────────────────

    test("schemaSync returns PostgresSchemaSync") {
        val writer = newWriter()
        writer.schemaSync().shouldBeInstanceOf<PostgresSchemaSync>()
    }

    // ── 3. openTable with defaults creates session with correct targetColumns ──

    test("openTable with defaults creates session with correct targetColumns") {
        val columns = listOf(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "int4"),
            TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "varchar"),
        )
        setupDefaultMocks(columns = columns)

        val writer = newWriter()
        val session = writer.openTable(pool, "public.users", ImportOptions())

        try {
            session.targetColumns shouldBe columns
        } finally {
            session.close()
        }
    }

    // ── 4. openTable with disableFkChecks throws UnsupportedOperationException ──

    test("openTable with disableFkChecks throws UnsupportedOperationException") {
        val writer = newWriter()

        val ex = shouldThrow<UnsupportedOperationException> {
            writer.openTable(pool, "public.users", ImportOptions(disableFkChecks = true))
        }
        ex.message shouldContain "disableFkChecks"
    }

    // ── 5. openTable with truncate executes TRUNCATE via JdbcOperations ──

    test("openTable with truncate executes TRUNCATE via JdbcOperations") {
        setupDefaultMocks()
        every {
            jdbc.execute(match { it.contains("TRUNCATE TABLE") })
        } returns 0

        val writer = newWriter()
        val session = writer.openTable(pool, "public.users", ImportOptions(truncate = true))

        try {
            verify {
                jdbc.execute(match {
                    it.contains("TRUNCATE TABLE") && it.contains("RESTART IDENTITY CASCADE")
                })
            }
        } finally {
            session.close()
        }
    }

    // ── 6. openTable with onConflict=UPDATE loads primary keys ──

    test("openTable with onConflict=UPDATE loads primary keys") {
        setupDefaultMocks(pkColumns = listOf("id"))
        mockPrimaryKeys(listOf("id"))

        val writer = newWriter()
        val session = writer.openTable(
            pool,
            "public.users",
            ImportOptions(onConflict = OnConflict.UPDATE),
        )

        try {
            session.targetColumns.map { it.name } shouldBe listOf("id", "name")
        } finally {
            session.close()
        }
    }

    // ── 7. openTable with onConflict=UPDATE and no PK throws ──

    test("openTable with onConflict=UPDATE and no PK throws IllegalArgumentException") {
        setupDefaultMocks(pkColumns = emptyList())
        // mock empty PK result
        val dbMeta = mockk<DatabaseMetaData>()
        every { conn.metaData } returns dbMeta
        val pkRs = mockk<ResultSet>(relaxUnitFun = true)
        every { dbMeta.getPrimaryKeys(any(), any(), any()) } returns pkRs
        every { pkRs.next() } returns false

        val writer = newWriter()

        val ex = shouldThrow<IllegalArgumentException> {
            writer.openTable(
                pool,
                "public.users",
                ImportOptions(onConflict = OnConflict.UPDATE),
            )
        }
        ex.message shouldContain "no primary key"

        // Connection should be closed on error
        verify { conn.close() }
    }

    // ── 8. openTable with triggerMode=STRICT calls assertNoUserTriggers ──

    test("openTable with triggerMode=STRICT calls assertNoUserTriggers") {
        // For STRICT mode, schemaSync.assertNoUserTriggers is called.
        // The SchemaSync internally uses jdbcFactory, so we verify via the JdbcOperations mock.
        setupDefaultMocks()

        // assertNoUserTriggers internally calls jdbc.querySingle with pg_trigger query
        // SchemaSync also needs currentSchema mock for the non-qualified table part
        val stmt = mockk<Statement>(relaxUnitFun = true)
        val rs = mockk<ResultSet>(relaxUnitFun = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery("SELECT current_schema()") } returns rs
        every { rs.next() } returns true
        every { rs.getString(1) } returns "public"

        // SchemaSync.assertNoUserTriggers queries pg_trigger - no triggers found
        every {
            jdbc.querySingle(match { it.contains("pg_trigger") }, any(), any())
        } returns null

        val writer = newWriter()
        val session = writer.openTable(
            pool,
            "public.users",
            ImportOptions(triggerMode = TriggerMode.STRICT),
        )

        try {
            verify {
                jdbc.querySingle(match { it.contains("pg_trigger") }, any(), any())
            }
        } finally {
            session.close()
        }
    }

    // ── 9. openTable with triggerMode=DISABLE calls disableTriggers ──

    test("openTable with triggerMode=DISABLE calls disableTriggers") {
        setupDefaultMocks()

        // disableTriggers internally runs ALTER TABLE ... DISABLE TRIGGER USER
        every {
            jdbc.execute(match { it.contains("DISABLE TRIGGER USER") })
        } returns 0

        val writer = newWriter()
        val session = writer.openTable(
            pool,
            "public.users",
            ImportOptions(triggerMode = TriggerMode.DISABLE),
        )

        try {
            verify {
                jdbc.execute(match { it.contains("DISABLE TRIGGER USER") })
            }
        } finally {
            session.close()
        }
    }

    // ── 10. openTable cleanup on error closes connection ──

    test("openTable cleanup on error closes connection") {
        val qualified = parseQualifiedTableName("public.users")

        // Make loadTargetColumns fail by having prepareStatement throw
        every {
            conn.prepareStatement("SELECT * FROM ${qualified.quotedPath()} LIMIT 0")
        } throws RuntimeException("connection broken")

        val writer = newWriter()

        shouldThrow<RuntimeException> {
            writer.openTable(pool, "public.users", ImportOptions())
        }.message shouldBe "connection broken"

        verify { conn.close() }
        verify { conn.rollback() }
    }
})
