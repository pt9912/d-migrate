package dev.dmigrate.driver.mssql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

class MssqlSchemaIntrospectionAdapterTest : FunSpec({

    val conn = mockk<Connection>()
    val pool = mockk<ConnectionPool> {
        every { dialect } returns DatabaseDialect.MSSQL
        every { borrow() } returns JdbcDatabaseConnection(conn)
    }
    every { conn.close() } returns Unit

    val jdbc = mockk<JdbcOperations>()
    val adapter = MssqlSchemaIntrospectionAdapter(jdbcFactory = { jdbc })

    test("listTables reads user tables of the schema") {
        every { jdbc.queryList(match { it.contains("FROM sys.tables") }, any(), *anyVararg()) } returns listOf(
            mapOf("table_schema" to "dbo", "table_name" to "orders"),
        )
        val tables = adapter.listTables(pool)
        tables shouldHaveSize 1
        tables[0].name shouldBe "orders"
        tables[0].schema shouldBe "dbo"
    }

    // Der Primaerschluessel gilt fuer jede beteiligte Spalte; `isUnique` nur
    // fuer einspaltige Indizes, und nicht fuer den Primaerschluessel selbst.
    test("primary key spans all its columns, unique stays single-column and excludes the PK") {
        every { jdbc.queryList(match { it.contains("sys.indexes") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "is_primary_key" to true, "is_unique" to true, "index_column_count" to 1),
            mapOf("column_name" to "tenant", "is_primary_key" to true, "is_unique" to true, "index_column_count" to 2),
            mapOf("column_name" to "email", "is_primary_key" to false, "is_unique" to true, "index_column_count" to 1),
            mapOf("column_name" to "a", "is_primary_key" to false, "is_unique" to true, "index_column_count" to 2),
        )
        every { jdbc.queryList(match { it.contains("sys.foreign_key_columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "customer_id"),
        )
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "type_name" to "int", "is_nullable" to false),
            mapOf("column_name" to "tenant", "type_name" to "int", "is_nullable" to false),
            mapOf("column_name" to "email", "type_name" to "nvarchar", "is_nullable" to true),
            mapOf("column_name" to "a", "type_name" to "int", "is_nullable" to true),
            mapOf("column_name" to "customer_id", "type_name" to "int", "is_nullable" to false),
        )

        val columns = adapter.listColumns(pool, "orders").associateBy { it.name }
        columns.getValue("id").isPrimaryKey shouldBe true
        // Auch die zweite Spalte des zusammengesetzten Schluessels.
        columns.getValue("tenant").isPrimaryKey shouldBe true
        columns.getValue("id").nullable shouldBe false
        columns.getValue("email").isUnique shouldBe true
        // Der Primaerschluessel-Index traegt `is_unique = 1`, zaehlt hier aber
        // nicht — PostgreSQL und MySQL trennen die beiden Eigenschaften ebenso.
        columns.getValue("id").isUnique shouldBe false
        columns.getValue("a").isUnique shouldBe false
        columns.getValue("customer_id").isForeignKey shouldBe true
        columns.getValue("email").dbType shouldBe "nvarchar"
    }
})
