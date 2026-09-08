package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayBinding
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.PartitionMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Kindnamen aus dem Overlay in ein zurueckgelesenes Schema setzen.
 *
 * SQL Server nummeriert Partitionen; sein Reverse vergibt `p1`, `p2`, … und
 * meldet das (`R346`). Wer die urspruenglichen Namen kennt, steuert sie bei —
 * geraten wird nichts.
 */
class PartitionNameOverlayApplierTest : FunSpec({

    fun schema(vararg partitionNames: String) = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, ordinal = 1)),
                primaryKey = listOf("id"),
                partitioning = PartitionConfig(
                    type = PartitionType.RANGE,
                    key = listOf("id"),
                    partitions = partitionNames.map {
                        PartitionDefinition(name = it, to = listOf(PartitionBound.MaxValue))
                    },
                ),
            ),
        ),
    )

    fun overlay(vararg entries: PartitionMappingOverlayEntry) = listOf(
        MigrationOverlayDocument(
            source = "overlays/names.json",
            overlay = MigrationOverlay(
                overlayKind = MigrationOverlayKinds.PARTITION_MAPPING,
                binding = MigrationOverlayBinding.Representation("schema-fp"),
                dialect = "mssql",
                entries = entries.toList(),
                createdAt = "2026-09-08T10:00:00Z",
                createdByVersion = "d-migrate-test",
            ).withComputedHash(),
        ),
    )

    val r346 = SchemaReadNote(
        SchemaReadSeverity.INFO, "R346", "orders", "SQL Server numbers partitions; …",
    )

    fun namesOf(s: SchemaDefinition) =
        s.tables.getValue("orders").partitioning!!.partitions.map { it.name }

    test("the overlay replaces the synthesised identifiers with the names it carries") {
        val applied = PartitionNameOverlayApplier.apply(
            schema("p1", "p2"),
            listOf(r346),
            overlay(
                PartitionMappingOverlayEntry(
                    id = "a", table = "orders", sourcePartition = "p_2024", targetPartition = "p1",
                ),
                PartitionMappingOverlayEntry(
                    id = "b", table = "orders", sourcePartition = "p_2025", targetPartition = "p2",
                ),
            ),
        )
        namesOf(applied.schema) shouldContainExactly listOf("p_2024", "p_2025")
    }

    test("a fully named table no longer carries R346 — the names are in the result now") {
        val applied = PartitionNameOverlayApplier.apply(
            schema("p1", "p2"),
            listOf(r346),
            overlay(
                PartitionMappingOverlayEntry(id = "a", table = "orders", sourcePartition = "x", targetPartition = "p1"),
                PartitionMappingOverlayEntry(id = "b", table = "orders", sourcePartition = "y", targetPartition = "p2"),
            ),
        )
        applied.notes shouldBe emptyList()
    }

    test("a partly named table keeps R346 — the rest is still guessed") {
        // Die Meldung verstummen zu lassen, waere hier eine Unwahrheit: `p2`
        // traegt weiterhin einen Namen, den niemand vergeben hat.
        val applied = PartitionNameOverlayApplier.apply(
            schema("p1", "p2"),
            listOf(r346),
            overlay(
                PartitionMappingOverlayEntry(id = "a", table = "orders", sourcePartition = "x", targetPartition = "p1"),
            ),
        )
        withClue(namesOf(applied.schema).toString()) {
            namesOf(applied.schema) shouldContainExactly listOf("x", "p2")
            applied.notes.map { it.code } shouldContainExactly listOf("R346")
        }
    }

    test("an entry for another table leaves this one alone") {
        val applied = PartitionNameOverlayApplier.apply(
            schema("p1"),
            listOf(r346),
            overlay(
                PartitionMappingOverlayEntry(
                    id = "a", table = "invoices", sourcePartition = "x", targetPartition = "p1",
                ),
            ),
        )
        namesOf(applied.schema) shouldContainExactly listOf("p1")
        applied.notes.map { it.code } shouldContainExactly listOf("R346")
    }

    test("a LIST-case entry names no identifier and changes nothing here") {
        // Es ist dieselbe Overlay-Art, aber die andere Auspraegung; sie
        // beantwortet die Namensfrage nicht.
        val applied = PartitionNameOverlayApplier.apply(
            schema("p1"),
            listOf(r346),
            overlay(
                PartitionMappingOverlayEntry(
                    id = "a", table = "orders", sourcePartition = "p_low",
                    values = listOf("1", "2"), rangeUpperBound = "3",
                ),
            ),
        )
        namesOf(applied.schema) shouldContainExactly listOf("p1")
        applied.notes.map { it.code } shouldContainExactly listOf("R346")
    }

    test("no overlay leaves schema and notes untouched") {
        val original = schema("p1")
        val applied = PartitionNameOverlayApplier.apply(original, listOf(r346), emptyList())
        applied.schema shouldBe original
        applied.notes shouldContainExactly listOf(r346)
    }
})
