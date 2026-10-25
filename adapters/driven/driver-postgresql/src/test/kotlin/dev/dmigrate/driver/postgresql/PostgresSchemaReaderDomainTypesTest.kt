package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/** `CHECK`-Constraints und Basistyp einer Domain im PostgreSQL-Reader (Issues #57 und #58). */
class PostgresSchemaReaderDomainTypesTest : FunSpec({

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

    fun domainRow(name: String, conname: String?, clause: String?) = mapOf(
        "typname" to name, "base_type" to "int4", "numeric_precision" to 32, "numeric_scale" to 0,
        "domain_default" to null, "conname" to conname, "check_clause" to clause,
    )

    fun readDomains(vararg rows: Map<String, Any?>): SchemaReadResult {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("typtype = 'd'") }, any()) } returns rows.toList()
        every { jdbc.queryList(match { it.contains("typtype = 'c'") }, any()) } returns emptyList()
        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        return reader.read(pool, opts)
    }

    test("domain with 0, 1 and n checks: rows are grouped by type, n checks are ANDed in row order (#57)") {
        val result = readDomains(
            domainRow("none", null, null),
            domainRow("one", "one_check", "CHECK ((VALUE > 0))"),
            domainRow("two", "c1", "CHECK ((VALUE > 0))"),
            domainRow("two", "c2", "CHECK ((VALUE < 10))"),
            domainRow("three", "a", "CHECK ((VALUE >= (1)::numeric))"),
            domainRow("three", "b", "CHECK ((VALUE <> 5))"),
            domainRow("three", "c", "CHECK ((VALUE < 100))"),
        )

        val types = result.schema.customTypes
        types.keys.toList() shouldBe listOf("none", "one", "two", "three")
        types.getValue("none").check.shouldBeNull()
        types.getValue("one").check shouldBe "VALUE > 0"
        types.getValue("two").check shouldBe "(VALUE > 0) AND (VALUE < 10)"
        types.getValue("three").check shouldBe "(VALUE >= 1) AND (VALUE <> 5) AND (VALUE < 100)"
        types.getValue("two").baseType shouldBe "integer"
        result.notes.filter { it.code == "R407" }.shouldBeEmpty()
    }

    test("domain check NOT VALID keeps the predicate, drops the suffix and reports R407 (#57)") {
        val result = readDomains(
            domainRow("d", "c1", "CHECK ((VALUE > 0))"),
            domainRow("d", "c2", "CHECK ((VALUE < 10)) NOT VALID"),
            domainRow("e", "only", "CHECK ((VALUE > 0)) NOT VALID"),
        )

        result.schema.customTypes.getValue("d").check shouldBe "(VALUE > 0) AND (VALUE < 10)"
        result.schema.customTypes.getValue("e").check shouldBe "VALUE > 0"
        val notes = result.notes.filter { it.code == "R407" }
        notes.map { it.objectName } shouldBe listOf("d", "e")
        notes[0].severity shouldBe SchemaReadSeverity.WARNING
        notes[0].message.contains("c2") shouldBe true
        notes[0].message.contains("c1") shouldBe false
    }

    test("normalizeDomainCheck strips NOT VALID, but not inside a literal (#57)") {
        normalizeDomainCheck("CHECK ((VALUE > 0)) NOT VALID") shouldBe "VALUE > 0"
        normalizeDomainCheck("CHECK ((VALUE <> 'x NOT VALID'::text))") shouldBe "VALUE <> 'x NOT VALID'"
    }

    test("domain base type keeps length and array from format_type (#58)") {
        stubEmptyDefaults()
        fun row(name: String, sql: String, typname: String = "x") = mapOf(
            "typname" to name, "base_type" to typname, "base_schema" to "pg_catalog",
            "base_type_sql" to sql, "check_clause" to null,
        )
        every { jdbc.queryList(match { it.contains("typtype = 'd'") }, any()) } returns listOf(
            row("v", "character varying(50)"), row("c", "character(5)"), row("ia", "integer[]"),
            row("va", "character varying(50)[]"), row("na", "numeric(10,2)[]"),
            row("ts", "timestamp(3) without time zone"),
            // Basistyp ausserhalb von pg_catalog: der Typname bleibt unqualifiziert.
            mapOf("typname" to "dd", "base_type" to "v", "base_schema" to "public",
                "base_type_sql" to "public.v", "check_clause" to null),
        )
        every { jdbc.queryList(match { it.contains("typtype = 'c'") }, any()) } returns emptyList()
        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val types = reader.read(pool, opts).schema.customTypes

        types.mapValues { it.value.baseType } shouldBe mapOf(
            "v" to "varchar(50)", "c" to "char(5)", "ia" to "integer[]", "va" to "varchar(50)[]",
            "na" to "decimal(10,2)[]", "ts" to "timestamp(3)", "dd" to "v",
        )
        types["na"]!!.precision.shouldBeNull()
    }
})
