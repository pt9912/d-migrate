package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class PartitionTransferExpansionTest : FunSpec({

    fun partitioned(children: List<String>?) = TableDefinition(
        partitioning = children?.let {
            PartitionConfig(PartitionType.RANGE, listOf("id"), it.map { c -> PartitionDefinition(name = c) })
        },
    )

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "s", version = "1", tables = tables.toMap())

    test("expands a parent partitioned identically on both sides") {
        val kids = listOf("payment_p1", "payment_p2", "payment_p3")
        val src = schema("payment" to partitioned(kids))
        val tgt = schema("payment" to partitioned(kids))

        PartitionTransferExpansion.plan(src, tgt, listOf("payment")) shouldContainExactly
            mapOf("payment" to kids)
    }

    test("no expansion when the target is not partitioned") {
        val src = schema("payment" to partitioned(listOf("payment_p1")))
        val tgt = schema("payment" to partitioned(null))

        PartitionTransferExpansion.plan(src, tgt, listOf("payment")).shouldBeEmpty()
    }

    test("no expansion when child-name sets differ") {
        val src = schema("payment" to partitioned(listOf("payment_p1", "payment_p2")))
        val tgt = schema("payment" to partitioned(listOf("payment_p1", "payment_pX")))

        PartitionTransferExpansion.plan(src, tgt, listOf("payment")).shouldBeEmpty()
    }

    test("no expansion for a non-partitioned table") {
        val src = schema("users" to partitioned(null))
        val tgt = schema("users" to partitioned(null))

        PartitionTransferExpansion.plan(src, tgt, listOf("users")).shouldBeEmpty()
    }

    test("child names are qualified with the parent's schema prefix") {
        val kids = listOf("payment_p1", "payment_p2")
        val src = schema("public.payment" to partitioned(kids))
        val tgt = schema("public.payment" to partitioned(kids))

        val plan = PartitionTransferExpansion.plan(src, tgt, listOf("public.payment"))
        plan["public.payment"] shouldBe listOf("public.payment_p1", "public.payment_p2")
    }
})
