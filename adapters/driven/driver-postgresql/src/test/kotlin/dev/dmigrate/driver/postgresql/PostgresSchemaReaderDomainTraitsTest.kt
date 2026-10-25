package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/** `DEFAULT`, `NOT NULL` und Kollation einer Domain im PostgreSQL-Reader, und `required` der Domain-Spalte (Issue #59). */
class PostgresSchemaReaderDomainTraitsTest : FunSpec({

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns JdbcDatabaseConnection(conn)
    }
    // Relaxed: jede nicht gestubbte Abfrage liefert eine leere Liste.
    val jdbc = mockk<JdbcOperations>(relaxed = true)
    val reader = PostgresSchemaReader(jdbcFactory = { jdbc })
    val stmt = mockk<Statement>(relaxUnitFun = true)
    val rs = mockk<ResultSet>(relaxUnitFun = true)
    every { conn.createStatement() } returns stmt
    every { stmt.executeQuery("SELECT current_schema()") } returns rs
    every { rs.next() } returns true
    every { rs.getString(1) } returns "public"
    every { conn.catalog } returns "testdb"

    fun stubEmptyDefaults() {
        every { jdbc.queryList(match { it.contains("typtype = 'c'") }, any()) } returns emptyList()
    }

    fun stubTableQueries(columns: List<Map<String, Any?>>, pkColumns: List<String>) {
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns columns
        every { jdbc.queryList(match { it.contains("contype = 'p'") }, any(), any()) } returns
            pkColumns.map { mapOf("column_name" to it) }
    }

    test("domain default, NOT NULL and collation are read from the catalog (#59)") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("typtype = 'd'") }, any()) } returns listOf(
            mapOf("typname" to "d_all", "base_type" to "text", "numeric_precision" to null,
                "numeric_scale" to null, "domain_default" to "'x'::text", "not_null" to true,
                "collation_name" to "C", "check_clause" to "CHECK ((length(VALUE) > 0))"),
            mapOf("typname" to "d_num", "base_type" to "int4", "numeric_precision" to 32,
                "numeric_scale" to 0, "domain_default" to "5", "not_null" to false,
                "collation_name" to null, "check_clause" to null),
            mapOf("typname" to "d_seq", "base_type" to "int4", "numeric_precision" to 32,
                "numeric_scale" to 0, "domain_default" to "nextval('s_seq'::regclass)", "not_null" to false,
                "collation_name" to null, "check_clause" to null),
            mapOf("typname" to "d_q", "base_type" to "text", "numeric_precision" to null,
                "numeric_scale" to null, "domain_default" to null, "not_null" to false,
                "collation_name" to "\"app\".\"ci\"", "check_clause" to null),
            mapOf("typname" to "d_plain", "base_type" to "int4", "numeric_precision" to 32,
                "numeric_scale" to 0, "domain_default" to null, "not_null" to false,
                "collation_name" to null, "check_clause" to null),
        )
        every { jdbc.queryList(match { it.contains("typtype = 'c'") }, any()) } returns emptyList()

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val types = reader.read(pool, opts).schema.customTypes

        types["d_all"]!!.default shouldBe DefaultValue.StringLiteral("x")
        types["d_all"]!!.notNull shouldBe true
        types["d_all"]!!.collation shouldBe "C"
        types["d_num"]!!.default shouldBe DefaultValue.NumberLiteral(5L)
        types["d_num"]!!.notNull shouldBe false
        types["d_seq"]!!.default shouldBe DefaultValue.SequenceNextVal("s_seq")
        types["d_q"]!!.collation shouldBe "\"app\".\"ci\""
        types["d_plain"]!!.default.shouldBeNull()
        types["d_plain"]!!.notNull shouldBe false
        types["d_plain"]!!.collation.shouldBeNull()
    }

    test("a column of a NOT NULL domain is not required: the domain carries NOT NULL (#59)") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "t", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        fun column(name: String, position: Int, columnNotNull: Boolean?) = mapOf(
            "column_name" to name, "data_type" to "integer", "udt_name" to "int4",
            // information_schema rechnet das NOT NULL der Domain ein: is_nullable ist NO.
            "is_nullable" to "NO", "column_not_null" to columnNotNull, "column_default" to null,
            "ordinal_position" to position, "character_maximum_length" to null,
            "numeric_precision" to 32, "numeric_scale" to 0, "is_identity" to "NO", "identity_generation" to null,
        )
        stubTableQueries(
            listOf(column("via_domain", 1, false), column("own", 2, true), column("legacy", 3, null)),
            emptyList(),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val columns = reader.read(pool, opts).schema.tables.getValue("t").columns

        columns.getValue("via_domain").required shouldBe false
        columns.getValue("own").required shouldBe true
        // Ohne die Spalte im Ergebnis gilt is_nullable wie zuvor.
        columns.getValue("legacy").required shouldBe true
    }
})
