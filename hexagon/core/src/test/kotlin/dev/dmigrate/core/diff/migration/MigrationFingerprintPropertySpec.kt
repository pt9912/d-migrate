package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.schemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.checkAll

/**
 * Property-Based Testing für [MigrationFingerprint] (LN-046, ADR 0029, Phase C).
 *
 * Der Fingerprint ist der kanonische Gleichheits-Anker der Migrations-Pipeline;
 * diese Invarianten sichern seine beiden Kern-Zusagen über generierte Schemata:
 * Reihenfolge-Unabhängigkeit (Maps werden lexikografisch projiziert) und der
 * bewusste Ausschluss reiner Reporting-Metadaten.
 */
class MigrationFingerprintPropertySpec : FunSpec({

    test("Fingerprint ist unabhängig von der Einfügereihenfolge der Tabellen und Spalten") {
        checkAll(Arb.schemaDefinition()) { schema ->
            val reordered = schema.copy(
                tables = schema.tables.entries.reversed().associate { (name, table) ->
                    name to table.copy(
                        columns = table.columns.entries.reversed().associate { it.key to it.value },
                    )
                },
            )
            MigrationFingerprint.compute(reordered) shouldBe MigrationFingerprint.compute(schema)
        }
    }

    test("Reine Reporting-Metadaten (name/version/description) fließen nicht in den Fingerprint ein") {
        checkAll(Arb.schemaDefinition()) { schema ->
            val relabeled = schema.copy(
                name = schema.name + "_x",
                version = schema.version + "_y",
                description = "changed",
            )
            MigrationFingerprint.compute(relabeled) shouldBe MigrationFingerprint.compute(schema)
        }
    }
})
