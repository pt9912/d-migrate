package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.PartitionLiteralGuard
import dev.dmigrate.driver.TransformationNote

internal class MysqlIndexPartitionDdlHelper(
    private val quoteIdentifier: (String) -> String,
) {

    fun generatePartitionClause(
        partitioning: PartitionConfig,
        columns: Map<String, ColumnDefinition>,
        notes: MutableList<TransformationNote>,
    ): String {
        val keyTypes = partitioning.key.map { columns[it]?.type }
        skipNote(partitioning, keyTypes)?.let { notes += it; return "" }
        notes += partitionDiagnostics(partitioning)
        val emitPartitions = effectivePartitions(partitioning, notes)
        // LIST that filtered down to nothing (only DEFAULT existed) → nothing valid to emit.
        if (emitPartitions.isEmpty() && partitioning.type != PartitionType.HASH) return ""

        // §1/§4: RANGE/LIST → `… COLUMNS(key)` so date/datetime/string keys take literal bounds directly.
        val mysqlMethod = when (partitioning.type) {
            PartitionType.RANGE -> "RANGE COLUMNS"
            PartitionType.LIST -> "LIST COLUMNS"
            PartitionType.HASH -> "HASH"
        }
        val key = partitioning.key.joinToString(", ") { quoteIdentifier(it) }
        val emittedCodes = mutableSetOf<String>()
        return buildString {
            append("PARTITION BY $mysqlMethod ($key)")
            if (emitPartitions.isNotEmpty()) {
                append(" (\n")
                append(emitPartitions.joinToString(",\n") {
                    renderPartition(it, partitioning.type, keyTypes, notes, emittedCodes)
                })
                append("\n)")
            }
        }
    }

    /**
     * Wenn die Partitionierung **ganz** verworfen werden muss, die zugehörige
     * `action_required`-Note (sonst null): leere RANGE/LIST (E055; MySQL lehnt
     * bare `PARTITION BY` ab) oder nicht abbildbarer Schlüsseltyp (E062, ADR 0020 §1/§3 —
     * RANGE/LIST COLUMNS brauchen INT/DATE/DATETIME/CHAR, HASH einen Integer).
     */
    private fun skipNote(partitioning: PartitionConfig, keyTypes: List<NeutralType?>): TransformationNote? {
        val objectName = partitioning.key.joinToString(",")
        if (partitioning.partitions.isEmpty() &&
            (partitioning.type == PartitionType.RANGE || partitioning.type == PartitionType.LIST)
        ) {
            return TransformationNote(
                type = NoteType.ACTION_REQUIRED, code = "E055", objectName = objectName,
                message = "${partitioning.type.name} partitioning requires at least one partition, " +
                    "but the definition is empty; partitioning was skipped for this table.",
                hint = "Add explicit partition boundaries or remove the partitioning configuration.",
            )
        }
        val unsupported = when (partitioning.type) {
            PartitionType.RANGE, PartitionType.LIST -> keyTypes.any { it is NeutralType.Decimal || it is NeutralType.Float }
            PartitionType.HASH -> keyTypes.any { !isIntegerKey(it) }
        }
        if (unsupported) {
            return TransformationNote(
                type = NoteType.ACTION_REQUIRED, code = "E062", objectName = objectName,
                message = "${partitioning.type.name} partition key type is not supported by MySQL " +
                    "(RANGE/LIST COLUMNS need INT/DATE/DATETIME/CHAR; HASH needs an integer key); " +
                    "partitioning was skipped for this table.",
                hint = "Repartition on a supported key type, or remove the partitioning configuration.",
            )
        }
        return null
    }

    /** Diagnostische Notes für eine emittierte Partitionierung (ADR 0020): W112 (`from`-Verwurf), W130 (HASH-Platzierung). */
    private fun partitionDiagnostics(partitioning: PartitionConfig): List<TransformationNote> {
        val objectName = partitioning.key.joinToString(",")
        return when (partitioning.type) {
            PartitionType.RANGE -> listOf(TransformationNote(
                type = NoteType.WARNING, code = "W112", objectName = objectName,
                message = "PostgreSQL RANGE has lower+upper bounds; MySQL RANGE COLUMNS keeps only the " +
                    "upper bound (VALUES LESS THAN), so the partition's `from` bound is dropped.",
                hint = "Verify the partitions are contiguous (MySQL RANGE assumes no gaps).",
            ))
            PartitionType.HASH -> listOf(TransformationNote(
                type = NoteType.WARNING, code = "W130", objectName = objectName,
                message = "PostgreSQL and MySQL hash functions differ; partition count and names are " +
                    "preserved, but rows may land in different partitions after import.",
                hint = "No data loss (parent routing); placement differs from the source.",
            ))
            PartitionType.LIST -> emptyList()
        }
    }

    /**
     * §4 (ADR 0020): MySQL-LIST hat keinen `DEFAULT`-Catch-all. Eine DEFAULT-Partition wird
     * verworfen — **Transfer-Datenverlust** (E063), da ihre Zeilen in MySQL keine Ziel-Partition haben.
     */
    private fun effectivePartitions(
        partitioning: PartitionConfig,
        notes: MutableList<TransformationNote>,
    ): List<PartitionDefinition> {
        if (partitioning.type == PartitionType.LIST && partitioning.partitions.any { it.isDefault }) {
            notes += TransformationNote(
                type = NoteType.ACTION_REQUIRED, code = "E063", objectName = partitioning.key.joinToString(","),
                message = "LIST DEFAULT partition has no MySQL equivalent and was dropped; rows that fell " +
                    "into it have no target partition in MySQL and would be rejected on transfer (data loss).",
                hint = "Replace the DEFAULT partition with explicit LIST values, or migrate those rows separately.",
            )
            return partitioning.partitions.filter { !it.isDefault }
        }
        return partitioning.partitions
    }

    private fun renderPartition(
        partition: PartitionDefinition,
        type: PartitionType,
        keyTypes: List<NeutralType?>,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String = buildString {
        append("    PARTITION ${quoteIdentifier(partition.name)}")
        when (type) {
            PartitionType.RANGE ->
                append(" VALUES LESS THAN (${renderMysqlUpperBound(partition, keyTypes, notes, emittedCodes)})")
            PartitionType.LIST -> {
                val temporal = keyTypes.firstOrNull() is NeutralType.DateTime
                val values = partition.values?.joinToString(", ") {
                    val safe = PartitionLiteralGuard.ensureSafe(it, partition.name)
                    if (temporal) normalizeTemporalBound(safe, partition.name, notes, emittedCodes) else safe
                } ?: ""
                append(" VALUES IN ($values)")
            }
            PartitionType.HASH -> Unit
        }
    }

    /** Integer-coercible key (MySQL HASH/KEY require it). */
    private fun isIntegerKey(type: NeutralType?): Boolean = when (type) {
        is NeutralType.Identifier, NeutralType.Integer, NeutralType.SmallInt,
        NeutralType.BigInteger, NeutralType.BooleanType -> true
        else -> false
    }

    /** MySQL-RANGE-Obergrenze aus dem strukturierten `to`-Bound-Tupel (ADR 0019/0020).
     *  DEFAULT-/leere Grenze → `MAXVALUE`; Temporal-Grenzen werden auf UTC normalisiert. */
    private fun renderMysqlUpperBound(
        partition: PartitionDefinition,
        keyTypes: List<NeutralType?>,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String {
        if (partition.isDefault) return "MAXVALUE"
        return partition.to.orEmpty().mapIndexed { i, bound ->
            when (bound) {
                PartitionBound.MaxValue -> "MAXVALUE"
                PartitionBound.MinValue -> "MINVALUE"
                is PartitionBound.Value -> {
                    val safe = PartitionLiteralGuard.ensureSafe(bound.literal, partition.name)
                    if (keyTypes.getOrNull(i) is NeutralType.DateTime)
                        normalizeTemporalBound(safe, partition.name, notes, emittedCodes)
                    else safe
                }
            }
        }.joinToString(", ").ifEmpty { "MAXVALUE" }
    }

    /**
     * §2 (ADR 0020): PG-timestamptz-Grenze (`'…+00'`) → MySQL-DATETIME-Literal. Ein
     * **UTC**-Offset wird entfernt (instant-erhaltend) → W129. Ein **Nicht-UTC**-Offset würde
     * die Grenze beim bloßen Strippen verschieben → `action_required` (E061), kein stiller Shift.
     */
    private fun normalizeTemporalBound(
        literal: String,
        partitionName: String,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String {
        val match = TZ_OFFSET.find(literal) ?: return literal
        val offset = match.groupValues[1]
        if (offset !in UTC_OFFSETS && emittedCodes.add("E061")) {
            notes += TransformationNote(
                type = NoteType.ACTION_REQUIRED,
                code = "E061",
                objectName = partitionName,
                message = "Partition '$partitionName' bound carries a non-UTC timezone offset '$offset'; " +
                    "stripping it for MySQL DATETIME would shift the boundary.",
                hint = "Re-read the source with the session time zone set to UTC, or convert the bound to UTC.",
            )
        } else if (offset in UTC_OFFSETS && emittedCodes.add("W129")) {
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W129",
                objectName = partitionName,
                message = "PostgreSQL timestamptz partition bounds normalized to UTC (timezone suffix " +
                    "removed) for MySQL DATETIME, which has no time zone.",
                hint = "Ensure the source data is stored/interpreted as UTC.",
            )
        }
        return literal.take(match.range.first) + "'"
    }

    private companion object {
        /** Trailing tz offset (`+00`, `-05:00`, …) right before the closing quote of a literal. */
        val TZ_OFFSET = Regex("([+-]\\d{2}(?::?\\d{2})?)'\\s*$")
        val UTC_OFFSETS = setOf("+00", "+0000", "+00:00", "-00", "-0000", "-00:00")
    }

    fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> {
        val lift = liftPartitionIndices(tableName, table)
        val allIndices = table.indices + lift.indices
        val statements = generatedIndexNames(tableName, allIndices).mapIndexedNotNull { position, indexName ->
            generateIndex(tableName, allIndices[position], indexName, table.columns)
        }
        // Lift notes (E064 unique-skip, W131 rename, INFO lifted) carry on a note-only statement.
        return if (lift.notes.isEmpty()) statements else statements + DdlStatement("", lift.notes)
    }

    private class LiftedIndices(
        val indices: List<IndexDefinition>,
        val notes: List<TransformationNote>,
    )

    /**
     * §5 (ADR 0020): MySQL teilt Indizes über **alle** Partitionen — PG-kind-lokale Indizes
     * (`PartitionDefinition.indices`) müssen auf die Tabelle **gehoben** werden statt still verworfen:
     * - **Nicht-unique** → heben, dedupliziert nach Spalten/Typ (N Partitionen tragen denselben Index →
     *   genau einer); Namenskollision mit einem Tabellen-Index → eindeutiger Name + W131; INFO je gehobenem Index.
     * - **UNIQUE/PK-artig** → NICHT heben, skip + `action_required` (E064): eine gehobene UNIQUE ohne alle
     *   Partitionsschlüssel-Spalten ergäbe invalides MySQL-DDL, und partition-lokale ≠ globale Eindeutigkeit.
     */
    private fun liftPartitionIndices(tableName: String, table: TableDefinition): LiftedIndices {
        val childLocal = table.partitioning?.partitions?.flatMap { it.indices }.orEmpty()
        if (childLocal.isEmpty()) return LiftedIndices(emptyList(), emptyList())
        val notes = mutableListOf<TransformationNote>()
        val lifted = mutableListOf<IndexDefinition>()
        val seenSignatures = table.indices.mapTo(mutableSetOf()) { indexSignature(it) }
        val usedNames = table.indices.mapNotNullTo(mutableSetOf()) { it.name }
        for (index in childLocal) {
            if (index.unique) {
                notes += uniqueSkipNote(index, tableName)
                continue
            }
            if (!seenSignatures.add(indexSignature(index))) continue
            val renamed = uniqueLiftName(index, usedNames, tableName, notes)
            renamed.name?.let { usedNames += it }
            lifted += renamed
            notes += liftedInfoNote(renamed, tableName)
        }
        return LiftedIndices(lifted, notes)
    }

    /** Identität für die Lift-Deduplikation: Spalten (Name/Prefix/Richtung) + Typ + Unique, **ohne** Name. */
    private fun indexSignature(index: IndexDefinition): String =
        "${index.type}|${index.unique}|" + index.columns.joinToString(",") { it.toString() }

    private fun generatedIndexName(index: IndexDefinition, tableName: String): String =
        index.name ?: "idx_${tableName}_${index.columnNames.joinToString("_")}"

    /** Kollidiert der (explizite) Name eines gehobenen Index, eindeutig suffigieren + W131. Generierte Namen: weiter unten disambiguiert. */
    private fun uniqueLiftName(
        index: IndexDefinition,
        used: Set<String>,
        tableName: String,
        notes: MutableList<TransformationNote>,
    ): IndexDefinition {
        val name = index.name ?: return index
        if (name !in used) return index
        var suffix = 2
        while ("${name}_$suffix" in used) suffix++
        val renamed = "${name}_$suffix"
        notes += TransformationNote(
            type = NoteType.WARNING, code = "W131", objectName = renamed,
            message = "Lifted partition-local index '$name' was renamed to '$renamed' because that name " +
                "already exists on table '$tableName' (MySQL shares indexes across all partitions).",
            hint = "Verify the renamed index is acceptable, or rename the source index.",
        )
        return index.copy(name = renamed)
    }

    private fun uniqueSkipNote(index: IndexDefinition, tableName: String): TransformationNote =
        TransformationNote(
            type = NoteType.ACTION_REQUIRED, code = "E064", objectName = generatedIndexName(index, tableName),
            message = "UNIQUE partition-local index '${generatedIndexName(index, tableName)}' cannot be lifted " +
                "to MySQL: a unique key on a partitioned table must include every partition-key column, and " +
                "partition-local uniqueness is not global uniqueness; lifting it would emit invalid DDL or " +
                "change semantics. The index was skipped.",
            hint = "Add the partition-key columns to the unique index, or enforce uniqueness in the application.",
        )

    private fun liftedInfoNote(index: IndexDefinition, tableName: String): TransformationNote =
        TransformationNote(
            type = NoteType.INFO, code = "PARTITION_INDEX_LIFTED", objectName = generatedIndexName(index, tableName),
            message = "Partition-local index '${generatedIndexName(index, tableName)}' was lifted to a " +
                "table-level index because MySQL shares indexes across all partitions (no per-partition indexes).",
            hint = "The index now covers all partitions; it is a non-unique performance index, so coverage only widens.",
        )

    private fun generateIndex(
        tableName: String,
        index: IndexDefinition,
        indexName: String,
        columns: Map<String, ColumnDefinition>,
    ): DdlStatement? {
        if (index.where != null) {
            return DdlStatement(
                "",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E057",
                        objectName = indexName,
                        message = "Partial index '$indexName' is not supported in MySQL and was skipped.",
                        hint = "Create an equivalent generated-column index manually or remove the index predicate.",
                    )
                )
            )
        }

        // VA3: ein Index auf einer Geometriespalte → MySQL `SPATIAL INDEX`, egal mit
        // welcher neutralen Zugriffsmethode er hereinkommt (GIST/SP-GiST/BRIN/SPATIAL;
        // MySQL kennt nur SPATIAL). Vor dem Prefix-/when-Pfad, da Geometrie keine
        // Prefix-Länge trägt.
        if (index.columnNames.any { columns[it]?.type is NeutralType.Geometry }) {
            return spatialIndexStatement(tableName, index, indexName)
        }

        // I-08: an unbounded TEXT/BLOB column needs a prefix length in MySQL
        // (ERROR 1170). When none is carried, the index cannot be rendered as
        // valid DDL — skip it with a note rather than guess a length. Only the
        // emitted BTREE/HASH types are affected; GIN/GIST/BRIN/SP-GiST are skipped below.
        val emitsBtree = index.type == IndexType.BTREE || index.type == IndexType.HASH
        val missingPrefix = if (emitsBtree) MysqlIndexPrefix.columnNeedingPrefix(index) { columns[it]?.type } else null
        missingPrefix?.let { offending ->
            return DdlStatement(
                "",
                listOf(
                    TransformationNote(
                        type = NoteType.WARNING,
                        code = "W125",
                        objectName = indexName,
                        message = "Index '$indexName' on TEXT/BLOB column '$offending' was skipped: " +
                            "MySQL requires a prefix length (e.g. `$offending(255)`) which is not present.",
                        hint = "Add a prefix length to the index column, or index a bounded VARCHAR/CHAR column.",
                    )
                )
            )
        }

        val columnsSql = index.columns.joinToString(", ") { renderIndexColumn(it) }

        return when (index.type) {
            // VA3: räumlicher Index → natives `CREATE SPATIAL INDEX` (Spalten ohne
            // Prefix/Richtung; MySQL erlaubt SPATIAL nur auf NOT-NULL-Geometrie).
            IndexType.SPATIAL -> {
                val spatialCols = index.columns.joinToString(", ") { quoteIdentifier(it.name) }
                DdlStatement(
                    "CREATE SPATIAL INDEX ${quoteIdentifier(indexName)} " +
                        "ON ${quoteIdentifier(tableName)} ($spatialCols);",
                )
            }
            IndexType.GIN, IndexType.GIST, IndexType.BRIN, IndexType.SPGIST -> {
                // Erreicht nur Nicht-Geometrie-Spalten; Geometrie ist oben als
                // SPATIAL abgefangen. Diese PG-Zugriffsmethoden kennt MySQL nicht.
                DdlStatement(
                    "",
                    listOf(
                        TransformationNote(
                            type = NoteType.WARNING,
                            code = "W102",
                            objectName = indexName,
                            message = "${index.type.name} index '$indexName' is not supported in MySQL and was skipped.",
                            hint = "Consider using a BTREE index or FULLTEXT index instead.",
                        )
                    )
                )
            }
            IndexType.HASH -> {
                val sql = buildString {
                    append("CREATE ")
                    if (index.unique) append("UNIQUE ")
                    append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)}")
                    append(" USING BTREE")
                    append(" ($columnsSql);")
                }
                DdlStatement(
                    sql,
                    listOf(
                        TransformationNote(
                            type = NoteType.WARNING,
                            code = "W102",
                            objectName = indexName,
                            message = "HASH index '$indexName' is not supported on InnoDB; converted to BTREE.",
                            hint = "InnoDB only supports BTREE indexes. The HASH index has been automatically converted.",
                        )
                    )
                )
            }
            IndexType.BTREE -> {
                val sql = buildString {
                    append("CREATE ")
                    if (index.unique) append("UNIQUE ")
                    append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)}")
                    append(" ($columnsSql);")
                }
                DdlStatement(sql)
            }
        }
    }

    /**
     * VA3: natives MySQL `CREATE SPATIAL INDEX` für einen Index auf einer
     * Geometriespalte (Spalten ohne Prefix/Richtung). MySQL verlangt dafür eine
     * NOT-NULL-Geometriespalte (als INFO-Note vermerkt).
     */
    private fun spatialIndexStatement(
        tableName: String,
        index: IndexDefinition,
        indexName: String,
    ): DdlStatement {
        val spatialCols = index.columns.joinToString(", ") { quoteIdentifier(it.name) }
        return DdlStatement(
            "CREATE SPATIAL INDEX ${quoteIdentifier(indexName)} " +
                "ON ${quoteIdentifier(tableName)} ($spatialCols);",
            listOf(
                TransformationNote(
                    type = NoteType.INFO,
                    code = "SPATIAL_INDEX_REQUIRES_NOT_NULL",
                    objectName = indexName,
                    message = "Index '$indexName' on a geometry column emitted as MySQL SPATIAL INDEX.",
                    hint = "MySQL requires the geometry column to be NOT NULL for a SPATIAL INDEX.",
                )
            ),
        )
    }

    private fun renderIndexColumn(column: IndexColumn): String =
        buildString {
            append(quoteIdentifier(column.name))
            val prefixLength = column.prefixLength
            if (prefixLength != null) append("($prefixLength)")
            val direction = column.direction
            if (direction != null) append(" ${direction.name}")
        }


    private fun generatedIndexNames(tableName: String, indices: List<IndexDefinition>): List<String> {
        val baseNames = indices.map { index ->
            index.name ?: "idx_${tableName}_${index.columnNames.joinToString("_")}"
        }
        val baseCounts = baseNames.groupingBy { it }.eachCount()
        val used = indices.mapNotNull { it.name }.groupingBy { it }.eachCount().toMutableMap()
        return indices.mapIndexed { position, index ->
            index.name ?: disambiguateGeneratedIndexName(baseNames[position], index, baseCounts.getValue(baseNames[position]), used)
        }
    }

    private fun disambiguateGeneratedIndexName(
        baseName: String,
        index: IndexDefinition,
        baseCount: Int,
        used: MutableMap<String, Int>,
    ): String {
        val candidate = if (baseCount == 1) baseName else "${baseName}_${indexDisambiguationSuffix(index)}"
        val seen = used.getOrDefault(candidate, 0)
        used[candidate] = seen + 1
        return if (seen == 0) candidate else "${candidate}_${seen + 1}"
    }

    private fun indexDisambiguationSuffix(index: IndexDefinition): String {
        val directionPart = index.columns.joinToString("_") { it.direction?.name?.lowercase() ?: "default" }
        val wherePart = index.where?.let { "_where_${Integer.toUnsignedString(it.hashCode(), 36)}" }.orEmpty()
        return "$directionPart$wherePart"
    }
}
