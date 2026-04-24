package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
            mapOf("table_schema" to "app", "table_name" to "users"),
            mapOf("table_schema" to "app", "table_name" to "orders"),
        )
        val tables = adapter.listTables(pool)
        tables shouldHaveSize 2
        tables[0].name shouldBe "users"
        tables[0].schema shouldBe "app"
    }

    test("listTables uses provided schema instead of DATABASE()") {
        clearMocks(jdbc)
        every { jdbc.queryList(any(), "tenant`one") } returns listOf(
            mapOf("table_schema" to "tenant`one", "table_name" to "users"),
        )

        val tables = adapter.listTables(pool, schema = "tenant`one")

        tables shouldHaveSize 1
        tables[0].schema shouldBe "tenant`one"
        verify {
            jdbc.queryList(
                match { it.contains("table_schema = ?") && !it.contains("DATABASE()") },
                "tenant`one",
            )
        }
    }

    test("listColumns returns column metadata with FK flag") {
        every { jdbc.queryList(match { it.contains("key_column_usage") }, any()) } returns listOf(
            mapOf("column_name" to "order_id"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any()) } returns listOf(
            mapOf("column_name" to "id", "column_type" to "int", "is_nullable" to "NO", "column_key" to "PRI"),
            mapOf("column_name" to "order_id", "column_type" to "int", "is_nullable" to "YES", "column_key" to "MUL"),
            mapOf(
                "column_name" to "email",
                "column_type" to "varchar(254)",
                "is_nullable" to "YES",
                "column_key" to "UNI",
            ),
        )
        val cols = adapter.listColumns(pool, "users")
        cols shouldHaveSize 3
        cols[0].isPrimaryKey shouldBe true
        cols[1].isForeignKey shouldBe true
        cols[2].isUnique shouldBe true
    }

    test("listColumns scopes metadata queries to provided schema") {
        clearMocks(jdbc)
        every { jdbc.queryList(match { it.contains("key_column_usage") }, "tenant.one", "users") } returns emptyList()
        every {
            jdbc.queryList(match { it.contains("information_schema.columns") }, "tenant.one", "users")
        } returns listOf(
            mapOf("column_name" to "id", "column_type" to "int", "is_nullable" to "NO", "column_key" to "PRI"),
        )

        val cols = adapter.listColumns(pool, "users", schema = "tenant.one")

        cols shouldHaveSize 1
        verify(atLeast = 2) {
            jdbc.queryList(
                match { it.contains("table_schema = ?") && it.contains("table_name = ?") },
                "tenant.one",
                "users",
            )
        }
    }
})
