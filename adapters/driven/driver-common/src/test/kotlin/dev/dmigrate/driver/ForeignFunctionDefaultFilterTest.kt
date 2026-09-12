package dev.dmigrate.driver

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ForeignFunctionDefaultFilterTest : FunSpec({

    fun schemaWith(default: DefaultValue?) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                    "status_history" to ColumnDefinition(NeutralType.Text(), required = true, default = default),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun defaultOf(result: ForeignFunctionDefaultFilter.Result) =
        result.schema.tables.getValue("orders").columns.getValue("status_history").default

    test("a PostgreSQL server text is dropped and named") {
        val pg = DefaultValue.FunctionCall("ARRAY['NEW'::order_status]")

        val result = ForeignFunctionDefaultFilter.apply(schemaWith(pg), DatabaseDialect.MSSQL)

        defaultOf(result).shouldBeNull()
        val note = result.notes.single()
        note.code shouldBe "E053"
        note.objectName shouldBe "orders.status_history"
        withClue(note.message) {
            note.message shouldContain "function default"
            note.message shouldContain "::"
        }
    }

    test("the same text stays on PostgreSQL, where it is valid") {
        val pg = DefaultValue.FunctionCall("ARRAY['NEW'::order_status]")

        val result = ForeignFunctionDefaultFilter.apply(schemaWith(pg), DatabaseDialect.POSTGRESQL)

        defaultOf(result) shouldBe pg
        result.notes.shouldBeEmpty()
    }

    test("a translated function name is untouched on every dialect") {
        DatabaseDialect.entries.forEach { dialect ->
            val result = ForeignFunctionDefaultFilter.apply(
                schemaWith(DefaultValue.FunctionCall("current_timestamp")), dialect,
            )
            withClue(dialect.name) {
                defaultOf(result) shouldBe DefaultValue.FunctionCall("current_timestamp")
                result.notes.shouldBeEmpty()
            }
        }
    }

    test("literal defaults are none of this filter's business") {
        val result = ForeignFunctionDefaultFilter.apply(
            schemaWith(DefaultValue.StringLiteral("NEW")), DatabaseDialect.MSSQL,
        )

        defaultOf(result) shouldBe DefaultValue.StringLiteral("NEW")
        result.notes.shouldBeEmpty()
    }

    test("a schema without any foreign default is returned unchanged, not rebuilt") {
        val schema = schemaWith(null)

        val result = ForeignFunctionDefaultFilter.apply(schema, DatabaseDialect.ORACLE)

        // Gleiche Instanz: der Filter baut nichts um, wo es nichts zu filtern gibt.
        (result.schema === schema) shouldBe true
        result.notes.shouldBeEmpty()
    }

    test("a MySQL backtick default is refused on the other dialects") {
        val mysql = DefaultValue.FunctionCall("`some_udf`()")

        ForeignFunctionDefaultFilter.apply(schemaWith(mysql), DatabaseDialect.MYSQL).notes.shouldBeEmpty()
        ForeignFunctionDefaultFilter.apply(schemaWith(mysql), DatabaseDialect.SQLITE).notes.single().code shouldBe "E053"
    }
})
