package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * AP4 (ADR 0019): partitions-bewusster Comparator. Der frühere Test
 * „partitioning changes do not produce diff" ist **umgedreht** — ein Unterschied
 * in Strategie, Schlüssel oder Kind-Partitionsmenge erzeugt jetzt einen Diff
 * (`schema compare` → DIFFERENT). Kind-Partitionen werden als **Menge**
 * verglichen (reihenfolge-unabhängig).
 *
 * Eigene Spec (nicht in SchemaComparatorTest), um die LargeClass-Schwelle nicht
 * zu reißen — echte Aufteilung statt `@Suppress`.
 */
class SchemaComparatorPartitioningTest : FunSpec({

    val comparator = SchemaComparator()

    fun partitioned(config: PartitionConfig?) = SchemaDefinition(
        name = "Test", version = "1.0",
        tables = mapOf("t" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "created_at" to ColumnDefinition(NeutralType.DateTime()),
            ),
            partitioning = config,
        )),
    )

    fun rangePart(name: String, from: String, to: String) = PartitionDefinition(
        name = name,
        from = listOf(PartitionBound.Value(from)),
        to = listOf(PartitionBound.Value(to)),
    )

    val rangeOnCreatedAt = PartitionConfig(
        PartitionType.RANGE, listOf("created_at"),
        partitions = listOf(
            rangePart("p2022_01", "'2022-01-01'", "'2022-02-01'"),
            rangePart("p2022_02", "'2022-02-01'", "'2022-03-01'"),
        ),
    )

    test("adding partitioning (none -> RANGE) produces a diff") {
        val diff = comparator.compare(partitioned(null), partitioned(rangeOnCreatedAt))
        diff.isEmpty() shouldBe false
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].partitioning.shouldNotBeNull()
        diff.tablesChanged[0].partitioning!!.before.shouldBeNull()
        diff.tablesChanged[0].partitioning!!.after shouldBe rangeOnCreatedAt
    }

    test("changed partition strategy (RANGE -> HASH) produces a diff") {
        val hash = PartitionConfig(
            PartitionType.HASH, listOf("created_at"),
            partitions = listOf(PartitionDefinition(name = "h0", modulus = 2, remainder = 0)),
        )
        comparator.compare(partitioned(rangeOnCreatedAt), partitioned(hash))
            .tablesChanged.single().partitioning.shouldNotBeNull()
    }

    test("changed partition key produces a diff") {
        val otherKey = rangeOnCreatedAt.copy(key = listOf("id"))
        comparator.compare(partitioned(rangeOnCreatedAt), partitioned(otherKey))
            .tablesChanged.single().partitioning.shouldNotBeNull()
    }

    test("changed child bound produces a diff") {
        val shifted = rangeOnCreatedAt.copy(
            partitions = listOf(
                rangePart("p2022_01", "'2022-01-01'", "'2022-02-15'"), // upper bound moved
                rangePart("p2022_02", "'2022-02-01'", "'2022-03-01'"),
            ),
        )
        comparator.compare(partitioned(rangeOnCreatedAt), partitioned(shifted))
            .tablesChanged.single().partitioning.shouldNotBeNull()
    }

    test("added child partition produces a diff") {
        val extra = rangeOnCreatedAt.copy(
            partitions = rangeOnCreatedAt.partitions + rangePart("p2022_03", "'2022-03-01'", "'2022-04-01'"),
        )
        comparator.compare(partitioned(rangeOnCreatedAt), partitioned(extra))
            .tablesChanged.single().partitioning.shouldNotBeNull()
    }

    test("child partition order does not matter (set equality)") {
        val reordered = rangeOnCreatedAt.copy(partitions = rangeOnCreatedAt.partitions.reversed())
        comparator.compare(partitioned(rangeOnCreatedAt), partitioned(reordered))
            .isEmpty() shouldBe true
    }

    test("identical partitioning produces no diff") {
        comparator.compare(partitioned(rangeOnCreatedAt), partitioned(rangeOnCreatedAt))
            .isEmpty() shouldBe true
    }
})
