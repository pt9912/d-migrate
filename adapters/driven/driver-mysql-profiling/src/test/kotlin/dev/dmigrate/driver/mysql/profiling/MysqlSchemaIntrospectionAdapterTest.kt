package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

class MysqlSchemaIntrospectionAdapterTest : FunSpec({

    val conn = mockk<Connection>()
    val pool = mockk<ConnectionPool> {
        every { dialect } returns DatabaseDialect.MYSQL
        every { borrow() } returns conn
    }
    every { conn.close() } returns Unit

    val jdbc = mockk<JdbcOperations>()
    val adapter = MysqlSchemaIntrospectionAdapter(jdbcFactory = { jdbc })

    test("listTables returns tables from information_schema") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }) } returns listOf(
            mapOf("table_name" to "users"),
            mapOf("table_name" to "orders"),
        )
        val tables = adapter.listTables(pool)
        tables shouldHaveSize 2
        tables[0].name shouldBe "users"
    }

    test("listColumns returns column metadata with FK flag") {
        every { jdbc.queryList(match { it.contains("key_column_usage") }, any()) } returns listOf(
            mapOf("column_name" to "order_id"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any()) } returns listOf(
            mapOf("column_name" to "id", "column_type" to "int", "is_nullable" to "NO", "column_key" to "PRI"),
            mapOf("column_name" to "order_id", "column_type" to "int", "is_nullable" to "YES", "column_key" to "MUL"),
            mapOf("column_name" to "email", "column_type" to "varchar(254)", "is_nullable" to "YES", "column_key" to "UNI"),
        )
        val cols = adapter.listColumns(pool, "users")
        cols shouldHaveSize 3
        cols[0].isPrimaryKey shouldBe true
        cols[1].isForeignKey shouldBe true
        cols[2].isUnique shouldBe true
    }
})
