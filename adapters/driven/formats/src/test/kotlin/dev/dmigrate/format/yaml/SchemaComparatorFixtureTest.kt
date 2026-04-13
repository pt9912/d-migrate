package dev.dmigrate.format.yaml

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class SchemaComparatorFixtureTest : FunSpec({

    val codec = YamlSchemaCodec()
    val comparator = SchemaComparator()

    fun loadFixture(path: String) =
        codec.read(SchemaComparatorFixtureTest::class.java.getResourceAsStream("/fixtures/$path")!!)

    // --- Self-compare: identical fixture produces empty diff ---

    test("minimal.yaml compared to itself produces empty diff") {
        val schema = loadFixture("schemas/minimal.yaml")
        val diff = comparator.compare(schema, schema)
        diff.isEmpty() shouldBe true
    }

    test("e-commerce.yaml compared to itself produces empty diff") {
        val schema = loadFixture("schemas/e-commerce.yaml")
        val diff = comparator.compare(schema, schema)
        diff.isEmpty() shouldBe true
    }

    test("full-featured.yaml compared to itself produces empty diff") {
        val schema = loadFixture("schemas/full-featured.yaml")
        val diff = comparator.compare(schema, schema)
        diff.isEmpty() shouldBe true
    }

    // --- Cross-fixture compare: minimal vs e-commerce ---

    test("minimal vs e-commerce detects structural differences") {
        val minimal = loadFixture("schemas/minimal.yaml")
        val ecommerce = loadFixture("schemas/e-commerce.yaml")

        val diff = comparator.compare(minimal, ecommerce)

        // Schema metadata
        diff.schemaMetadata!!.name!!.before shouldBe "Minimal Schema"
        diff.schemaMetadata!!.name!!.after shouldBe "E-Commerce System"

        // Tables: "users" removed, "customers" and "orders" added
        diff.tablesRemoved shouldHaveSize 1
        diff.tablesRemoved[0].name shouldBe "users"
        diff.tablesAdded shouldHaveSize 2
        diff.tablesAdded.map { it.name } shouldBe listOf("customers", "orders")

        // Enum types: order_status added
        diff.enumTypesAdded shouldHaveSize 1
        diff.enumTypesAdded[0].name shouldBe "order_status"
        diff.enumTypesAdded[0].definition.values shouldBe
            listOf("pending", "processing", "shipped", "delivered", "cancelled")
    }

    // --- e-commerce with modifications ---

    test("e-commerce with added column detects change") {
        val original = loadFixture("schemas/e-commerce.yaml")
        val modified = original.copy(
            tables = original.tables.mapValues { (name, table) ->
                if (name == "customers") {
                    table.copy(columns = table.columns + ("phone" to ColumnDefinition(
                        type = NeutralType.Text(20),
                    )))
                } else table
            }
        )

        val diff = comparator.compare(original, modified)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].name shouldBe "customers"
        diff.tablesChanged[0].columnsAdded.keys shouldBe setOf("phone")
    }

    test("e-commerce with changed enum values detects enum diff") {
        val original = loadFixture("schemas/e-commerce.yaml")
        val modified = original.copy(
            customTypes = mapOf("order_status" to CustomTypeDefinition(
                kind = CustomTypeKind.ENUM,
                values = listOf("pending", "processing", "shipped", "delivered", "cancelled", "refunded"),
            ))
        )

        val diff = comparator.compare(original, modified)
        diff.enumTypesChanged shouldHaveSize 1
        diff.enumTypesChanged[0].name shouldBe "order_status"
        diff.enumTypesChanged[0].values.after shouldBe
            listOf("pending", "processing", "shipped", "delivered", "cancelled", "refunded")
    }

    test("e-commerce CHECK constraint does not produce diff when removed") {
        val original = loadFixture("schemas/e-commerce.yaml")
        // Remove the CHECK constraint from orders
        val modified = original.copy(
            tables = original.tables.mapValues { (name, table) ->
                if (name == "orders") {
                    table.copy(constraints = emptyList())
                } else table
            }
        )

        val diff = comparator.compare(original, modified)
        // CHECK is not in MVP scope, so removing it should not produce a diff
        diff.tablesChanged.shouldBeEmpty()
    }

    test("full-featured vs modified detects view changes") {
        val original = loadFixture("schemas/full-featured.yaml")
        val modified = original.copy(
            views = original.views.mapValues { (name, view) ->
                if (name == "monthly_stats") view.copy(materialized = false) else view
            }
        )

        val diff = comparator.compare(original, modified)
        diff.viewsChanged shouldHaveSize 1
        diff.viewsChanged[0].name shouldBe "monthly_stats"
        diff.viewsChanged[0].materialized!!.before shouldBe true
        diff.viewsChanged[0].materialized!!.after shouldBe false
    }

    test("e-commerce column-level FK equivalent to constraint-level FK produces no diff") {
        val original = loadFixture("schemas/e-commerce.yaml")
        // Move the column-level references to a constraint-level FK
        val modified = original.copy(
            tables = original.tables.mapValues { (name, table) ->
                if (name == "orders") {
                    val customerIdCol = table.columns["customer_id"]!!
                    val ref = customerIdCol.references!!
                    table.copy(
                        columns = table.columns.mapValues { (colName, col) ->
                            if (colName == "customer_id") col.copy(references = null) else col
                        },
                        constraints = table.constraints + ConstraintDefinition(
                            name = "fk_customer",
                            type = ConstraintType.FOREIGN_KEY,
                            columns = listOf("customer_id"),
                            references = ConstraintReferenceDefinition(
                                table = ref.table,
                                columns = listOf(ref.column),
                                onDelete = ref.onDelete,
                                onUpdate = ref.onUpdate,
                            ),
                        ),
                    )
                } else table
            }
        )

        val diff = comparator.compare(original, modified)
        diff.isEmpty() shouldBe true
    }
})
