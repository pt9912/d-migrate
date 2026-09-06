package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.PartitionLiteralGuard
import dev.dmigrate.driver.TransformationNote

/**
 * Die `PARTITION BY`-Klausel fuer Oracle (ADR 0052, Slice 7). Einzige Quelle
 * fuer Generate- **und** Diff-Pfad, damit eine Tabelle auf beiden Wegen
 * gleich partitioniert entsteht (`spec/ddl-generation-rules.md`, Abschnitt 9).
 *
 * Oracle steht dem neutralen Modell naeher als MySQL: es traegt mehrspaltige
 * RANGE-Schluessel und kennt in LIST eine echte `DEFAULT`-Partition, die
 * MySQL verwerfen muss. Zwei Unterschiede bleiben:
 *
 * - **RANGE hat nur eine obere Grenze** (`VALUES LESS THAN`), wie MySQL. Die
 *   untere Grenze des Modells ergibt sich in Oracle implizit aus der
 *   vorhergehenden Partition und wird beim Rendern verworfen (`W112`).
 * - **HASH kennt kein Modulus/Remainder.** Oracle verteilt selbst; gefuehrt
 *   wird nur die Anzahl. Namen bleiben erhalten, die Platzierung einer
 *   einzelnen Zeile aendert sich (`W130`).
 */
internal class OraclePartitionDdlBuilder(
    private val quoteIdentifier: (String) -> String,
) {

    private val boundRenderer = OraclePartitionBoundRenderer()
    private val typeMapper = OracleTypeMapper()

    /**
     * Die Klausel, oder `""` wenn die Partitionierung fuer Oracle gar nicht
     * gerendert werden darf. In dem Fall traegt [notes] den Grund; der
     * Aufrufer legt die Tabelle dann flach an (Generate) oder blockt (Diff).
     */
    fun clause(
        tableName: String,
        partitioning: PartitionConfig,
        columns: Map<String, ColumnDefinition>,
        notes: MutableList<TransformationNote>,
    ): String {
        skipNote(tableName, partitioning, columns)?.let {
            notes += it
            return ""
        }
        notes += diagnostics(tableName, partitioning)
        val keyTypes = partitioning.key.map { columns[it]?.type }
        val emittedCodes = mutableSetOf<String>()
        val key = partitioning.key.joinToString(", ") { quoteIdentifier(it) }
        val body = partitioning.partitions.joinToString(",\n") {
            "    ${renderPartition(it, partitioning.type, keyTypes, notes, emittedCodes)}"
        }
        return buildString {
            append("PARTITION BY ${partitioning.type.name} ($key) (\n")
            append(body)
            append("\n)")
        }
    }

    /**
     * Warum diese Partitionierung fuer Oracle nicht renderbar ist — oder
     * `null`. Eigene Methode, weil der Diff-Pfad dieselbe Frage stellt, bevor
     * er eine Tabelle anlegt, und sonst DDL erzeugte, die der Generate-Pfad
     * verweigert.
     */
    fun skipNote(
        tableName: String,
        partitioning: PartitionConfig,
        columns: Map<String, ColumnDefinition>,
    ): TransformationNote? {
        unkeyableKey(tableName, partitioning, columns)?.let { return it }
        if (partitioning.partitions.isEmpty()) {
            return ManualActionRequired(
                code = "E055", objectType = "partitioning", objectName = tableName,
                reason = "${partitioning.type.name} partitioning of table '$tableName' has no partitions; " +
                    "Oracle requires at least one, so the table was created unpartitioned.",
                hint = "Add explicit partitions, or remove the partitioning configuration.",
            ).toNote()
        }
        if (partitioning.type == PartitionType.LIST && partitioning.key.size > 1) {
            // MySQLs `LIST COLUMNS(a, b)` ist im neutralen Modell
            // darstellbar; Oracle-LIST partitioniert ueber genau eine Spalte.
            return ManualActionRequired(
                code = "E055", objectType = "partitioning", objectName = tableName,
                reason = "LIST partitioning of table '$tableName' uses ${partitioning.key.size} key columns; " +
                    "Oracle partitions a LIST scheme by exactly one column, so the table was created " +
                    "unpartitioned.",
                hint = "Partition on a single column, or use RANGE, which Oracle supports multi-column.",
            ).toNote()
        }
        if (partitioning.type == PartitionType.RANGE) {
            rangeSkipNote(tableName, partitioning)?.let { return it }
        }
        return null
    }

    /**
     * Live gemessen (2026-09-06): Oracle lehnt zwei Schluesseltypen ab —
     * `TIMESTAMP WITH TIME ZONE` mit `ORA-03001` (unimplemented feature) und
     * `CLOB`/`BLOB` mit `ORA-14135`. `TIMESTAMP WITH LOCAL TIME ZONE` ist
     * dagegen zulaessig, spielt hier aber keine Rolle: das neutrale Modell
     * unterscheidet die beiden Zeitzonen-Formen nicht.
     *
     * Genau diese Menge beschreibt bereits [OracleTypeMapper.isUnkeyable] —
     * eingefuehrt in Slice 3b fuer Index- und Schluesselspalten, aus
     * demselben Grund und mit demselben Inhalt.
     */
    private fun unkeyableKey(
        tableName: String,
        partitioning: PartitionConfig,
        columns: Map<String, ColumnDefinition>,
    ): TransformationNote? {
        val offending = partitioning.key.firstOrNull { name ->
            columns[name]?.type?.let { typeMapper.isUnkeyable(it) } == true
        } ?: return null
        return ManualActionRequired(
            code = "E062", objectType = "partitioning", objectName = tableName,
            reason = "Partition key column '$offending' of table '$tableName' has a type Oracle does not " +
                "allow as a partitioning column (large objects: ORA-14135; TIMESTAMP WITH TIME ZONE: " +
                "ORA-03001); the table was created unpartitioned.",
            hint = "Partition on a different column, or store the value in a type Oracle can partition on.",
        ).toNote()
    }

    private fun rangeSkipNote(tableName: String, partitioning: PartitionConfig): TransformationNote? {
        val unbounded = partitioning.partitions.firstOrNull { it.to.isNullOrEmpty() && !it.isDefault }
        if (unbounded != null) {
            return ManualActionRequired(
                code = "E055", objectType = "partitioning", objectName = tableName,
                reason = "RANGE partition '${unbounded.name}' of table '$tableName' carries no upper " +
                    "bound; Oracle expresses a range only as VALUES LESS THAN, so the table was " +
                    "created unpartitioned.",
                hint = "Give every RANGE partition an upper bound (`to`), or use MAXVALUE for the last one.",
            ).toNote()
        }
        // MINVALUE als OBERE Grenze ist in Oracle nicht ausdrueckbar. Sie
        // stillschweigend als MAXVALUE zu rendern kehrte die Bedeutung der
        // Partition um -- aus „nichts faellt hinein" wuerde „alles".
        val inverted = partitioning.partitions.firstOrNull { p ->
            p.to.orEmpty().any { it is PartitionBound.MinValue }
        }
        if (inverted != null) {
            return ManualActionRequired(
                code = "E055", objectType = "partitioning", objectName = tableName,
                reason = "RANGE partition '${inverted.name}' of table '$tableName' has MINVALUE as its " +
                    "upper bound, which Oracle cannot express; the table was created unpartitioned.",
                hint = "An upper bound of MINVALUE selects no rows — remove the partition or give it a real bound.",
            ).toNote()
        }
        return null
    }

    private fun diagnostics(tableName: String, partitioning: PartitionConfig): List<TransformationNote> =
        when (partitioning.type) {
            PartitionType.RANGE -> listOfNotNull(lowerBoundDropNote(tableName, partitioning))
            PartitionType.HASH -> listOf(hashPlacementNote(tableName))
            // LIST bildet Oracle vollstaendig ab, DEFAULT-Partition eingeschlossen.
            PartitionType.LIST -> emptyList()
        }

    /**
     * Nur wenn wirklich eine untere Grenze im Modell steht. Anders als MySQL,
     * das die Notiz unbedingt setzt: eine aus einem Oracle-Reverse stammende
     * Konfiguration traegt gar keine `from`-Grenzen, und dann gibt es nichts
     * zu melden — sonst warnte jeder Oracle-Round-Trip vor einem Verlust, der
     * nicht stattfindet.
     */
    private fun lowerBoundDropNote(tableName: String, partitioning: PartitionConfig): TransformationNote? {
        if (partitioning.partitions.none { !it.from.isNullOrEmpty() }) return null
        return TransformationNote(
            type = NoteType.WARNING, code = "W112", objectName = tableName,
            message = "RANGE partitioning of table '$tableName' carries lower bounds; Oracle keeps only the " +
                "upper bound (VALUES LESS THAN), so they were dropped.",
            hint = "Verify the partitions are contiguous and ordered ascending — Oracle derives the lower " +
                "bound from the preceding partition.",
        )
    }

    private fun hashPlacementNote(tableName: String): TransformationNote = TransformationNote(
        type = NoteType.WARNING, code = "W130", objectName = tableName,
        message = "HASH partitioning of table '$tableName' keeps partition count and names, but Oracle " +
            "distributes rows with its own hash function; a given row may land in a different partition.",
        hint = "No data loss — every row still has a target partition; only the placement differs.",
    )

    private fun renderPartition(
        partition: PartitionDefinition,
        type: PartitionType,
        keyTypes: List<NeutralType?>,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String {
        val name = quoteIdentifier(partition.name)
        return when (type) {
            // Oracle verteilt HASH selbst; die Partition traegt nur ihren Namen.
            PartitionType.HASH -> "PARTITION $name"
            // Eine DEFAULT-Partition in einem RANGE-Schema ist der Catch-all
            // und damit `MAXVALUE` -- MySQL loest das identisch. Ohne diesen
            // Zweig entstuende `VALUES LESS THAN ()` (ORA-14019): der
            // Generate-Pfad schriebe es hin, der Diff-Pfad fuehrte es aus.
            PartitionType.RANGE ->
                if (partition.isDefault) {
                    "PARTITION $name VALUES LESS THAN (MAXVALUE)"
                } else {
                    "PARTITION $name VALUES LESS THAN " +
                        "(${bounds(partition, keyTypes, notes, emittedCodes)})"
                }
            PartitionType.LIST ->
                if (partition.isDefault) {
                    "PARTITION $name VALUES (DEFAULT)"
                } else {
                    "PARTITION $name VALUES (${listValues(partition, keyTypes, notes, emittedCodes)})"
                }
        }
    }

    /**
     * [skipNote] hat MINVALUE als obere Grenze bereits ausgeschlossen. Der
     * Zweig hier bleibt deshalb ein Programmierfehler, kein Rueckfall — als
     * `MAXVALUE` zu rendern kehrte die Bedeutung der Partition um.
     */
    private fun bounds(
        partition: PartitionDefinition,
        keyTypes: List<NeutralType?>,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String = partition.to.orEmpty().mapIndexed { index, bound ->
        when (bound) {
            is PartitionBound.MaxValue -> "MAXVALUE"
            is PartitionBound.MinValue -> error("MINVALUE upper bound reached the renderer; skipNote must refuse it")
            is PartitionBound.Value -> literal(bound.literal, keyTypes.getOrNull(index), partition, notes, emittedCodes)
        }
    }.joinToString(", ")

    private fun listValues(
        partition: PartitionDefinition,
        keyTypes: List<NeutralType?>,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String = partition.values.orEmpty().joinToString(", ") {
        // LIST partitioniert in Oracle ueber genau eine Spalte; alle Werte
        // teilen deshalb denselben Schluesseltyp.
        literal(it, keyTypes.firstOrNull(), partition, notes, emittedCodes)
    }

    private fun literal(
        raw: String,
        keyType: NeutralType?,
        partition: PartitionDefinition,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String = boundRenderer.render(
        PartitionLiteralGuard.ensureSafe(raw, partition.name),
        keyType,
        partition.name,
        notes,
        emittedCodes,
    )
}
