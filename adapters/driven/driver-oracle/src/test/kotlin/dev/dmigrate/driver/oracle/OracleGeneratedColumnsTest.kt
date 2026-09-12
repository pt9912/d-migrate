package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.metadata.GeneratedColumnNotes
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty as shouldBeEmptyMap
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class OracleGeneratedColumnsTest : FunSpec({

    fun column(
        name: String,
        default: String? = null,
        isIdentity: Boolean = false,
    ) = OracleMetadataQueries.ColumnRow(
        name = name,
        typeName = "NUMBER",
        length = null,
        precision = 14,
        scale = 2,
        nullable = true,
        isIdentity = isIdentity,
        identityGeneration = null,
        identitySequenceName = null,
        defaultDefinition = default,
        ordinal = 1,
    )

    fun session(virtual: List<String>, ddl: String? = null, ddlFails: Boolean = false): JdbcOperations {
        val jdbc = mockk<JdbcOperations>()
        every {
            jdbc.queryList(match { it.contains("virtual_column") }, any(), any())
        } returns virtual.map { mapOf("column_name" to it) }
        if (ddlFails) {
            every {
                jdbc.querySingle(match { it.contains("DBMS_METADATA.GET_DDL") }, any(), any())
            } throws IllegalStateException("ORA-00904")
        } else {
            every {
                jdbc.querySingle(match { it.contains("DBMS_METADATA.GET_DDL") }, any(), any())
            } returns ddl?.let { mapOf("ddl" to it) }
        }
        return jdbc
    }

    val columns = listOf(column("qty"), column("price"))

    test("a virtual column carries its expression from data_default") {
        val notes = mutableListOf<SchemaReadNote>()
        val jdbc = session(virtual = listOf("total"))

        val computed = OracleGeneratedColumns.read(
            jdbc, "APP", "order_line",
            columns + column("total", default = """"qty"*"price""""),
            notes,
        )

        computed shouldBe mapOf(
            "total" to ColumnGeneration.Computed(""""qty"*"price"""", stored = false),
        )
        notes.shouldBeEmpty()
        // Eine virtuelle Spalte steht im Katalog — die DDL kostet hier nichts.
        verify(exactly = 0) { jdbc.querySingle(match { it.contains("GET_DDL") }, any(), any()) }
    }

    test("a virtual column without an expression is reported as a loss") {
        val notes = mutableListOf<SchemaReadNote>()
        val computed = OracleGeneratedColumns.read(
            session(virtual = listOf("total")), "APP", "order_line",
            columns + column("total"),
            notes,
        )

        computed.shouldBeEmptyMap()
        notes.map { it.code } shouldBe listOf(GeneratedColumnNotes.EXPRESSION_DROPPED)
    }

    test("a materialized column is recognised from the DDL") {
        val notes = mutableListOf<SchemaReadNote>()
        val ddl = """CREATE TABLE "APP"."order_line" ("qty" NUMBER, "price" NUMBER,
            "total" NUMBER(14,2) GENERATED ALWAYS AS ("qty"*"price") MATERIALIZED )"""

        val computed = OracleGeneratedColumns.read(
            session(virtual = emptyList(), ddl = ddl), "APP", "order_line",
            columns + column("total", default = """"qty"*"price""""),
            notes,
        )

        computed shouldBe mapOf(
            "total" to ColumnGeneration.Computed(""""qty"*"price"""", stored = true),
        )
        notes.shouldBeEmpty()
    }

    test("a default that the DDL does not call generated stays a default") {
        val notes = mutableListOf<SchemaReadNote>()
        val ddl = """CREATE TABLE "APP"."order_line" ("qty" NUMBER, "price" NUMBER,
            "total" NUMBER(14,2) DEFAULT "qty" )"""

        val computed = OracleGeneratedColumns.read(
            session(virtual = emptyList(), ddl = ddl), "APP", "order_line",
            columns + column("total", default = """"qty""""),
            notes,
        )

        computed.shouldBeEmptyMap()
        notes.shouldBeEmpty()
    }

    test("a plain literal default costs no DDL round trip") {
        val notes = mutableListOf<SchemaReadNote>()
        val jdbc = session(virtual = emptyList())

        val computed = OracleGeneratedColumns.read(
            jdbc, "APP", "order_line",
            columns +
                column("created", default = "SYSDATE") +
                column("flag", default = "7") +
                column("rate", default = "-1.5") +
                column("label", default = "'n/a'") +
                column("note", default = "NULL"),
            notes,
        )

        computed.shouldBeEmptyMap()
        notes.shouldBeEmpty()
        verify(exactly = 0) { jdbc.querySingle(match { it.contains("GET_DDL") }, any(), any()) }
    }

    test("an expression without any column reference is still a candidate") {
        // Gemessen: Oracle nimmt `GENERATED ALWAYS AS (1+1) MATERIALIZED` an.
        // Ein Filter auf „nennt eine Nachbarspalte" liefe daran vorbei.
        val notes = mutableListOf<SchemaReadNote>()
        val ddl = """CREATE TABLE "APP"."t" ("a" NUMBER,
            "b" NUMBER GENERATED ALWAYS AS (1+1) MATERIALIZED )"""

        val computed = OracleGeneratedColumns.read(
            session(virtual = emptyList(), ddl = ddl), "APP", "t",
            listOf(column("a"), column("b", default = "1+1")),
            notes,
        )

        computed shouldBe mapOf("b" to ColumnGeneration.Computed("1+1", stored = true))
    }

    test("an unreadable DDL is reported, not guessed") {
        val notes = mutableListOf<SchemaReadNote>()

        val computed = OracleGeneratedColumns.read(
            session(virtual = emptyList(), ddlFails = true), "APP", "order_line",
            columns + column("total", default = """"qty"*"price""""),
            notes,
        )

        computed.shouldBeEmptyMap()
        notes.map { it.code } shouldBe listOf(GeneratedColumnNotes.GENERATION_UNDECIDABLE)
        notes.single().message shouldBe
            "Could not read the table DDL (DBMS_METADATA), so a MATERIALIZED computed column is " +
            "indistinguishable from a plain DEFAULT here: total. " +
            "They were read as plain columns with a default."
    }

    test("an identity column is never a materialized candidate") {
        val notes = mutableListOf<SchemaReadNote>()
        val jdbc = session(virtual = emptyList())

        OracleGeneratedColumns.read(
            jdbc, "APP", "order_line",
            columns + column("id", default = """"qty"*2""", isIdentity = true),
            notes,
        )

        notes.shouldBeEmpty()
        verify(exactly = 0) { jdbc.querySingle(match { it.contains("GET_DDL") }, any(), any()) }
    }
})
