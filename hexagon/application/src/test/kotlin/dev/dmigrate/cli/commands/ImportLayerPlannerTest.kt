package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

class ImportLayerPlannerTest : FunSpec({

    fun col(refTable: String? = null) = ColumnDefinition(
        type = NeutralType.Integer,
        references = refTable?.let { ReferenceDefinition(table = it, column = "id") },
    )

    fun table(columns: Map<String, ColumnDefinition> = emptyMap(), children: List<String>? = null) = TableDefinition(
        columns = columns,
        partitioning = children?.let {
            PartitionConfig(PartitionType.RANGE, listOf("id"), it.map { c -> PartitionDefinition(name = c) })
        },
    )

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "s", version = "1", tables = tables.toMap())

    test("FK chain a->b->c yields three layers, roots first") {
        val s = schema(
            "a" to table(mapOf("b_id" to col("b"))),
            "b" to table(mapOf("c_id" to col("c"))),
            "c" to table(),
        )
        ImportLayerPlanner.plan(s, listOf("a", "b", "c")) shouldContainExactly
            listOf(listOf("c"), listOf("b"), listOf("a"))
    }

    test("independent tables share one layer") {
        val s = schema("x" to table(), "y" to table())
        ImportLayerPlanner.plan(s, listOf("x", "y")).single() shouldContainExactlyInAnyOrder listOf("x", "y")
    }

    test("child-partition inputs inherit their parent's FK layer") {
        val s = schema(
            "customer" to table(),
            "payment" to table(mapOf("cust_id" to col("customer")), children = listOf("payment_p1", "payment_p2")),
        )
        val layers = ImportLayerPlanner.plan(s, listOf("customer", "payment_p1", "payment_p2"))
        layers[0] shouldContainExactly listOf("customer")
        layers[1] shouldContainExactlyInAnyOrder listOf("payment_p1", "payment_p2")
    }

    test("cyclic tables land in a trailing layer but stay covered") {
        val s = schema(
            "a" to table(mapOf("b_id" to col("b"))),
            "b" to table(mapOf("a_id" to col("a"))),
        )
        ImportLayerPlanner.plan(s, listOf("a", "b")).flatten() shouldContainExactlyInAnyOrder listOf("a", "b")
    }
})
