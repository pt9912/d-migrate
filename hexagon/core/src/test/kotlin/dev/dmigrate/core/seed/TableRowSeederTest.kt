package dev.dmigrate.core.seed

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class TableRowSeederTest : FunSpec({

    fun seederFor(seed: Long) = TableRowSeeder(Random(seed), SeedLocale.EN)

    fun schemaOf(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "test", version = "1.0", tables = tables.toMap())

    test("generates count rows per table") {
        val schema = schemaOf(
            "users" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                    "name" to ColumnDefinition(type = NeutralType.Text(maxLength = 40), required = true),
                ),
            ),
        )
        val result = seederFor(1).seedAll(schema, countPerTable = 5)
        result.getValue("users") shouldHaveSize 5
    }

    test("determinism: same seed produces identical rows across two independent runs") {
        val schema = schemaOf(
            "users" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                    "name" to ColumnDefinition(type = NeutralType.Text(maxLength = 40), required = true),
                ),
            ),
        )
        val first = seederFor(42).seedAll(schema, countPerTable = 10)
        val second = seederFor(42).seedAll(schema, countPerTable = 10)
        first shouldBe second
    }

    test("FK-consistent: every generated child value references an existing parent value") {
        val schema = schemaOf(
            "customers" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                ),
            ),
            "orders" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                    "customer_id" to ColumnDefinition(
                        type = NeutralType.Integer,
                        required = true,
                        references = ReferenceDefinition(table = "customers", column = "id"),
                    ),
                ),
            ),
        )
        val result = seederFor(7).seedAll(schema, countPerTable = 20)
        val customerIds = result.getValue("customers").map { it.getValue("id") }.toSet()
        val orderCustomerIds = result.getValue("orders").map { it.getValue("customer_id") }
        orderCustomerIds.all { it in customerIds } shouldBe true
    }

    test("unique columns contain no duplicates") {
        val schema = schemaOf(
            "codes" to TableDefinition(
                columns = mapOf(
                    "code" to ColumnDefinition(type = NeutralType.SmallInt, required = true, unique = true),
                ),
            ),
        )
        val result = seederFor(3).seedAll(schema, countPerTable = 50)
        val values = result.getValue("codes").map { it.getValue("code") }
        values.toSet() shouldHaveSize values.size
    }

    test("unique exhaustion throws SeedUniquenessExhaustedException") {
        val schema = schemaOf(
            "flags" to TableDefinition(
                columns = mapOf(
                    "flag" to ColumnDefinition(
                        type = NeutralType.Enum(values = listOf("a", "b")),
                        required = true,
                        unique = true,
                    ),
                ),
            ),
        )
        shouldThrow<SeedUniquenessExhaustedException> {
            seederFor(1).seedAll(schema, countPerTable = 5)
        }
    }

    test("Enum column only emits declared values") {
        val values = listOf("draft", "active", "archived")
        val schema = schemaOf(
            "posts" to TableDefinition(
                columns = mapOf(
                    "status" to ColumnDefinition(type = NeutralType.Enum(values = values), required = true),
                ),
            ),
        )
        val result = seederFor(9).seedAll(schema, countPerTable = 30)
        result.getValue("posts").all { it.getValue("status") in values } shouldBe true
    }

    test("required Geometry column throws SeedPreflightException (AE-10)") {
        val schema = schemaOf(
            "places" to TableDefinition(
                columns = mapOf(
                    "location" to ColumnDefinition(type = NeutralType.Geometry(), required = true),
                ),
            ),
        )
        shouldThrow<SeedPreflightException> {
            seederFor(1).seedAll(schema, countPerTable = 1)
        }
    }

    test("nullable Geometry column becomes null instead of failing (AE-10)") {
        val schema = schemaOf(
            "places" to TableDefinition(
                columns = mapOf(
                    "location" to ColumnDefinition(type = NeutralType.Geometry(), required = false),
                ),
            ),
        )
        val result = seederFor(1).seedAll(schema, countPerTable = 3)
        result.getValue("places").all { it.getValue("location") == null } shouldBe true
    }

    test("real FK cycle: required column throws, nullable column becomes null (AE-4)") {
        val cyclic = schemaOf(
            "a" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                    "b_id" to ColumnDefinition(
                        type = NeutralType.Integer,
                        required = true,
                        references = ReferenceDefinition(table = "b", column = "id"),
                    ),
                ),
            ),
            "b" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                    "a_id" to ColumnDefinition(
                        type = NeutralType.Integer,
                        required = true,
                        references = ReferenceDefinition(table = "a", column = "id"),
                    ),
                ),
            ),
        )
        shouldThrow<SeedPreflightException> {
            seederFor(1).seedAll(cyclic, countPerTable = 3)
        }
    }
})
