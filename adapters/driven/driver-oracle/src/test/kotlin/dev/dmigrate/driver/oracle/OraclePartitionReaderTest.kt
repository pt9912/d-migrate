package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/**
 * Die Katalogformen unten sind gegen `gvenzl/oracle-free:23` gemessen
 * (2026-09-06), nicht der Doku entnommen — insbesondere die `TO_DATE`-Form
 * mit ihren drei Argumenten und dem fuehrenden Leerzeichen im Literal.
 */
class OraclePartitionReaderTest : FunSpec({

    fun jdbc(
        head: Map<String, Any?>?,
        key: List<String> = listOf("D"),
        partitions: List<Map<String, Any?>> = emptyList(),
    ): JdbcOperations = mockk {
        every { querySingle(match { it.contains("FROM all_part_tables") }, "APP", "T") } returns head
        every { queryList(match { it.contains("FROM all_part_key_columns") }, "APP", "T") } returns
            key.map { mapOf("column_name" to it) }
        every { queryList(match { it.contains("FROM all_tab_partitions") }, "APP", "T") } returns partitions
    }

    fun head(
        type: String,
        sub: String = "NONE",
        interval: String? = null,
    ): Map<String, Any?> = mapOf(
        "partitioning_type" to type, "subpartitioning_type" to sub, "interval" to interval,
    )

    fun part(name: String, high: String?, position: Int = 1): Map<String, Any?> =
        mapOf("partition_name" to name, "partition_position" to position, "high_value" to high)

    test("an unpartitioned table yields null") {
        OraclePartitionReader.read(jdbc(head = null), "APP", "T").shouldBeNull()
    }

    test("a RANGE date bound is canonicalized to the form PostgreSQL and MySQL also produce") {
        val scan = OraclePartitionReader.read(
            jdbc(
                head("RANGE"),
                partitions = listOf(
                    part(
                        "P2023",
                        "TO_DATE(' 2024-01-01 00:00:00', 'SYYYY-MM-DD HH24:MI:SS', 'NLS_CALENDAR=GREGORIAN')",
                    ),
                    part("PMAX", "MAXVALUE", position = 2),
                ),
            ),
            "APP", "T",
        )!!
        scan.config.type shouldBe PartitionType.RANGE
        scan.config.key shouldBe listOf("D")
        // Ohne die Umkehrung stuende Oracle-Syntax im neutralen Modell.
        // Der Zeitanteil bleibt stehen -- Oracles DATE traegt ihn, und der
        // Katalog fuehrt ihn. Ob er gegen eine reine Datumsgrenze passt,
        // entscheidet die Fingerabdruck-Projektion, nicht der Leser.
        scan.config.partitions[0].to shouldBe listOf(PartitionBound.Value("'2024-01-01 00:00:00'"))
        scan.config.partitions[1].to shouldBe listOf(PartitionBound.MaxValue)
    }

    test("the time component is kept verbatim, whatever the key column is") {
        val scan = OraclePartitionReader.read(
            jdbc(
                head("RANGE"),
                key = listOf("TS"),
                partitions = listOf(
                    part(
                        "S1",
                        "TO_DATE(' 2024-01-01 00:00:00', 'SYYYY-MM-DD HH24:MI:SS', 'NLS_CALENDAR=GREGORIAN')",
                    ),
                ),
            ),
            "APP", "T",
        )!!
        scan.config.partitions.single().to shouldBe listOf(PartitionBound.Value("'2024-01-01 00:00:00'"))
    }

    test("a multi-column bound splits only at top level — TO_DATE carries commas of its own") {
        val scan = OraclePartitionReader.read(
            jdbc(
                head("RANGE"),
                key = listOf("D", "B"),
                partitions = listOf(
                    part(
                        "Q1",
                        "TO_DATE(' 2024-01-01 00:00:00', 'SYYYY-MM-DD HH24:MI:SS', " +
                            "'NLS_CALENDAR=GREGORIAN'), 100",
                    ),
                ),
            ),
            "APP", "T",
        )!!
        // Naiv an jedem Komma getrennt entstuenden hier vier Grenzen statt zwei.
        scan.config.partitions.single().to shouldBe listOf(
            PartitionBound.Value("'2024-01-01 00:00:00'"),
            PartitionBound.Value("100"),
        )
    }

    test("a LIST partition carries its values, and DEFAULT is a flag rather than a value") {
        val scan = OraclePartitionReader.read(
            jdbc(
                head("LIST"),
                key = listOf("ST"),
                partitions = listOf(
                    part("L_AB", "'A', 'B'"),
                    part("L_REST", "DEFAULT", position = 2),
                ),
            ),
            "APP", "T",
        )!!
        scan.config.partitions[0].values shouldBe listOf("'A'", "'B'")
        scan.config.partitions[1].isDefault shouldBe true
        scan.config.partitions[1].values.shouldBeNull()
    }

    test("HASH partitions carry a name and nothing else — Oracle has no modulus or remainder") {
        val scan = OraclePartitionReader.read(
            jdbc(
                head("HASH"),
                key = listOf("ID"),
                partitions = listOf(part("SYS_P679", null), part("SYS_P680", null, position = 2)),
            ),
            "APP", "T",
        )!!
        scan.config.partitions.map { it.name } shouldBe listOf("SYS_P679", "SYS_P680")
        scan.config.partitions.forEach {
            it.modulus.shouldBeNull()
            it.remainder.shouldBeNull()
            it.to.shouldBeNull()
        }
    }

    test("INTERVAL and composite partitioning are carried out so the reader can report them") {
        val scan = OraclePartitionReader.read(
            jdbc(
                head("RANGE", sub = "LIST", interval = "NUMTOYMINTERVAL(1,'MONTH')"),
                partitions = listOf(part("P0", "MAXVALUE")),
            ),
            "APP", "T",
        )!!
        scan.interval shouldBe "NUMTOYMINTERVAL(1,'MONTH')"
        scan.subpartitioningType shouldBe "LIST"
    }

    test("a partitioning strategy without a neutral counterpart is not guessed at") {
        // REFERENCE/SYSTEM als RANGE zu lesen ergaebe eine Tabelle, die anders
        // partitioniert wieder entstuende.
        OraclePartitionReader.read(
            jdbc(head("REFERENCE"), partitions = listOf(part("P0", null))),
            "APP", "T",
        ).shouldBeNull()
    }
})
