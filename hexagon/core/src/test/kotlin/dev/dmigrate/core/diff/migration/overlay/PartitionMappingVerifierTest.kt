package dev.dmigrate.core.diff.migration.overlay

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

/**
 * Die Nachpruefung der LIST-nach-RANGE-Zuordnung.
 *
 * Sie ist der Grund, warum dieser Fall wertvoller ist als der Namensfall: bei
 * Namen ist die Angabe des Anwenders die einzige Quelle, hier nicht. Eine
 * Zuordnung, die eine Zeile in die falsche Partition routen wuerde, darf nicht
 * durchkommen.
 */
class PartitionMappingVerifierTest : FunSpec({

    fun mapping(name: String, values: List<String>, bound: String) =
        PartitionMappingVerifier.Mapping(name, values, bound)

    fun verify(vararg m: PartitionMappingVerifier.Mapping) = PartitionMappingVerifier.verify(m.toList())

    fun reasonOf(result: PartitionMappingVerifier.Result): String =
        result.shouldBeInstanceOf<PartitionMappingVerifier.Result.Invalid>().reason

    // ── Der Fall aus dem Plan ────────────────────────────────────

    test("contiguous integer sets map onto the bounds the plan names") {
        // `(1,2), (3,4)` -> Grenzen `3, 5`.
        verify(
            mapping("p_low", listOf("1", "2"), "3"),
            mapping("p_high", listOf("3", "4"), "5"),
        ) shouldBe PartitionMappingVerifier.Result.Valid
    }

    test("interleaving sets are refused with a named reason") {
        // `('DE','FR'), ('US','CA')` -- `CA` liegt vor `DE`, in jeder Ordnung
        // verschraenken sich die Mengen.
        val result = verify(
            mapping("p_eu", listOf("DE", "FR"), "GZ"),
            mapping("p_am", listOf("US", "CA"), "ZZ"),
        )
        reasonOf(result) shouldContain "interleave"
    }

    // ── Die Grenzen muessen richtig routen ───────────────────────

    test("a bound that does not cover its own values is refused") {
        // Grenze 2 auf der Menge (1,2): `VALUES LESS THAN (2)` liesse die 2 in
        // die naechste Partition fallen.
        reasonOf(
            verify(
                mapping("p_low", listOf("1", "2"), "2"),
                mapping("p_high", listOf("3", "4"), "5"),
            ),
        ) shouldContain "does not cover its own value"
    }

    test("a bound that reaches into the next partition is refused") {
        // Grenze 4 auf (1,2) zoege die 3 der naechsten Menge mit herueber.
        reasonOf(
            verify(
                mapping("p_low", listOf("1", "2"), "4"),
                mapping("p_high", listOf("3", "4"), "5"),
            ),
        ) shouldContain "reaches into"
    }

    test("a value in two partitions is refused — the target would be ambiguous") {
        reasonOf(
            verify(
                mapping("p_a", listOf("1", "2"), "3"),
                mapping("p_b", listOf("2", "5"), "6"),
            ),
        ) shouldContain "more than one partition"
    }

    test("an empty value set says nothing and is refused") {
        reasonOf(verify(mapping("p_a", emptyList(), "3"))) shouldContain "empty value set"
    }

    // ── Ordnung ──────────────────────────────────────────────────

    test("all-numeric literals are ordered numerically, not alphabetically") {
        // Alphabetisch laege '10' vor '9' und die Mengen verschraenkten sich.
        verify(
            mapping("p_low", listOf("9"), "10"),
            mapping("p_high", listOf("10", "11"), "12"),
        ) shouldBe PartitionMappingVerifier.Result.Valid
    }

    test("a single mapping only has to cover itself") {
        verify(mapping("p_all", listOf("1", "2", "3"), "4")) shouldBe PartitionMappingVerifier.Result.Valid
        reasonOf(verify(mapping("p_all", listOf("1", "2", "3"), "3"))) shouldContain "does not cover"
    }

    test("nothing to verify is valid") {
        PartitionMappingVerifier.verify(emptyList()) shouldBe PartitionMappingVerifier.Result.Valid
    }

    // ── Property: akzeptiert genau dann, wenn zusammenhaengend ───

    test("a partition of consecutive integers into runs is accepted, any other split is not") {
        checkAll(
            iterations = 300,
            Arb.list(Arb.int(1..6), 2..5),
        ) { cuts ->
            // Aus den Schnittgroessen zusammenhaengende Laeufe ueber 1..n bauen.
            var next = 1
            val runs = cuts.map { size -> (0 until size).map { (next++).toString() } }
            val contiguous = runs.mapIndexed { index, values ->
                mapping("p$index", values, (values.last().toInt() + 1).toString())
            }
            withClue("zusammenhaengend: $runs") {
                PartitionMappingVerifier.verify(contiguous) shouldBe PartitionMappingVerifier.Result.Valid
            }

            // Dieselben Mengen, aber zwei Laeufe getauscht -> verschraenkt,
            // sobald es mehr als einen gibt und sie nicht gleich sind.
            if (runs.size >= 2 && runs.first() != runs.last()) {
                val swapped = contiguous.toMutableList()
                val firstValues = swapped[0].values
                swapped[0] = swapped[0].copy(values = swapped[swapped.lastIndex].values)
                swapped[swapped.lastIndex] = swapped[swapped.lastIndex].copy(values = firstValues)
                withClue("getauscht: $swapped") {
                    (PartitionMappingVerifier.verify(swapped) is PartitionMappingVerifier.Result.Invalid) shouldBe true
                }
            }
        }
    }
})
