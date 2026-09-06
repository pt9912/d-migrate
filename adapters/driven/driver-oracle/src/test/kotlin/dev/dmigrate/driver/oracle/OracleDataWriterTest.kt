package dev.dmigrate.driver.oracle

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
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
 * Deckt die JDBC-Choreografie des Oracle-Import-Pfads ab (Identity-Toggle
 * ALWAYS↔BY DEFAULT, MERGE-Konfliktmodi, FK-Disable/NOVALIDATE-Enable,
 * Reseed, Cleanup) -- die reine SQL-Erzeugung prüft [OracleInsertSqlTest]
 * mock-frei.
 */
class OracleDataWriterTest : FunSpec({

    class Rig {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val jdbc = mockk<JdbcOperations>(relaxed = true)
        val pool = mockk<ConnectionPool>()
        val writer = OracleDataWriter { jdbc }
        var autoCommit = true

        init {
            every { pool.borrow() } returns JdbcDatabaseConnection(conn)
            every { conn.autoCommit } answers { autoCommit }
            every { conn.autoCommit = any() } answers { autoCommit = firstArg() }
            every { jdbc.querySingle(match { it.contains("CURRENT_SCHEMA") }) } returns
                mapOf("schema_name" to "APP")
            every { jdbc.queryList(match { it.contains("all_tab_identity_cols") }, any(), any()) } returns emptyList()
            every { jdbc.queryList(match { it.contains("virtual_column") }, any(), any()) } returns emptyList()
            every { jdbc.queryList(match { it.contains("constraint_type = 'P'") }, any(), any()) } returns emptyList()
            every { jdbc.queryList(match { it.contains("constraint_type = 'R'") }, any(), any()) } returns emptyList()
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
                every { md.getColumnTypeName(pos) } returns (column.sqlTypeName ?: "NUMBER")
            }
        }

        fun withIdentityColumn(name: String, generation: String = "ALWAYS", sequenceName: String = "ISEQ\$\$_1") = apply {
            every { jdbc.queryList(match { it.contains("all_tab_identity_cols") }, any(), any()) } returns
                listOf(
                    mapOf(
                        "column_name" to name, "generation_type" to generation,
                        "sequence_name" to sequenceName, "increment_by" to 1L,
                    ),
                )
        }

        fun withVirtualColumns(vararg names: String) = apply {
            every { jdbc.queryList(match { it.contains("virtual_column") }, any(), any()) } returns
                names.map { mapOf<String, Any?>("column_name" to it) }
        }

        fun withPrimaryKey(vararg names: String) = apply {
            every { jdbc.queryList(match { it.contains("constraint_type = 'P'") }, any(), any()) } returns
                names.map { mapOf<String, Any?>("column_name" to it) }
        }

        fun withForeignKeys(vararg names: String) = apply {
            every { jdbc.queryList(match { it.contains("constraint_type = 'R'") }, any(), any()) } returns
                names.flatMapIndexed { i, name ->
                    listOf(
                        mapOf(
                            "constraint_name" to name, "column_name" to "col$i", "position" to 1,
                            "referenced_table" to "ref", "referenced_column" to "id", "delete_rule" to null,
                        ),
                    )
                }
        }

        fun withInsertStatement(): PreparedStatement {
            val ps = mockk<PreparedStatement>(relaxUnitFun = true)
            every { conn.prepareStatement(match { !it.contains("WHERE 1 = 0") }) } returns ps
            return ps
        }
    }

    fun column(name: String, type: String = "NUMBER", jdbcType: Int = Types.INTEGER) =
        TargetColumn(name = name, nullable = true, jdbcType = jdbcType, sqlTypeName = type)

    fun chunk(vararg rows: Array<Any?>) = DataChunk(
        table = "orders",
        columns = listOf(ColumnDescriptor("id", nullable = false), ColumnDescriptor("name", nullable = true)),
        rows = rows.toList(),
        chunkIndex = 0,
    )

    test("dialect and schemaSync are Oracle-specific") {
        val writer = OracleDataWriter()
        writer.dialect shouldBe DatabaseDialect.ORACLE
        writer.schemaSync()::class.simpleName shouldBe "OracleSchemaSync"
    }

    test("openTable loads target columns and opens a transaction") {
        val rig = Rig().withColumns(column("id"), column("name"))
        rig.writer.openTable(rig.pool, "orders", ImportOptions()).use {
            rig.autoCommit shouldBe false
        }
    }

    test("truncate empties the table outside the import transaction") {
        val rig = Rig().withColumns(column("id"))
        rig.writer.openTable(rig.pool, "sales.orders", ImportOptions(truncate = true)).use {
            verify { rig.jdbc.execute("DELETE FROM \"sales\".\"orders\"") }
            rig.autoCommit shouldBe false
        }
    }

    test("an unsupported trigger option is rejected before borrowing work") {
        val rig = Rig().withColumns(column("id"))
        shouldThrow<IllegalStateException> {
            rig.writer.openTable(rig.pool, "orders", ImportOptions(triggerMode = TriggerMode.DISABLE))
        }.message!! shouldContain "triggerMode"
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
        val rig = Rig().withColumns(column("id"), column("name"))
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

    test("a GENERATED ALWAYS identity column in the chunk toggles the table to BY DEFAULT and back on finish") {
        val rig = Rig().withColumns(column("id"), column("name")).withIdentityColumn("id", generation = "ALWAYS")
        val insert = rig.withInsertStatement()
        every { insert.executeBatch() } returns intArrayOf(1)

        rig.writer.openTable(rig.pool, "orders", ImportOptions(reseedSequences = false)).use { session ->
            session.write(chunk(arrayOf(7, "seven")))
            verify {
                rig.jdbc.execute("ALTER TABLE \"APP\".\"orders\" MODIFY \"id\" GENERATED BY DEFAULT AS IDENTITY")
            }
            // Oracle kennt kein OVERRIDING SYSTEM VALUE (ORA-00926) -- die
            // Klausel darf nach dem Toggle nicht mehr im INSERT auftauchen.
            val statements = mutableListOf<String>()
            verify { rig.conn.prepareStatement(capture(statements)) }
            statements.last().contains("OVERRIDING") shouldBe false
            session.commitChunk()
            session.finishTable()
        }
        verify { rig.jdbc.execute("ALTER TABLE \"APP\".\"orders\" MODIFY \"id\" GENERATED ALWAYS AS IDENTITY") }
    }

    test("a GENERATED BY DEFAULT identity column needs no toggle") {
        val rig = Rig().withColumns(column("id"), column("name")).withIdentityColumn("id", generation = "BY DEFAULT")
        val insert = rig.withInsertStatement()
        every { insert.executeBatch() } returns intArrayOf(1)

        rig.writer.openTable(rig.pool, "orders", ImportOptions(reseedSequences = false)).use { session ->
            session.write(chunk(arrayOf(7, "seven")))
        }
        verify(exactly = 0) { rig.jdbc.execute(match { it.contains("GENERATED BY DEFAULT") }) }
    }

    test("a virtual column in the chunk is rejected with a naming message, not a driver error") {
        val rig = Rig().withColumns(column("id"), column("name")).withVirtualColumns("name")
        rig.withInsertStatement()

        rig.writer.openTable(rig.pool, "orders", ImportOptions(reseedSequences = false)).use { session ->
            shouldThrow<ImportSchemaMismatchException> { session.write(chunk(arrayOf(1, "a"))) }
                .message!! shouldContain "virtual column"
        }
    }

    test("skip: batch counts of 1/0 distinguish inserted from skipped rows") {
        val rig = Rig().withColumns(column("id"), column("name")).withPrimaryKey("id")
        val merge = rig.withInsertStatement()
        every { merge.executeBatch() } returns intArrayOf(1, 0, 1)

        rig.writer.openTable(
            rig.pool, "orders", ImportOptions(onConflict = OnConflict.SKIP, reseedSequences = false),
        ).use { session ->
            val result = session.write(chunk(arrayOf(1, "a"), arrayOf(2, "b"), arrayOf(3, "c")))
            result.rowsInserted shouldBe 2
            result.rowsSkipped shouldBe 1
        }
        val statements = mutableListOf<String>()
        verify { rig.conn.prepareStatement(capture(statements)) }
        statements.last() shouldContain "MERGE INTO \"APP\".\"orders\""
        statements.last() shouldContain "WHEN NOT MATCHED"
    }

    test("update: batch counts cannot distinguish insert from update, so they land in rowsUnknown") {
        val rig = Rig().withColumns(column("id"), column("name")).withPrimaryKey("id")
        val merge = rig.withInsertStatement()
        every { merge.executeBatch() } returns intArrayOf(1, 1)

        rig.writer.openTable(
            rig.pool, "orders", ImportOptions(onConflict = OnConflict.UPDATE, reseedSequences = false),
        ).use { session ->
            val result = session.write(chunk(arrayOf(1, "a"), arrayOf(2, "b")))
            result.rowsInserted shouldBe 0
            result.rowsUpdated shouldBe 0
            result.rowsUnknown shouldBe 2
            result.totalRows shouldBe 2
        }
    }

    test("null values bind through setNull with the target JDBC type") {
        val rig = Rig().withColumns(column("id"), column("name"))
        val insert = rig.withInsertStatement()
        every { insert.executeBatch() } returns intArrayOf(1)

        rig.writer.openTable(rig.pool, "orders", ImportOptions(reseedSequences = false)).use { session ->
            session.write(chunk(arrayOf(1, null)))
        }
        verify { insert.setNull(2, Types.INTEGER) }
    }

    test("skip needs every primary key column in the chunk (the MERGE binds them)") {
        val rig = Rig().withColumns(column("id"), column("name")).withPrimaryKey("id")
        rig.withInsertStatement()

        rig.writer.openTable(
            rig.pool, "orders", ImportOptions(onConflict = OnConflict.SKIP, reseedSequences = false),
        ).use { session ->
            val chunkWithoutPk = DataChunk(
                table = "orders",
                columns = listOf(ColumnDescriptor("name", nullable = true)),
                rows = listOf(arrayOf<Any?>("a")),
                chunkIndex = 0,
            )
            shouldThrow<ImportSchemaMismatchException> { session.write(chunkWithoutPk) }
                .message!! shouldContain "requires all primary key columns"
        }
    }

    test("disableFkChecks disables the table's own FK constraints and re-enables them on finish") {
        val rig = Rig().withColumns(column("id"), column("name")).withForeignKeys("fk_orders_customer")
        val insert = rig.withInsertStatement()
        every { insert.executeBatch() } returns intArrayOf(1)

        rig.writer.openTable(
            rig.pool, "orders", ImportOptions(disableFkChecks = true, reseedSequences = false),
        ).use { session ->
            verify { rig.jdbc.execute("ALTER TABLE \"APP\".\"orders\" DISABLE CONSTRAINT \"fk_orders_customer\"") }
            session.write(chunk(arrayOf(1, "a")))
            session.commitChunk()
            session.finishTable()
        }
        verify { rig.jdbc.execute("ALTER TABLE \"APP\".\"orders\" ENABLE NOVALIDATE CONSTRAINT \"fk_orders_customer\"") }
    }

    test("truncateTables suspends the table's own constraints, empties, and re-enables them") {
        val rig = Rig()
        every { rig.jdbc.queryList(match { it.contains("constraint_type = 'R'") }, "APP", "child") } returns
            listOf(mapOf("constraint_name" to "fk_child_parent", "column_name" to "parent_id", "position" to 1,
                "referenced_table" to "parent", "referenced_column" to "id", "delete_rule" to null))
        every { rig.jdbc.queryList(match { it.contains("constraint_type = 'R'") }, "sales", "parent") } returns
            emptyList()
        rig.writer.truncateTables(rig.pool, listOf("child", "sales.parent"))
        verifyOrder {
            rig.jdbc.execute("ALTER TABLE \"APP\".\"child\" DISABLE CONSTRAINT \"fk_child_parent\"")
            rig.jdbc.execute("DELETE FROM \"APP\".\"child\"")
            rig.jdbc.execute("DELETE FROM \"sales\".\"parent\"")
            rig.jdbc.execute("ALTER TABLE \"APP\".\"child\" ENABLE NOVALIDATE CONSTRAINT \"fk_child_parent\"")
        }
    }

    test("truncateTables on an empty list touches nothing") {
        val rig = Rig()
        rig.writer.truncateTables(rig.pool, emptyList())
        verify(exactly = 0) { rig.pool.borrow() }
    }

    test("a failure while suspending constraints still re-enables the tables it already suspended") {
        val rig = Rig()
        every { rig.jdbc.queryList(match { it.contains("constraint_type = 'R'") }, "APP", "good") } returns
            listOf(mapOf("constraint_name" to "fk_good", "column_name" to "x", "position" to 1,
                "referenced_table" to "t", "referenced_column" to "id", "delete_rule" to null))
        every { rig.jdbc.queryList(match { it.contains("constraint_type = 'R'") }, "APP", "bad") } returns
            emptyList()
        every {
            rig.jdbc.execute(match { it.contains("\"bad\"") && it.contains("DELETE") })
        } throws RuntimeException("no permission")

        shouldThrow<RuntimeException> { rig.writer.truncateTables(rig.pool, listOf("good", "bad")) }
        // `good` wurde ausgesetzt und muss wieder scharf sein.
        verify { rig.jdbc.execute("ALTER TABLE \"APP\".\"good\" ENABLE NOVALIDATE CONSTRAINT \"fk_good\"") }
    }
})
