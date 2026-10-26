package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/**
 * Eine Spalte vom Typ einer Domain liest der PostgreSQL-Reader als `Enum(refType = <Domain>)` (Issue #68):
 * `data_type`/`udt_name` tragen dort nur den Basistyp, `domain_name` die Domain.
 */
class PostgresSchemaReaderDomainColumnTest : FunSpec({

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> { every { borrow() } returns JdbcDatabaseConnection(conn) }
    val jdbc = mockk<JdbcOperations>(relaxed = true)
    val reader = PostgresSchemaReader(jdbcFactory = { jdbc })
    val stmt = mockk<Statement>(relaxUnitFun = true)
    val rs = mockk<ResultSet>(relaxUnitFun = true)
    every { conn.createStatement() } returns stmt
    every { stmt.executeQuery("SELECT current_schema()") } returns rs
    every { rs.next() } returns true
    every { rs.getString(1) } returns "public"
    every { conn.catalog } returns "testdb"

    // `format` ist `format_type(typbasetype, typtypmod)`, wie der Katalog den Basistyp nennt.
    fun domainRow(name: String, format: String = "integer", base: String = "int4", schema: String = "pg_catalog") = mapOf(
        "typname" to name, "base_type" to base, "base_type_sql" to format, "base_schema" to schema,
        "numeric_precision" to 32, "numeric_scale" to 0,
        "domain_default" to null, "conname" to null, "check_clause" to null,
    )

    fun column(
        name: String, dataType: String = "integer", udt: String = "int4",
        domain: String? = null, domainSchema: String? = "public", extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val row = mutableMapOf<String, Any?>(
            "column_name" to name, "data_type" to dataType, "udt_name" to udt, "is_nullable" to "YES",
            "column_default" to null, "ordinal_position" to 1,
        )
        if (domain != null) {
            row["domain_name"] = domain
            row["domain_schema"] = domainSchema
        }
        row.putAll(extra)
        return row
    }

    fun read(domains: List<Map<String, Any?>>, vararg columns: Map<String, Any?>): SchemaReadResult {
        every { jdbc.queryList(match { it.contains("typtype = 'd'") }, any()) } returns domains
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "t", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns
            columns.toList()
        every { jdbc.queryList(match { it.contains("to_regclass('geometry_columns')") }) } returns
            listOf(mapOf("geom" to null, "geog" to null))
        every { jdbc.queryList(match { it.contains("contype = 'p'") }, any(), any()) } returns
            listOf(mapOf("column_name" to "id"))
        val opts = SchemaReadOptions(
            includeViews = false, includeFunctions = false, includeProcedures = false, includeTriggers = false,
        )
        return reader.read(pool, opts)
    }

    fun types(result: SchemaReadResult) = result.schema.tables.getValue("t").columns.mapValues { it.value.type }

    test("eine Domain-Spalte der gelesenen Domain wird Enum(refType)") {
        val result = read(listOf(domainRow("dd")), column("id"), column("d", domain = "dd"))

        types(result).getValue("d") shouldBe NeutralType.Enum(refType = "dd")
        types(result).getValue("id") shouldBe NeutralType.Integer
    }

    test("eine Zeile ohne domain_name ist keine Domain-Spalte (Fixture-Zeilen bleiben tolerant)") {
        val result = read(listOf(domainRow("dd")), column("id"), column("d"))

        types(result).getValue("d") shouldBe NeutralType.Integer
    }

    test("eine Domain, die nicht in custom_types steht, faellt auf den Basistyp zurueck") {
        val result = read(emptyList(), column("id"), column("d", domain = "dd"))

        types(result).getValue("d") shouldBe NeutralType.Integer
    }

    test("eine Domain aus einem anderen Schema faellt auf den Basistyp zurueck, auch bei gleichem Namen") {
        val result = read(
            listOf(domainRow("dd")), column("id"), column("d", domain = "dd", domainSchema = "other"),
        )

        types(result).getValue("d") shouldBe NeutralType.Integer
    }

    test("Domain ueber einer Domain: die aeussere ist nicht aufloesbar, die Spalte liest wie ohne Domain-Erkennung") {
        // `udt_name` traegt den inneren Namen; ohne Domain-Erkennung ist das `Enum(refType = d1)`.
        val result = read(
            listOf(domainRow("d1"), domainRow("d2", format = "d1", base = "d1", schema = "public")),
            column("id"), column("a", domain = "d1"), column("b", dataType = "USER-DEFINED", udt = "d1", domain = "d2"),
        )

        types(result).getValue("a") shouldBe NeutralType.Enum(refType = "d1")
        types(result).getValue("b") shouldBe NeutralType.Enum(refType = "d1")
    }

    test("eine Array-Domain nennt die Domain, nicht das Array") {
        val result = read(
            listOf(domainRow("ia", format = "integer[]")),
            column("id"), column("a", dataType = "ARRAY", udt = "_int4", domain = "ia"),
        )

        types(result).getValue("a") shouldBe NeutralType.Enum(refType = "ia")
    }

    test("jede Basistyp-Domain mit neutralem Gegenstueck nennt die Domain (text, varchar(n), decimal, uuid, boolean, bigint)") {
        val cases = listOf(
            Triple("text" to "text", "text", mapOf<String, Any?>()),
            Triple("character varying" to "varchar", "character varying(50)", mapOf<String, Any?>("character_maximum_length" to 50)),
            Triple("numeric" to "numeric", "numeric(10,2)", mapOf<String, Any?>("numeric_precision" to 10, "numeric_scale" to 2)),
            Triple("uuid" to "uuid", "uuid", mapOf<String, Any?>()),
            Triple("boolean" to "bool", "boolean", mapOf<String, Any?>()),
            Triple("bigint" to "int8", "bigint", mapOf<String, Any?>()),
        )
        for ((names, format, extra) in cases) {
            val result = read(
                listOf(domainRow("dd", format = format)), column("id"),
                column("d", dataType = names.first, udt = names.second, domain = "dd", extra = extra),
            )
            withClue(format) { types(result).getValue("d") shouldBe NeutralType.Enum(refType = "dd") }
        }
    }

    test("Domain ohne neutrales Gegenstueck oder mit anderem Typ als der Basistyp bleibt beim Basistyp (real, bit, numeric, varchar[])") {
        // Erwartet: die Spalte liest genau wie dieselbe Zeile ohne `domain_name` (ohne Domain-Erkennung).
        val cases = listOf(
            "real" to column("d", dataType = "real", udt = "float4", domain = "dd"),
            "bit(5)" to column("d", dataType = "bit", udt = "bit", domain = "dd"),
            "numeric" to column("d", dataType = "numeric", udt = "numeric", domain = "dd"),
            "character varying(50)[]" to column("d", dataType = "ARRAY", udt = "_varchar", domain = "dd"),
            "inet" to column("d", dataType = "inet", udt = "inet", domain = "dd"),
            "interval" to column("d", dataType = "interval", udt = "interval", domain = "dd"),
        )
        for ((format, col) in cases) {
            val withDomain = types(read(listOf(domainRow("dd", format = format)), column("id"), col)).getValue("d")
            val plain = col - "domain_name" - "domain_schema"
            val without = types(read(emptyList(), column("id"), plain)).getValue("d")
            withClue(format) {
                (withDomain is NeutralType.Enum && withDomain.refType == "dd") shouldBe false
                withDomain shouldBe without
            }
        }
        types(read(listOf(domainRow("dd", format = "real")), column("id"), cases[0].second)).getValue("d") shouldBe
            NeutralType.Float(FloatPrecision.SINGLE)
    }

    test("eine Identity-Spalte bleibt beim Basistyp: das Modell traegt die Erzeugungsart nur dort") {
        val result = read(
            listOf(domainRow("dd")), column("id"),
            column(
                "n", domain = "dd",
                extra = mapOf("is_identity" to "YES", "identity_generation" to "BY DEFAULT"),
            ),
        )

        types(result).getValue("n") shouldBe NeutralType.Integer
    }

    test("NOT NULL der Domain bleibt an der Domain: die Spalte traegt required nur mit eigenem NOT NULL") {
        val result = read(
            listOf(domainRow("dd")), column("id"),
            column("b", domain = "dd", extra = mapOf("is_nullable" to "NO", "column_not_null" to false)),
            column("c", domain = "dd", extra = mapOf("is_nullable" to "NO", "column_not_null" to true)),
        )

        val columns = result.schema.tables.getValue("t").columns
        columns.getValue("b").required shouldBe false
        columns.getValue("c").required shouldBe true
    }
})
