package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

class MssqlTableListerTest : FunSpec({

    fun rig(jdbc: JdbcOperations): Pair<MssqlTableLister, ConnectionPool> {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val pool = mockk<ConnectionPool> {
            every { borrow() } returns JdbcDatabaseConnection(conn)
        }
        return MssqlTableLister(jdbcFactory = { jdbc }) to pool
    }

    test("dialect is MSSQL") {
        MssqlTableLister().dialect shouldBe DatabaseDialect.MSSQL
    }

    test("lists tables of the current schema in catalog order") {
        val jdbc = mockk<JdbcOperations> {
            every { querySingle(match { it.contains("SCHEMA_NAME()") }) } returns
                mapOf("schema_name" to "sales")
            every { queryList(match { it.contains("FROM sys.tables t") }, "sales") } returns listOf(
                mapOf("table_name" to "customers", "schema_name" to "sales"),
                mapOf("table_name" to "orders", "schema_name" to "sales"),
            )
        }
        val (lister, pool) = rig(jdbc)
        lister.listTables(pool) shouldBe listOf("customers", "orders")
    }

    test("empty schema lists nothing") {
        val jdbc = mockk<JdbcOperations> {
            every { querySingle(match { it.contains("SCHEMA_NAME()") }) } returns
                mapOf("schema_name" to "dbo")
            every { queryList(match { it.contains("FROM sys.tables t") }, "dbo") } returns emptyList()
        }
        val (lister, pool) = rig(jdbc)
        lister.listTables(pool) shouldBe emptyList()
    }

    test("driver exposes this lister") {
        MssqlDriver().tableLister()::class.simpleName shouldBe "MssqlTableLister"
    }
})
