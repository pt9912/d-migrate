package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * Die Partitionierungs-Seite der Tabellen-Erzeugung fuer SQL Server.
 *
 * Aus `MssqlDdlGenerator` herausgeloest, weil sie ein eigener Gegenstand ist:
 * der Generator baut Spalten und Schluessel INNERHALB der Tabelle, das hier
 * baut die zwei Objekte DANEBEN — Partition Function und Scheme — und die
 * Klausel, die die Tabelle an sie haengt.
 */
internal class MssqlTablePartitioning(
    private val columnHelper: MssqlColumnConstraintHelper,
    private val quoteIdentifier: (String) -> String,
) {

    /**
     * Die beiden Partitionierungs-Wege von SQL Server: der emulierte HASH-Fall
     * ueber die berechnete Spalte und der native RANGE-Fall. Eigene Methode,
     * weil sie ein eigener Gedanke ist — `generateTable` baut Spalten und
     * Schluessel, das hier baut die zwei Objekte daneben.
     */
    fun render(
        name: String,
        effective: TableDefinition,
        schema: SchemaDefinition,
        hashPlan: MssqlHashPartitionPlan?,
        /** Ob die HASH-Emulation abgelehnt wurde; dann ist der Grund schon gemeldet. */
        hashRefused: Boolean,
        options: DdlGenerationOptions,
        notes: MutableList<TransformationNote>,
    ): RenderedPartitioning {
        val partitionPrelude = mutableListOf<String>()
        var onClause = ""
        // Der emulierte HASH-Fall haengt an der berechneten Spalte, nicht am
        // Modell-Schluessel: die Grenzen sind die Eimergrenzen, und der Typ
        // steht fest (CHECKSUM liefert int).
        if (hashPlan != null) {
            partitionPrelude += MssqlPartitionDdl.createStatementsForBoundaries(
                table = name,
                boundaries = hashPlan.boundaries,
                columnType = MssqlHashPartitionEmulation.BUCKET_TYPE,
                storage = options.partitionStorage,
                quote = quoteIdentifier,
            )
            onClause = " ON ${quoteIdentifier(MssqlPartitionDdl.schemeName(name))} " +
                "(${quoteIdentifier(MssqlHashPartitionEmulation.BUCKET_COLUMN)})"
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W145", objectName = name,
                message = "HASH partitioning of table '$name' was emulated with a persisted computed " +
                    "column over ${hashPlan.modulus} buckets. SQL Server hashes differently than the " +
                    "source dialect, so a row lands in a different bucket than it did there; the bucket " +
                    "column also joins every unique key, and a reverse reads the table back as RANGE.",
                hint = "Functionally equivalent for storage separation and partition elimination; do not " +
                    "rely on a specific row-to-partition assignment.",
            )
        }
        // Ein abgelehnter HASH-Fall hat seinen Grund schon als E067/E068/E069
        // gemeldet. Ohne diesen Ausschluss kaeme hier ein zweites
        // ACTION_REQUIRED dazu — mit der Begruendung „RANGE ueber eine Spalte",
        // die auf den abgelehnten Fall gar nicht zutrifft.
        effective.partitioning?.takeIf { hashPlan == null && !hashRefused }?.let { partitioning ->
            if (MssqlPartitionDdl.isRenderable(partitioning)) {
                val keyColumn = partitioning.key.first()
                val keyType = effective.columns[keyColumn]
                    ?.let { columnHelper.renderColumn(name, keyColumn, it, effective, schema, notes).sqlType }
                if (keyType == null) {
                    notes += unrenderablePartitioning(name, partitioning.type.name, "its key column has no SQL type")
                } else {
                    partitionPrelude += MssqlPartitionDdl.createStatements(
                        table = name,
                        config = partitioning,
                        columnType = keyType,
                        storage = options.partitionStorage,
                        quote = quoteIdentifier,
                    )
                    onClause = MssqlPartitionDdl.onClause(name, partitioning, quoteIdentifier)
                    // Function und Scheme sind in SQL Server datenbankweit und
                    // teilbar; das neutrale Modell traegt die Partitionierung je
                    // Tabelle. Aus dieser Richtung ist die Teilung nicht
                    // rekonstruierbar — je Tabelle ein eigenes Paar.
                    notes += TransformationNote(
                        type = NoteType.WARNING, code = "W144", objectName = name,
                        message = "Partition function '${MssqlPartitionDdl.functionName(name)}' and scheme " +
                            "'${MssqlPartitionDdl.schemeName(name)}' were created for table '$name' alone. " +
                            "SQL Server shares these objects across tables; the neutral model carries " +
                            "partitioning per table, so a shared original becomes one pair per table.",
                        hint = "Functionally equivalent; consolidate the schemes manually if the sharing matters.",
                    )
                }
            } else {
                notes += unrenderablePartitioning(
                    name, partitioning.type.name,
                    "SQL Server partitions by RANGE over a single column only",
                )
            }
        }
        return RenderedPartitioning(partitionPrelude, onClause)
    }

    /** Die zwei Vorlauf-Statements und die Klausel, die die Tabelle anhaengt. */
    data class RenderedPartitioning(val prelude: List<String>, val onClause: String)

    /** Warum eine Partitionierung nicht gerendert wird — Form wie bisher, Grund verschieden. */
    private fun unrenderablePartitioning(table: String, strategy: String, why: String): TransformationNote =
        ManualActionRequired(
            code = "E055", objectType = "partitioning", objectName = table,
            reason = "$strategy partitioning of table '$table' is not rendered for SQL Server: $why; " +
                "created as a plain table.",
            hint = "Create the partition function and scheme manually and rebuild the table on the scheme.",
        ).toNote()
}
