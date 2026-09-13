package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `column-generation-diff-unmapped.md`: ob eine Spalte gefahrlos gedroppt und
 * neu angelegt werden kann (Kind-Wechsel per Spaltentausch auf
 * MySQL/Oracle/SQL Server). Dialektunabhaengig — das neutrale Modell traegt
 * alles, was gefragt wird.
 */
class ColumnSwapGuardTest : FunSpec({

    fun plainColumn() = ColumnDefinition(type = NeutralType.Text())

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = mapOf(*tables))

    test("a plain, unencumbered column is safe") {
        val schema = schema(
            "orders" to TableDefinition(
                columns = mapOf("id" to plainColumn(), "note" to plainColumn()),
                primaryKey = listOf("id"),
            ),
        )
        ColumnSwapGuard.check(schema, "orders", "note") shouldBe ColumnSwapGuard.Result.Safe
    }

    test("a missing table is encumbered") {
        val schema = schema("orders" to TableDefinition(columns = mapOf("id" to plainColumn())))
        val result = ColumnSwapGuard.check(schema, "missing", "note")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "not in the schema"
    }

    test("a missing column is encumbered") {
        val schema = schema("orders" to TableDefinition(columns = mapOf("id" to plainColumn())))
        val result = ColumnSwapGuard.check(schema, "orders", "missing")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "not in the schema"
    }

    test("a primary key column is encumbered") {
        val schema = schema(
            "orders" to TableDefinition(columns = mapOf("id" to plainColumn()), primaryKey = listOf("id")),
        )
        val result = ColumnSwapGuard.check(schema, "orders", "id")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "primary key"
    }

    test("a column with an inline UNIQUE flag is encumbered") {
        val schema = schema(
            "orders" to TableDefinition(columns = mapOf("email" to plainColumn().copy(unique = true))),
        )
        val result = ColumnSwapGuard.check(schema, "orders", "email")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "UNIQUE"
    }

    test("a column with a named unique constraint reference is encumbered") {
        val schema = schema(
            "orders" to TableDefinition(
                columns = mapOf("email" to plainColumn().copy(uniqueConstraintName = "uq_orders_email")),
            ),
        )
        val result = ColumnSwapGuard.check(schema, "orders", "email")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "UNIQUE"
    }

    test("a column with an outgoing inline foreign key reference is encumbered") {
        val schema = schema(
            "orders" to TableDefinition(
                columns = mapOf(
                    "customer_id" to plainColumn().copy(references = ReferenceDefinition(table = "customers", column = "id")),
                ),
            ),
            "customers" to TableDefinition(columns = mapOf("id" to plainColumn())),
        )
        val result = ColumnSwapGuard.check(schema, "orders", "customer_id")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "outgoing foreign key"
    }

    test("a column covered by an index is encumbered, naming the index") {
        val schema = schema(
            "orders" to TableDefinition(
                columns = mapOf("note" to plainColumn()),
                indices = listOf(IndexDefinition(name = "idx_orders_note", columns = listOf(IndexColumn(name = "note")))),
            ),
        )
        val result = ColumnSwapGuard.check(schema, "orders", "note")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "idx_orders_note"
    }

    test("a column covered by an unnamed index is encumbered") {
        val schema = schema(
            "orders" to TableDefinition(
                columns = mapOf("note" to plainColumn()),
                indices = listOf(IndexDefinition(columns = listOf(IndexColumn(name = "note")))),
            ),
        )
        val result = ColumnSwapGuard.check(schema, "orders", "note")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "unnamed index"
    }

    test("a column covered by a named CHECK constraint is encumbered") {
        val schema = schema(
            "orders" to TableDefinition(
                columns = mapOf("qty" to plainColumn()),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "chk_orders_qty", type = ConstraintType.CHECK, columns = listOf("qty"), expression = "qty >= 0",
                    ),
                ),
            ),
        )
        val result = ColumnSwapGuard.check(schema, "orders", "qty")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "chk_orders_qty"
    }

    test("a column referenced by another table's inline foreign key is encumbered") {
        val schema = schema(
            "customers" to TableDefinition(columns = mapOf("id" to plainColumn())),
            "orders" to TableDefinition(
                columns = mapOf(
                    "customer_id" to plainColumn().copy(references = ReferenceDefinition(table = "customers", column = "id")),
                ),
            ),
        )
        val result = ColumnSwapGuard.check(schema, "customers", "id")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "orders"
    }

    test("a column referenced by another table's FK constraint is encumbered") {
        val schema = schema(
            "customers" to TableDefinition(columns = mapOf("id" to plainColumn())),
            "orders" to TableDefinition(
                columns = mapOf("customer_id" to plainColumn()),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "fk_orders_customer",
                        type = ConstraintType.FOREIGN_KEY,
                        columns = listOf("customer_id"),
                        references = ConstraintReferenceDefinition(table = "customers", columns = listOf("id")),
                    ),
                ),
            ),
        )
        val result = ColumnSwapGuard.check(schema, "customers", "id")
        (result as ColumnSwapGuard.Result.Encumbered).reason shouldContain "orders"
    }

    test("a foreign key on an unrelated column does not encumber this one") {
        val schema = schema(
            "customers" to TableDefinition(columns = mapOf("id" to plainColumn(), "note" to plainColumn())),
            "orders" to TableDefinition(
                columns = mapOf(
                    "customer_id" to plainColumn().copy(references = ReferenceDefinition(table = "customers", column = "id")),
                ),
            ),
        )
        ColumnSwapGuard.check(schema, "customers", "note") shouldBe ColumnSwapGuard.Result.Safe
    }
})
