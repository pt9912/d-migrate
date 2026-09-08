package dev.dmigrate.core.diff.migration.overlay

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Was aus einer LIST-Partitionierung wird, wenn der Anwender die Zuordnung zu
 * RANGE-Grenzen beisteuert — und wann daraus nichts wird.
 *
 * Die Ablehnungen sind hier das Eigentliche: eine Uebersetzung, die nicht
 * stimmt, routet Zeilen in die falsche Partition, und das faellt erst auf,
 * wenn jemand die Daten sucht, die dort liegen sollten.
 */
class PartitionListToRangeTest : FunSpec({

    fun listConfig(vararg partitions: PartitionDefinition) =
        PartitionConfig(type = PartitionType.LIST, key = listOf("region_id"), partitions = partitions.toList())

    fun part(name: String, vararg values: String) =
        PartitionDefinition(name = name, values = values.toList())

    fun entry(partition: String, values: List<String>, bound: String, table: String = "sales") =
        PartitionMappingOverlayEntry(
            id = "e-$partition", table = table, sourcePartition = partition,
            values = values, rangeUpperBound = bound,
        )

    test("a checked mapping becomes ascending half-open intervals, whatever order it was written in") {
        // Die Reihenfolge im Dokument ist die des Autors; die Grenzen einer
        // RANGE-Partitionierung sind aufsteigend, sonst routet sie nicht.
        val config = listConfig(part("p_hi", "5", "6"), part("p_lo", "1", "2"))
        val result = PartitionListToRange.translate(
            table = "sales",
            config = config,
            entries = listOf(
                entry("p_hi", listOf("5", "6"), "7"),
                entry("p_lo", listOf("1", "2"), "3"),
            ),
        )

        result.shouldBeInstanceOf<PartitionListToRange.Result.Translated>()
        result.config.type shouldBe PartitionType.RANGE
        result.config.key shouldBe listOf("region_id")
        result.config.partitions.map { it.name } shouldBe listOf("p_lo", "p_hi", "p_hi_above")
        result.config.partitions.map { it.from } shouldBe listOf(
            listOf(PartitionBound.MinValue),
            listOf(PartitionBound.Value("3")),
            listOf(PartitionBound.Value("7")),
        )
        result.config.partitions.map { it.to } shouldBe listOf(
            listOf(PartitionBound.Value("3")),
            listOf(PartitionBound.Value("7")),
            listOf(PartitionBound.MaxValue),
        )
        // Die Wertemenge ist in der RANGE-Form keine Aussage mehr — sie stuende
        // dann neben Grenzen, die dasselbe anders sagen.
        result.config.partitions.all { it.values == null } shouldBe true
    }

    test("what LIST rejected, the translated form accepts — and says so by carrying the partition") {
        // Oberhalb der letzten Grenze hat RANGE eine Partition und LIST keine.
        // Sie im Modell wegzulassen hiesse, ein Schema zu fuehren, das eine
        // Partition weniger beschreibt, als danach existiert.
        val result = PartitionListToRange.translate(
            table = "sales",
            config = listConfig(part("p_lo", "1")),
            entries = listOf(entry("p_lo", listOf("1"), "2")),
        )

        result.shouldBeInstanceOf<PartitionListToRange.Result.Translated>()
        result.config.partitions.last().to shouldBe listOf(PartitionBound.MaxValue)
        result.config.partitions.size shouldBe 2
    }

    test("interleaved value sets are refused, with the reason the verifier gives") {
        val result = PartitionListToRange.translate(
            table = "sales",
            config = listConfig(part("p_eu", "'DE'", "'FR'"), part("p_am", "'US'", "'CA'")),
            entries = listOf(
                entry("p_eu", listOf("'DE'", "'FR'"), "'GZ'"),
                entry("p_am", listOf("'US'", "'CA'"), "'ZZ'"),
            ),
        )

        result.shouldBeInstanceOf<PartitionListToRange.Result.Refused>()
        result.reason shouldContain "interleave"
    }

    test("a partition the mapping does not name is refused, not quietly dropped") {
        // Sie wegzulassen hiesse, ihre Zeilen einer fremden Partition zu geben.
        val result = PartitionListToRange.translate(
            table = "sales",
            config = listConfig(part("p_lo", "1"), part("p_hi", "5")),
            entries = listOf(entry("p_lo", listOf("1"), "2")),
        )

        result.shouldBeInstanceOf<PartitionListToRange.Result.Refused>()
        result.reason shouldContain "p_hi"
    }

    test("a mapping that claims another value set than the schema is refused") {
        // Sonst buergte die Nachpruefung fuer eine Partitionierung, die es
        // nicht ist.
        val result = PartitionListToRange.translate(
            table = "sales",
            config = listConfig(part("p_lo", "1", "2")),
            entries = listOf(entry("p_lo", listOf("1", "9"), "3")),
        )

        result.shouldBeInstanceOf<PartitionListToRange.Result.Refused>()
        result.reason shouldContain "different value set"
    }

    test("a LIST DEFAULT partition has no RANGE counterpart") {
        val result = PartitionListToRange.translate(
            table = "sales",
            config = listConfig(part("p_lo", "1"), PartitionDefinition(name = "p_rest", isDefault = true)),
            entries = listOf(entry("p_lo", listOf("1"), "2")),
        )

        result.shouldBeInstanceOf<PartitionListToRange.Result.Refused>()
        result.reason shouldContain "catch-all"
    }

    test("a RANGE partitioning needs no translation, and a mapping for another table is none") {
        PartitionListToRange.translate(
            table = "sales",
            config = PartitionConfig(
                type = PartitionType.RANGE, key = listOf("day"),
                partitions = listOf(PartitionDefinition(name = "p1")),
            ),
            entries = listOf(entry("p1", listOf("1"), "2")),
        ) shouldBe PartitionListToRange.Result.NotApplicable

        PartitionListToRange.translate(
            table = "sales",
            config = listConfig(part("p_lo", "1")),
            entries = listOf(entry("p_lo", listOf("1"), "2", table = "orders")),
        ) shouldBe PartitionListToRange.Result.NotApplicable
    }
})
