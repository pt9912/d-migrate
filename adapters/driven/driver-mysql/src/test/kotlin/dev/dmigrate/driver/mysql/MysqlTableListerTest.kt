package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

class MysqlTableListerTest : FunSpec({

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns conn
    }
    val jdbc = mockk<JdbcOperations>()
    val lister = MysqlTableLister(jdbcFactory = { jdbc })

    // Mock currentDatabase(conn) — uses conn.catalog first
    every { conn.catalog } returns "mydb"

    test("dialect is MYSQL") {
        lister.dialect shouldBe DatabaseDialect.MYSQL
    }

    test("listTables returns table names") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
            mapOf("table_name" to "orders", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        val result = lister.listTables(pool)
        result shouldBe listOf("users", "orders")
    }

    test("listTables returns empty list for empty database") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns emptyList()
        lister.listTables(pool).shouldBeEmpty()
    }

    test("listTables uses currentDatabase fallback when conn.catalog is null") {
        val conn2 = mockk<Connection>(relaxUnitFun = true)
        val pool2 = mockk<ConnectionPool> {
            every { borrow() } returns conn2
        }
        val jdbc2 = mockk<JdbcOperations>()
        val lister2 = MysqlTableLister(jdbcFactory = { jdbc2 })

        // conn.catalog returns null → fallback to SQL query
        every { conn2.catalog } returns null
        val stmt2 = mockk<Statement>(relaxUnitFun = true)
        val rs2 = mockk<ResultSet>(relaxUnitFun = true)
        every { conn2.createStatement() } returns stmt2
        every { stmt2.executeQuery("SELECT DATABASE()") } returns rs2
        every { rs2.next() } returns true
        every { rs2.getString(1) } returns "fallback_db"

        every { jdbc2.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "t1", "table_schema" to "fallback_db", "table_type" to "BASE TABLE"),
        )

        val result = lister2.listTables(pool2)
        result shouldBe listOf("t1")
    }
})
