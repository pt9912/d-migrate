package dev.dmigrate.driver.postgresql

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

class PostgresTableListerTest : FunSpec({

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns conn
    }
    val jdbc = mockk<JdbcOperations>()
    val lister = PostgresTableLister(jdbcFactory = { jdbc })

    // Mock currentSchema(conn)
    val stmt = mockk<Statement>(relaxUnitFun = true)
    val rs = mockk<ResultSet>(relaxUnitFun = true)
    every { conn.createStatement() } returns stmt
    every { stmt.executeQuery("SELECT current_schema()") } returns rs
    every { rs.next() } returns true
    every { rs.getString(1) } returns "public"

    test("dialect is POSTGRESQL") {
        lister.dialect shouldBe DatabaseDialect.POSTGRESQL
    }

    test("listTables returns table names") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "public", "table_type" to "BASE TABLE"),
            mapOf("table_name" to "orders", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        val result = lister.listTables(pool)
        result shouldBe listOf("users", "orders")
    }

    test("listTables returns empty list for empty schema") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns emptyList()
        lister.listTables(pool).shouldBeEmpty()
    }
})
