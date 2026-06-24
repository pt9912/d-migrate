package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/**
 * AP6.1 (ADR 0020): MySQL-Reverse-Capture der Partitionierung aus
 * `information_schema.PARTITIONS`. Mockt die Query und prüft das Parsen je Strategie.
 */
class MysqlPartitionReaderTest : FunSpec({

    val jdbc = mockk<JdbcOperations>()

    fun stubPartitions(rows: List<Map<String, Any?>>) {
        every {
            jdbc.queryList(match { it.contains("information_schema.partitions") }, any(), any())
        } returns rows
    }

    test("non-partitioned table yields null") {
        stubPartitions(emptyList())
        MysqlPartitionReader.read(jdbc, "db", "users").shouldBeNull()
    }

    test("RANGE COLUMNS: upper bounds (to) captured, from reconstructed from contiguity (AP6.5)") {
        stubPartitions(listOf(
            mapOf("partition_name" to "p1", "partition_method" to "RANGE COLUMNS",
                "partition_expression" to "`payment_date`",
                "partition_description" to "'2022-02-01 00:00:00'", "partition_ordinal_position" to 1L),
            mapOf("partition_name" to "p_max", "partition_method" to "RANGE COLUMNS",
                "partition_expression" to "`payment_date`",
                "partition_description" to "MAXVALUE", "partition_ordinal_position" to 2L),
        ))
        val config = MysqlPartitionReader.read(jdbc, "db", "payment")!!
        config.type shouldBe PartitionType.RANGE
        config.key shouldBe listOf("payment_date")
        config.partitions[0].name shouldBe "p1"
        config.partitions[0].to shouldBe listOf(PartitionBound.Value("'2022-02-01 00:00:00'"))
        // AP6.5: first from = MINVALUE; the next from = the previous upper bound.
        config.partitions[0].from shouldBe listOf(PartitionBound.MinValue)
        config.partitions[1].to shouldBe listOf(PartitionBound.MaxValue)
        config.partitions[1].from shouldBe listOf(PartitionBound.Value("'2022-02-01 00:00:00'"))
    }

    test("LIST COLUMNS partitions captured as values") {
        stubPartitions(listOf(
            mapOf("partition_name" to "p_eu", "partition_method" to "LIST COLUMNS",
                "partition_expression" to "`region`",
                "partition_description" to "'eu','de'", "partition_ordinal_position" to 1L),
        ))
        val config = MysqlPartitionReader.read(jdbc, "db", "events")!!
        config.type shouldBe PartitionType.LIST
        config.partitions.single().values shouldBe listOf("'eu'", "'de'")
    }

    test("HASH partitions: named children → modulus/remainder synthesized (AP6.5)") {
        stubPartitions(listOf(
            mapOf("partition_name" to "p0", "partition_method" to "HASH",
                "partition_expression" to "`id`", "partition_description" to null,
                "partition_ordinal_position" to 1L),
            mapOf("partition_name" to "p1", "partition_method" to "HASH",
                "partition_expression" to "`id`", "partition_description" to null,
                "partition_ordinal_position" to 2L),
        ))
        val config = MysqlPartitionReader.read(jdbc, "db", "data")!!
        config.type shouldBe PartitionType.HASH
        config.partitions.map { it.name } shouldBe listOf("p0", "p1")
        config.partitions[0].to.shouldBeNull()
        config.partitions[0].values.shouldBeNull()
        // AP6.5: MySQL `PARTITIONS 2` → PG modulus=2, remainder = ordinal index.
        config.partitions[0].modulus shouldBe 2
        config.partitions[0].remainder shouldBe 0
        config.partitions[1].modulus shouldBe 2
        config.partitions[1].remainder shouldBe 1
    }

    test("multi-column partition key strips backticks, splits, and reconstructs arity-matched from (AP6.5)") {
        stubPartitions(listOf(
            mapOf("partition_name" to "p1", "partition_method" to "RANGE COLUMNS",
                "partition_expression" to "`a`,`b`",
                "partition_description" to "10,'x'", "partition_ordinal_position" to 1L),
        ))
        val config = MysqlPartitionReader.read(jdbc, "db", "t")!!
        config.key shouldBe listOf("a", "b")
        config.partitions.single().to shouldBe listOf(PartitionBound.Value("10"), PartitionBound.Value("'x'"))
        // First from = MINVALUE per key column (arity 2).
        config.partitions.single().from shouldBe listOf(PartitionBound.MinValue, PartitionBound.MinValue)
    }
})
