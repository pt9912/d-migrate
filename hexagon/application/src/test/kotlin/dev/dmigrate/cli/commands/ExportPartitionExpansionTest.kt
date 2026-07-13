package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class ExportPartitionExpansionTest : FunSpec({

    fun partitioned(children: List<String>?) = TableDefinition(
        partitioning = children?.let {
            PartitionConfig(PartitionType.RANGE, listOf("id"), it.map { c -> PartitionDefinition(name = c) })
        },
    )

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "s", version = "1", tables = tables.toMap())

    test("fans out every partitioned parent into its children") {
        val s = schema(
            "payment" to partitioned(listOf("payment_p1", "payment_p2")),
            "users" to partitioned(null),
        )

        ExportPartitionExpansion.plan(s, listOf("payment", "users")) shouldContainExactly
            mapOf("payment" to listOf("payment_p1", "payment_p2"))
    }

    test("qualifies child names with the parent's schema prefix") {
        val s = schema("public.payment" to partitioned(listOf("payment_p1")))

        ExportPartitionExpansion.plan(s, listOf("public.payment"))["public.payment"] shouldBe
            listOf("public.payment_p1")
    }
})
