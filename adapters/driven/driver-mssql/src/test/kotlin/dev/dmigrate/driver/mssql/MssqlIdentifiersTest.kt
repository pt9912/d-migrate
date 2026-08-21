package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

class MssqlIdentifiersTest : FunSpec({

    test("bracket quotes and escapes closing brackets") {
        MssqlIdentifiers.bracket("users") shouldBe "[users]"
        MssqlIdentifiers.bracket("a]b") shouldBe "[a]]b]"
    }

    test("qualified quotes both segments") {
        MssqlIdentifiers.qualified("dbo", "users") shouldBe "[dbo].[users]"
        MssqlIdentifiers.qualified("s]x", "t]y") shouldBe "[s]]x].[t]]y]"
    }

    test("currentSchema reads SCHEMA_NAME()") {
        val session = mockk<JdbcOperations> {
            every { querySingle(match { it.contains("SCHEMA_NAME()") }) } returns
                mapOf("schema_name" to "sales")
        }
        MssqlIdentifiers.currentSchema(session) shouldBe "sales"
    }

    test("currentSchema falls back to dbo when the probe yields nothing") {
        val session = mockk<JdbcOperations> {
            every { querySingle(any()) } returns null
        }
        MssqlIdentifiers.currentSchema(session) shouldBe "dbo"
    }

    test("currentDatabase prefers the connection catalog") {
        val conn = mockk<Connection> { every { catalog } returns "shopdb" }
        MssqlIdentifiers.currentDatabase(conn) shouldBe "shopdb"
    }

    test("currentDatabase falls back to DB_NAME() when catalog is null") {
        val rs = mockk<java.sql.ResultSet>(relaxUnitFun = true) {
            every { next() } returns true
            every { getString(1) } returns "shopdb"
            every { close() } returns Unit
        }
        val stmt = mockk<java.sql.Statement>(relaxUnitFun = true) {
            every { executeQuery("SELECT DB_NAME()") } returns rs
            every { close() } returns Unit
        }
        val conn = mockk<Connection> {
            every { catalog } returns null
            every { createStatement() } returns stmt
        }
        MssqlIdentifiers.currentDatabase(conn) shouldBe "shopdb"
    }
})
