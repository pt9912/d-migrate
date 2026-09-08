package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.PartitionMappingOverlayEntry
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.SchemaReadNote

/**
 * Setzt die Kindnamen aus einem `partition-mapping`-Overlay in ein
 * zurueckgelesenes Schema.
 *
 * SQL Server nummeriert Partitionen; sein Reverse kann `p_2024` nicht
 * zurueckgeben und vergibt `p1`, `p2`, … in Grenzreihenfolge (`R346`). Wer die
 * urspruenglichen Namen kennt, steuert sie ueber das Overlay bei — das
 * Werkzeug rraet sie nicht.
 *
 * **Der Eintrag zeigt vom Ziel auf die Quelle.** `targetPartition` ist der
 * Bezeichner, unter dem der Server sie fuehrt (`1`, `2`, …), `sourcePartition`
 * der Name, den sie tragen soll. Beim Reverse ist das gelesene Schema die
 * Zielseite, also wird `targetPartition` gesucht und durch `sourcePartition`
 * ersetzt.
 *
 * **`R346` verstummt nur fuer die Tabellen, die das Overlay vollstaendig
 * benennt.** Ein halb benanntes Schema hat weiterhin geratene Namen, und die
 * Meldung ist dann richtig.
 */
object PartitionNameOverlayApplier {

    data class Applied(val schema: SchemaDefinition, val notes: List<SchemaReadNote>)

    fun apply(
        schema: SchemaDefinition,
        notes: List<SchemaReadNote>,
        documents: List<MigrationOverlayDocument>,
    ): Applied {
        val byTable = documents
            .flatMap { it.overlay.entries }
            .filterIsInstance<PartitionMappingOverlayEntry>()
            .filter { it.targetPartition != null }
            .groupBy { it.table }
        if (byTable.isEmpty()) return Applied(schema, notes)

        val fullyNamed = mutableSetOf<String>()
        val tables = schema.tables.mapValues { (tableName, table) ->
            val mapping = byTable[tableName]?.associate { it.targetPartition!! to it.sourcePartition }
                ?: return@mapValues table
            val partitioning = table.partitioning ?: return@mapValues table
            val renamed = partitioning.partitions.map { part ->
                mapping[part.name]?.let { part.copy(name = it) } ?: part
            }
            if (partitioning.partitions.all { it.name in mapping }) fullyNamed += tableName
            table.copy(partitioning = partitioning.copy(partitions = renamed))
        }

        return Applied(
            schema = schema.copy(tables = tables),
            // Fuer eine vollstaendig benannte Tabelle ist die Aussage „die
            // Namen sind nicht gespeichert" nicht mehr die ganze Wahrheit —
            // sie stehen jetzt im Ergebnis.
            notes = notes.filterNot { it.code == "R346" && it.objectName in fullyNamed },
        )
    }
}
