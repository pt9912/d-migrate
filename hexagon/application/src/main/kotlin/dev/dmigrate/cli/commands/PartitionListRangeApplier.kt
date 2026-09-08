package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.PartitionListToRange
import dev.dmigrate.core.diff.migration.overlay.PartitionMappingOverlayEntry
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities

/**
 * Setzt eine LIST-Partitionierung als RANGE, wo der Zieldialekt LIST nicht
 * kennt und der Anwender die Zuordnung beigesteuert hat.
 *
 * Umgeschrieben wird das **Schema**, nicht der Renderer. Das ist die Stelle,
 * an der es fuer alle Befehle zugleich gilt: der Generator erzeugt daraus
 * gewoehnliches RANGE-DDL, der Vergleich sieht auf beiden Seiten dieselbe
 * Form, und der Abdruck haengt nicht an einer Einstellung. Ein Umweg ueber
 * den Renderer haette dieselbe Uebersetzung an drei Stellen gebraucht — und
 * einen Vergleich, der weiter LIST gegen RANGE haelt.
 *
 * **Nur wo der Dialekt es braucht.** PostgreSQL, MySQL und Oracle kennen
 * LIST; dort waere die Uebersetzung ein Verlust, kein Gewinn. Wo der Dialekt
 * gar nicht partitioniert, gibt es nichts zu uebersetzen.
 *
 * **Eine untaugliche Zuordnung ist ein Abbruch, kein Rueckfall.** Wer eine
 * Zuordnung vorlegt, die falsch routen wuerde, bekommt sie benannt zurueck;
 * still auf „dann eben nicht partitioniert" auszuweichen ergaebe eine
 * Tabelle, die aussieht wie die gewuenschte.
 */
internal object PartitionListRangeApplier {

    sealed interface Result {
        /** [tables] nennt, was uebersetzt wurde — leer, wenn nichts zu tun war. */
        data class Applied(val schema: SchemaDefinition, val tables: List<String>) : Result

        data class Refused(val table: String, val reason: String) : Result
    }

    fun apply(
        schema: SchemaDefinition,
        dialect: DatabaseDialect,
        documents: List<MigrationOverlayDocument>,
    ): Result {
        val caps = DialectCapabilities.forDialect(dialect)
        if (caps.supportsListPartitioning || !caps.supportsPartitioning) return Result.Applied(schema, emptyList())

        val entries = documents
            .flatMap { it.overlay.entries }
            .filterIsInstance<PartitionMappingOverlayEntry>()
        if (entries.isEmpty()) return Result.Applied(schema, emptyList())

        val translated = mutableListOf<String>()
        val tables = schema.tables.mapValues { (name, table) ->
            val partitioning = table.partitioning ?: return@mapValues table
            when (val result = PartitionListToRange.translate(name, partitioning, entries)) {
                is PartitionListToRange.Result.Refused -> return Result.Refused(name, result.reason)
                PartitionListToRange.Result.NotApplicable -> table
                is PartitionListToRange.Result.Translated -> {
                    translated += name
                    table.copy(partitioning = result.config)
                }
            }
        }
        return Result.Applied(schema.copy(tables = tables), translated)
    }
}
