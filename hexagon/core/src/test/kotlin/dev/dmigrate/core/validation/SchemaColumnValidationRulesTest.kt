package dev.dmigrate.core.validation

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class SchemaColumnValidationRulesTest : FunSpec({

    fun schema(
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = "Test",
        version = "1.0",
        tables = tables,
        customTypes = customTypes,
        sequences = sequences,
    )

    fun table(columns: Map<String, ColumnDefinition>) =
        TableDefinition(columns = columns)

    test("validate returns E017 for incompatible foreign key type") {
        val schema = schema(
            tables = mapOf(
                "users" to table(
                    mapOf("id" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)))
                )
            )
        )
        val column = ColumnDefinition(
            type = NeutralType.Integer,
            references = ReferenceDefinition(table = "users", column = "id"),
        )

        val errors = SchemaColumnValidationRules.validate(
            path = "tables.orders.columns.user_id",
            column = column,
            schema = schema,
        )

        errors.single().code shouldBe "E017"
    }

    test("validate returns E122 for legacy nextval function call") {
        val errors = SchemaColumnValidationRules.validate(
            path = "tables.orders.columns.order_id",
            column = ColumnDefinition(
                type = NeutralType.Integer,
                default = DefaultValue.FunctionCall("nextval('order_seq')"),
            ),
            schema = schema(),
        )

        errors.single().code shouldBe "E122"
    }

    test("validate returns E123 for missing sequence_nextval target") {
        val errors = SchemaColumnValidationRules.validate(
            path = "tables.orders.columns.order_id",
            column = ColumnDefinition(
                type = NeutralType.Integer,
                default = DefaultValue.SequenceNextVal("order_seq"),
            ),
            schema = schema(sequences = emptyMap()),
        )

        errors.single().code shouldBe "E123"
    }

    test("validate returns no errors for matching identifier foreign key and existing sequence") {
        val schema = schema(
            tables = mapOf(
                "users" to table(
                    mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)))
                )
            ),
            sequences = mapOf("order_seq" to SequenceDefinition(start = 1)),
        )
        val errors = SchemaColumnValidationRules.validate(
            path = "tables.orders.columns.user_id",
            column = ColumnDefinition(
                type = NeutralType.Integer,
                references = ReferenceDefinition(table = "users", column = "id"),
                default = DefaultValue.SequenceNextVal("order_seq"),
            ),
            schema = schema,
        )

        errors.shouldBeEmpty()
    }
})
