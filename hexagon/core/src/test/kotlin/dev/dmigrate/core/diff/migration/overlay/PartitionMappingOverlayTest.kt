package dev.dmigrate.core.diff.migration.overlay

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Die Overlay-Art `partition-mapping` als Dokument: ihre Bindung, ihre
 * Eintragsform und was der Validator daran prueft.
 *
 * Sie beantwortet eine Frage in zwei Auspraegungen — welche Partition des
 * Ziels welche der Quelle meint. Der Namensfall ruht auf der Angabe des
 * Anwenders, der LIST-Fall wird nachgeprueft
 * ([PartitionMappingVerifier]).
 */
class PartitionMappingOverlayTest : FunSpec({

    fun overlay(
        entries: List<MigrationOverlayEntry>,
        binding: MigrationOverlayBinding = MigrationOverlayBinding.Representation("schema-fp"),
    ) = MigrationOverlay(
        overlayKind = MigrationOverlayKinds.PARTITION_MAPPING,
        binding = binding,
        dialect = "mssql",
        entries = entries,
        createdAt = "2026-09-08T10:00:00Z",
        createdByVersion = "d-migrate-test",
    ).withComputedHash()

    fun codes(doc: MigrationOverlay) = MigrationOverlayValidator.validate(
        overlay = doc,
        context = MigrationOverlayValidationContext(
            expectedSourceFingerprint = "src-fp",
            expectedTargetFingerprint = "dst-fp",
            expectedDialect = "mssql",
            expectedRepresentationFingerprint = "schema-fp",
        ),
        source = "overlays/partitions.json",
    ).diagnostics.map { it.code }

    val nameEntry = PartitionMappingOverlayEntry(
        id = "p1", table = "orders", sourcePartition = "p_2024", targetPartition = "1",
    )

    fun listEntry(id: String, partition: String, values: List<String>, bound: String) =
        PartitionMappingOverlayEntry(
            id = id, table = "orders", sourcePartition = partition, values = values, rangeUpperBound = bound,
        )

    // ── Bindung ──────────────────────────────────────────────────

    test("the kind requires a representation binding and declares v2") {
        val doc = overlay(listOf(nameEntry))
        doc.formatVersion shouldBe MigrationOverlay.FORMAT_VERSION_V2
        codes(doc) shouldBe emptyList()
    }

    test("a pair binding is refused, not reinterpreted") {
        // Der IST-Zustand einer laufenden Datenbank ist fuer eine Darstellung
        // belanglos; ein Paar zu verlangen hiesse, etwas anderes zu behaupten.
        codes(
            overlay(listOf(nameEntry), MigrationOverlayBinding.Transition("src-fp", "dst-fp")),
        ) shouldContain MigrationOverlayDiagnostics.BINDING_MISMATCH
    }

    // ── Eintragsform ─────────────────────────────────────────────

    test("an entry that names neither an identifier nor a bound says nothing") {
        codes(
            overlay(
                listOf(PartitionMappingOverlayEntry(id = "p1", table = "orders", sourcePartition = "p_2024")),
            ),
        ) shouldContain MigrationOverlayDiagnostics.PARTITION_MAPPING_INCOMPLETE
    }

    test("values without a bound cannot be verified and are refused") {
        codes(
            overlay(
                listOf(
                    PartitionMappingOverlayEntry(
                        id = "p1", table = "orders", sourcePartition = "p_low", values = listOf("1", "2"),
                        targetPartition = "1",
                    ),
                ),
            ),
        ) shouldContain MigrationOverlayDiagnostics.PARTITION_MAPPING_INCOMPLETE
    }

    // ── Die Nachpruefung greift im Validator ─────────────────────

    test("a verifiable LIST mapping passes") {
        codes(
            overlay(
                listOf(
                    listEntry("a", "p_low", listOf("1", "2"), "3"),
                    listEntry("b", "p_high", listOf("3", "4"), "5"),
                ),
            ),
        ) shouldNotContain MigrationOverlayDiagnostics.PARTITION_MAPPING_INVALID
    }

    test("an interleaving LIST mapping is refused, and every entry of the table carries the finding") {
        // Die Verschraenkung ist eine Eigenschaft des Satzes; einen einzelnen
        // Eintrag herauszugreifen legte eine Ursache nahe, die es nicht gibt.
        val doc = overlay(
            listOf(
                listEntry("a", "p_eu", listOf("DE", "FR"), "GZ"),
                listEntry("b", "p_am", listOf("US", "CA"), "ZZ"),
            ),
        )
        val result = MigrationOverlayValidator.validate(
            overlay = doc,
            context = MigrationOverlayValidationContext(
                expectedSourceFingerprint = "src-fp",
                expectedTargetFingerprint = "dst-fp",
                expectedDialect = "mssql",
                expectedRepresentationFingerprint = "schema-fp",
            ),
            source = "overlays/partitions.json",
        )
        withClue(result.diagnostics.map { it.code to it.entryId }.toString()) {
            result.diagnostics
                .filter { it.code == MigrationOverlayDiagnostics.PARTITION_MAPPING_INVALID }
                .map { it.entryId } shouldBe listOf("a", "b")
        }
    }

    test("two tables are verified separately") {
        // Die Mengen zweier Tabellen muessen sich nicht vertragen — sie
        // beschreiben verschiedene Partitionierungen.
        val doc = overlay(
            listOf(
                listEntry("a", "p_low", listOf("1", "2"), "3"),
                PartitionMappingOverlayEntry(
                    id = "b", table = "invoices", sourcePartition = "q_low",
                    values = listOf("1", "2"), rangeUpperBound = "3",
                ),
            ),
        )
        codes(doc) shouldNotContain MigrationOverlayDiagnostics.PARTITION_MAPPING_INVALID
    }

    // ── Hash ─────────────────────────────────────────────────────

    test("the entry participates in the overlay hash") {
        val a = overlay(listOf(listEntry("a", "p_low", listOf("1", "2"), "3")))
        val b = overlay(listOf(listEntry("a", "p_low", listOf("1", "2"), "4")))
        (a.overlayHash == b.overlayHash) shouldBe false
    }

    test("the declared order of values is part of the document") {
        // Sortiert wird erst in der Pruefung; das Dokument bleibt, wie es
        // geschrieben wurde, sonst haenge der Hash an einer Normalisierung.
        val a = overlay(listOf(listEntry("a", "p_low", listOf("1", "2"), "3")))
        val b = overlay(listOf(listEntry("a", "p_low", listOf("2", "1"), "3")))
        (a.overlayHash == b.overlayHash) shouldBe false
    }
})
