package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

class OracleTableListerTest : FunSpec({

    fun rig(jdbc: JdbcOperations): Pair<OracleTableLister, ConnectionPool> {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val pool = mockk<ConnectionPool> {
            every { borrow() } returns JdbcDatabaseConnection(conn)
        }
        return OracleTableLister(jdbcFactory = { jdbc }) to pool
    }

    test("dialect is ORACLE") {
        OracleTableLister().dialect shouldBe DatabaseDialect.ORACLE
    }

    test("lists tables of the current schema in catalog order") {
        val jdbc = mockk<JdbcOperations> {
            every { querySingle(match { it.contains("SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')") }) } returns
                mapOf("schema_name" to "SALES")
            every { queryList(match { it.contains("FROM all_tables") }, "SALES") } returns listOf(
                mapOf("table_name" to "CUSTOMERS"),
                mapOf("table_name" to "ORDERS"),
            )
        }
        val (lister, pool) = rig(jdbc)
        lister.listTables(pool) shouldBe listOf("CUSTOMERS", "ORDERS")
    }

    test("empty schema lists nothing") {
        val jdbc = mockk<JdbcOperations> {
            every { querySingle(match { it.contains("SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')") }) } returns
                mapOf("schema_name" to "APP")
            every { queryList(match { it.contains("FROM all_tables") }, "APP") } returns emptyList()
        }
        val (lister, pool) = rig(jdbc)
        lister.listTables(pool) shouldBe emptyList()
    }

    test("driver exposes this lister") {
        OracleDriver().tableLister()::class.simpleName shouldBe "OracleTableLister"
    }
})
