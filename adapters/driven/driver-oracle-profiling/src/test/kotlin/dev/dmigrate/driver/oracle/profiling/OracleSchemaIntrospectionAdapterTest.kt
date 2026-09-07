package dev.dmigrate.driver.oracle.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.sql.Connection

class OracleSchemaIntrospectionAdapterTest : FunSpec({

    val conn = mockk<Connection>()
    val pool = mockk<ConnectionPool> {
        every { dialect } returns DatabaseDialect.ORACLE
        every { borrow() } returns JdbcDatabaseConnection(conn)
    }
    every { conn.close() } returns Unit

    val jdbc = mockk<JdbcOperations>()
    val adapter = OracleSchemaIntrospectionAdapter(jdbcFactory = { jdbc })

    test("listTables scopes to the current schema and excludes internal tables") {
        val sql = slot<String>()
        every { jdbc.queryList(capture(sql), any()) } returns listOf(
            mapOf("table_schema" to "APP", "table_name" to "USERS"),
        )
        adapter.listTables(pool).map { it.name to it.schema } shouldBe listOf("USERS" to "APP")
        // Ohne Angabe gilt das aktuelle Schema, nicht die ganze Datenbank.
        sql.captured shouldContain "SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')"
        // Dieselben vier Ausschluesse wie im Reverse-Pfad -- alle stehen als
        // gewoehnliche Zeilen in ALL_TABLES.
        sql.captured shouldContain "BIN\$"
        sql.captured shouldContain "secondary = 'Y'"
        sql.captured shouldContain "all_mviews"
        sql.captured shouldContain "all_mview_logs"
    }

    test("a composite primary key marks every participating column, a composite unique none") {
        every {
            jdbc.queryList(match { it.contains("constraint_type IN ('P', 'U')") }, any(), any())
        } returns listOf(
            mapOf("column_name" to "TENANT", "constraint_type" to "P", "column_count" to 2),
            mapOf("column_name" to "ID", "constraint_type" to "P", "column_count" to 2),
            mapOf("column_name" to "A", "constraint_type" to "U", "column_count" to 2),
            mapOf("column_name" to "B", "constraint_type" to "U", "column_count" to 2),
            mapOf("column_name" to "EMAIL", "constraint_type" to "U", "column_count" to 1),
        )
        every { jdbc.queryList(match { it.contains("constraint_type = 'R'") }, any(), any()) } returns
            listOf(mapOf("column_name" to "OWNER_ID"))
        every { jdbc.queryList(match { it.contains("FROM all_tab_columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "TENANT", "data_type" to "NUMBER", "nullable" to "N"),
            mapOf("column_name" to "ID", "data_type" to "NUMBER", "nullable" to "N"),
            mapOf("column_name" to "A", "data_type" to "VARCHAR2", "nullable" to "Y"),
            mapOf("column_name" to "EMAIL", "data_type" to "VARCHAR2", "nullable" to "Y"),
            mapOf("column_name" to "OWNER_ID", "data_type" to "NUMBER", "nullable" to "Y"),
        )

        val columns = adapter.listColumns(pool, "T").associateBy { it.name }
        columns.getValue("TENANT").isPrimaryKey shouldBe true
        columns.getValue("ID").isPrimaryKey shouldBe true
        // Aus einer zusammengesetzten UNIQUE-Constraint folgt nichts ueber die
        // einzelne Spalte.
        columns.getValue("A").isUnique shouldBe false
        columns.getValue("EMAIL").isUnique shouldBe true
        columns.getValue("OWNER_ID").isForeignKey shouldBe true
        columns.getValue("EMAIL").isForeignKey shouldBe false
        columns.getValue("TENANT").nullable shouldBe false
        columns.getValue("A").nullable shouldBe true
    }

    test("the primary key does not also count as unique") {
        // Oracle legt fuer einen Primaerschluessel einen eindeutigen Index an;
        // die beiden Eigenschaften sind im Profiling aber getrennt.
        every {
            jdbc.queryList(match { it.contains("constraint_type IN ('P', 'U')") }, any(), any())
        } returns listOf(
            mapOf("column_name" to "ID", "constraint_type" to "P", "column_count" to 1),
            mapOf("column_name" to "ID", "constraint_type" to "U", "column_count" to 1),
        )
        every { jdbc.queryList(match { it.contains("constraint_type = 'R'") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM all_tab_columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "ID", "data_type" to "NUMBER", "nullable" to "N"),
        )
        val id = adapter.listColumns(pool, "T").single()
        id.isPrimaryKey shouldBe true
        id.isUnique shouldBe false
    }

    test("an explicit schema is folded to upper case, the way Oracle folds an unquoted name") {
        // ALL_TABLES.OWNER traegt den gefalteten Namen; `--schema hr` faende
        // sonst nichts und lieferte einen leeren Bericht ohne Meldung.
        every { jdbc.queryList(any(), any()) } returns emptyList()
        adapter.listTables(pool, schema = "hr")
        verify { jdbc.queryList(any(), "HR") }
    }

    test("a quoted schema keeps its case") {
        every { jdbc.queryList(any(), any()) } returns emptyList()
        adapter.listTables(pool, schema = "\"lowerCase\"")
        verify { jdbc.queryList(any(), "lowerCase") }
    }
})
