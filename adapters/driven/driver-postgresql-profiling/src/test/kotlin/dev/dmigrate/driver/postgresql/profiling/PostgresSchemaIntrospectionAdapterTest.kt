package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

class PostgresSchemaIntrospectionAdapterTest : FunSpec({

    val conn = mockk<Connection>()
    val pool = mockk<ConnectionPool> {
        every { dialect } returns DatabaseDialect.POSTGRESQL
        every { borrow() } returns conn
    }
    every { conn.close() } returns Unit

    val jdbc = mockk<JdbcOperations>()
    val adapter = PostgresSchemaIntrospectionAdapter(jdbcFactory = { jdbc })

    test("listTables returns tables from information_schema") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "public"),
            mapOf("table_name" to "orders", "table_schema" to "public"),
        )
        val tables = adapter.listTables(pool)
        tables shouldHaveSize 2
        tables[0].name shouldBe "users"
    }

    test("listTables uses provided schema") {
        every { jdbc.queryList(any(), "custom") } returns listOf(
            mapOf("table_name" to "t1", "table_schema" to "custom"),
        )
        val tables = adapter.listTables(pool, schema = "custom")
        tables shouldHaveSize 1
        tables[0].schema shouldBe "custom"
    }

    test("listColumns returns column metadata with PK/FK/unique flags") {
        // PK query
        every { jdbc.queryList(match { it.contains("indisprimary") }, any()) } returns listOf(
            mapOf("attname" to "id"),
        )
        // FK query
        every { jdbc.queryList(match { it.contains("FOREIGN KEY") }, any(), any()) } returns listOf(
            mapOf("column_name" to "order_id"),
        )
        // Unique query
        every { jdbc.queryList(match { it.contains("indisunique") }, any()) } returns listOf(
            mapOf("attname" to "email"),
        )
        // Columns query
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "udt_name" to "int4", "is_nullable" to "NO"),
            mapOf("column_name" to "email", "data_type" to "character varying", "udt_name" to "varchar", "is_nullable" to "YES"),
            mapOf("column_name" to "order_id", "data_type" to "integer", "udt_name" to "int4", "is_nullable" to "YES"),
        )
        val cols = adapter.listColumns(pool, "users")
        cols shouldHaveSize 3
        cols[0].name shouldBe "id"
        cols[0].isPrimaryKey shouldBe true
        cols[1].name shouldBe "email"
        cols[1].isUnique shouldBe true
        cols[2].name shouldBe "order_id"
        cols[2].isForeignKey shouldBe true
    }

    test("listColumns resolves USER-DEFINED types via udt_name") {
        every { jdbc.queryList(match { it.contains("indisprimary") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FOREIGN KEY") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("indisunique") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "status", "data_type" to "USER-DEFINED", "udt_name" to "order_status", "is_nullable" to "NO"),
        )
        val cols = adapter.listColumns(pool, "orders")
        cols[0].dbType shouldBe "order_status"
    }
})
