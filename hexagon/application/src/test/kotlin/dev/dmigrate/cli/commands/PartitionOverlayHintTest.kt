package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayBinding
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidationContext
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidator
import dev.dmigrate.core.diff.migration.overlay.PartitionMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Die Partitions-Meldungen nennen den Abdruck, an den ein Overlay zu binden
 * waere.
 *
 * Ohne ihn sind `R346` und `E055` Feststellungen ohne Ausweg: kein Befehl gibt
 * den Fingerabdruck eines Schemas aus, und ohne ihn nimmt der Validator kein
 * `partition-mapping`-Overlay an.
 *
 * Der Test unten ist die eigentliche Abnahme: er **parst die Meldung**, baut
 * daraus ein Overlay und laesst es validieren. Damit ist belegt, dass der
 * genannte Wert derselbe ist, den der Validator erwartet — und nicht bloss,
 * dass irgendein Wert in der Meldung steht.
 */
class PartitionOverlayHintTest : FunSpec({

    val schema = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, ordinal = 1),
                    "region" to ColumnDefinition(NeutralType.Text(maxLength = 10), ordinal = 2),
                ),
                primaryKey = listOf("id"),
                partitioning = PartitionConfig(
                    type = PartitionType.LIST,
                    key = listOf("region"),
                    partitions = listOf(
                        PartitionDefinition(name = "p_low", values = listOf("1", "2")),
                        PartitionDefinition(name = "p_high", values = listOf("3", "4")),
                    ),
                ),
            ),
        ),
    )

    /** Was ein Anwender aus der Meldung herausliest. */
    fun fingerprintFrom(message: String): String? =
        Regex("schemaFingerprint `([0-9a-f]+)`").find(message)?.groupValues?.get(1)

    test("a reverse note about numbered partitions gains the fingerprint to bind to") {
        val notes = listOf(
            SchemaReadNote(
                severity = SchemaReadSeverity.INFO,
                code = "R346",
                objectName = "orders",
                message = "SQL Server numbers partitions; …",
            ),
        )
        val enriched = PartitionOverlayHint.enrichReadNotes(notes, schema, DatabaseDialect.MSSQL).single()
        withClue(enriched.hint.orEmpty()) {
            enriched.hint!! shouldContain MigrationOverlayKinds.PARTITION_MAPPING
            enriched.hint!! shouldContain "targetPartition"
            fingerprintFrom(enriched.hint!!).shouldNotBeNull()
        }
    }

    test("an unrelated note is left alone") {
        val notes = listOf(
            SchemaReadNote(SchemaReadSeverity.WARNING, "R301", "orders.x", "unbridged type", hint = "keep me"),
        )
        PartitionOverlayHint.enrichReadNotes(notes, schema, DatabaseDialect.MSSQL).single().hint shouldBe "keep me"
    }

    test("the E055 note stays silent for now — the generate path does not read the overlay yet") {
        // Ein Hinweis auf eine Datei, die kein Befehl entgegennimmt, waere
        // schlechter als keiner. Er kommt mit der Naht im Generate-Pfad.
        val note = SchemaReadNote(SchemaReadSeverity.ACTION_REQUIRED, "E055", "orders", "LIST not rendered")
        PartitionOverlayHint.enrichReadNotes(listOf(note), schema, DatabaseDialect.MSSQL).single().hint shouldBe null
    }

    // ── Die Abnahme aus dem Plan ─────────────────────────────────

    test("an overlay built from the message alone is accepted by the validator") {
        // Kein zweiter Kanal: der Fingerabdruck kommt aus der Meldung, nicht
        // aus derselben Funktion, die der Test danach zum Vergleich aufruft.
        val note = SchemaReadNote(
            severity = SchemaReadSeverity.INFO,
            code = "R346",
            objectName = "orders",
            message = "SQL Server numbers partitions; the 2 partitions of 'orders' were named p1…p2.",
            hint = "The original names are not stored in the database.",
        )
        val enriched = PartitionOverlayHint
            .enrichReadNotes(listOf(note), schema, DatabaseDialect.MSSQL)
            .single()

        val fingerprint = fingerprintFrom(enriched.hint.orEmpty())
        withClue(enriched.hint.orEmpty()) { fingerprint.shouldNotBeNull() }

        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.PARTITION_MAPPING,
            binding = MigrationOverlayBinding.Representation(fingerprint!!),
            dialect = "mssql",
            entries = listOf(
                PartitionMappingOverlayEntry(
                    id = "low", table = "orders", sourcePartition = "p_low",
                    values = listOf("1", "2"), rangeUpperBound = "3",
                ),
                PartitionMappingOverlayEntry(
                    id = "high", table = "orders", sourcePartition = "p_high",
                    values = listOf("3", "4"), rangeUpperBound = "5",
                ),
            ),
            createdAt = "2026-09-08T10:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()

        val result = MigrationOverlayValidator.validate(
            overlay = overlay,
            context = MigrationOverlayValidationContext(
                expectedSourceFingerprint = "unused",
                expectedTargetFingerprint = "unused",
                expectedDialect = "mssql",
                // Genau der Weg, den ein Befehl nimmt — dieselbe Funktion, die
                // die Meldung gespeist hat.
                expectedRepresentationFingerprint =
                    PartitionOverlayHint.representationFingerprint(schema, DatabaseDialect.MSSQL),
            ),
            source = "overlays/from-message.json",
        )

        withClue(result.diagnostics.map { it.code to it.message }.toString()) {
            result.hasBlockers shouldBe false
        }
    }

    test("a fingerprint that is not the one named is refused — the binding is not decorative") {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.PARTITION_MAPPING,
            binding = MigrationOverlayBinding.Representation("0000"),
            dialect = "mssql",
            entries = listOf(
                PartitionMappingOverlayEntry(
                    id = "low", table = "orders", sourcePartition = "p_low", targetPartition = "1",
                ),
            ),
            createdAt = "2026-09-08T10:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()

        MigrationOverlayValidator.validate(
            overlay = overlay,
            context = MigrationOverlayValidationContext(
                expectedSourceFingerprint = "unused",
                expectedTargetFingerprint = "unused",
                expectedDialect = "mssql",
                expectedRepresentationFingerprint =
                    PartitionOverlayHint.representationFingerprint(schema, DatabaseDialect.MSSQL),
            ),
            source = "overlays/stale.json",
        ).diagnostics.map { it.code } shouldBe listOf(MigrationOverlayDiagnostics.STALE_SCHEMA_FINGERPRINT)
    }
})
