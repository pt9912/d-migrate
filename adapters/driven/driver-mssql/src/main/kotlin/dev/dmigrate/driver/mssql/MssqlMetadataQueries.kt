package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.driver.metadata.ConstraintProjection
import dev.dmigrate.driver.metadata.ForeignKeyProjection
import dev.dmigrate.driver.metadata.IndexProjection
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.metadata.TableRef

/**
 * Katalog-Queries für den MSSQL-Reverse-Read. Bewusst `sys.*`-Sichten
 * statt `INFORMATION_SCHEMA`: Identity-Metadaten (Seed/Increment),
 * Default-Constraint-Definitionen, gefilterte Indizes und Computed
 * Columns sind dort nicht (vollständig) sichtbar — das Risiko aus dem
 * Scoping-Plan (docs/planning/in-progress/mssql-dialect-scoping.md).
 */
internal object MssqlMetadataQueries {

    /**
     * `sys.indexes.type` = 1. SQL Server nummeriert die Ablageform dort statt sie
     * zu benennen: 0 ist der Heap, 1 der clustered Index, 2 der nonclustered, und
     * darueber folgen XML, raeumlich und Columnstore. Der Scan filtert `type > 0`,
     * es bleiben also die echten Indizes.
     */
    private const val CLUSTERED_INDEX_TYPE = 1

    /** Spalten-Zeile inkl. Identity-/Default-/Computed-Metadaten. */
    data class ColumnRow(
        val name: String,
        val typeName: String,
        val maxLength: Int?,
        val precision: Int?,
        val scale: Int?,
        val nullable: Boolean,
        val isIdentity: Boolean,
        val identitySeed: Long?,
        val identityIncrement: Long?,
        val isComputed: Boolean,
        val computedDefinition: String?,
        val defaultDefinition: String?,
        val ordinal: Int,
    )

    /** Index-Scan: Projektionen plus Namen der Indizes mit INCLUDE-Spalten. */
    data class IndexScan(
        val indices: List<IndexProjection>,
    )

    /**
     * Der Rohbefund der Partitionierung. `boundaries` sind die Grenzwerte in
     * Katalogreihenfolge; die Zahl der Partitionen ist stets `boundaries + 1`.
     */
    data class PartitionScan(
        val functionName: String,
        val schemeName: String,
        val boundaryOnRight: Boolean,
        val column: String,
        val boundaries: List<String>,
    )

    data class SequenceRow(
        val name: String,
        val typeName: String,
        val start: Long,
        val increment: Long,
        val minValue: Long?,
        val maxValue: Long?,
        val cycle: Boolean,
        val cache: Int?,
    )

    data class ViewRow(val name: String, val definition: String)

    fun listTableRefs(session: JdbcOperations, schema: String): List<TableRef> =
        session.queryList(
            """
            SELECT t.name AS table_name, s.name AS schema_name
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ? AND t.is_ms_shipped = 0
            ORDER BY t.name
            """.trimIndent(),
            schema,
        ).map { row -> TableRef(name = row.string("table_name"), schema = row["schema_name"] as? String) }

    fun listColumns(session: JdbcOperations, qualifiedTable: String): List<ColumnRow> =
        session.queryList(
            """
            SELECT c.name AS column_name, ty.name AS type_name, c.max_length, c.precision, c.scale,
                   c.is_nullable, c.is_identity, c.is_computed, c.column_id,
                   dc.definition AS default_definition,
                   ic.seed_value, ic.increment_value,
                   cc.definition AS computed_definition
            FROM sys.columns c
            JOIN sys.types ty ON ty.user_type_id = c.user_type_id
            LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id
            LEFT JOIN sys.identity_columns ic
                ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            LEFT JOIN sys.computed_columns cc
                ON cc.object_id = c.object_id AND cc.column_id = c.column_id
            WHERE c.object_id = OBJECT_ID(?)
            ORDER BY c.column_id
            """.trimIndent(),
            qualifiedTable,
        ).map { row ->
            ColumnRow(
                name = row.string("column_name"),
                typeName = row.string("type_name"),
                maxLength = row.int("max_length"),
                precision = row.int("precision"),
                scale = row.int("scale"),
                nullable = row.bool("is_nullable"),
                isIdentity = row.bool("is_identity"),
                identitySeed = row.long("seed_value"),
                identityIncrement = row.long("increment_value"),
                isComputed = row.bool("is_computed"),
                computedDefinition = row["computed_definition"] as? String,
                defaultDefinition = row["default_definition"] as? String,
                ordinal = row.int("column_id") ?: 0,
            )
        }

    fun listPrimaryKeyColumns(session: JdbcOperations, qualifiedTable: String): List<String> =
        session.queryList(
            """
            SELECT col.name AS column_name
            FROM sys.key_constraints kc
            JOIN sys.index_columns ic
                ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id
            JOIN sys.columns col
                ON col.object_id = ic.object_id AND col.column_id = ic.column_id
            WHERE kc.parent_object_id = OBJECT_ID(?) AND kc.type = 'PK'
            ORDER BY ic.key_ordinal
            """.trimIndent(),
            qualifiedTable,
        ).map { it.string("column_name") }

    fun listForeignKeys(session: JdbcOperations, qualifiedTable: String): List<ForeignKeyProjection> {
        val rows = session.queryList(
            """
            SELECT fk.name AS constraint_name, pc.name AS column_name,
                   rt.name AS referenced_table, rc.name AS referenced_column,
                   fk.delete_referential_action_desc, fk.update_referential_action_desc,
                   fkc.constraint_column_id
            FROM sys.foreign_keys fk
            JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
            JOIN sys.columns pc
                ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
            JOIN sys.columns rc
                ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
            JOIN sys.tables rt ON rt.object_id = fkc.referenced_object_id
            WHERE fk.parent_object_id = OBJECT_ID(?)
            ORDER BY fk.name, fkc.constraint_column_id
            """.trimIndent(),
            qualifiedTable,
        )
        return rows.groupBy { it.string("constraint_name") }.map { (name, group) ->
            ForeignKeyProjection(
                name = name,
                columns = group.map { it.string("column_name") },
                referencedTable = group.first().string("referenced_table"),
                referencedColumns = group.map { it.string("referenced_column") },
                onDelete = group.first().actionDesc("delete_referential_action_desc"),
                onUpdate = group.first().actionDesc("update_referential_action_desc"),
            )
        }
    }

    fun scanIndexes(session: JdbcOperations, qualifiedTable: String): IndexScan {
        val rows = session.queryList(
            """
            SELECT i.name AS index_name, i.is_unique, i.has_filter, i.filter_definition, i.type,
                   col.name AS column_name, ic.key_ordinal, ic.is_descending_key, ic.is_included_column
            FROM sys.indexes i
            JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.columns col ON col.object_id = ic.object_id AND col.column_id = ic.column_id
            WHERE i.object_id = OBJECT_ID(?) AND i.type > 0
              AND i.is_primary_key = 0 AND i.is_hypothetical = 0
            ORDER BY i.name, ic.key_ordinal, ic.index_column_id
            """.trimIndent(),
            qualifiedTable,
        )
        val byIndex = rows.groupBy { it.string("index_name") }
        val indices = byIndex.map { (name, group) ->
            val keyColumns = group.filterNot { it.bool("is_included_column") }
            IndexProjection(
                name = name,
                columns = keyColumns.map { it.string("column_name") },
                isUnique = group.first().bool("is_unique"),
                directions = keyColumns.map { row ->
                    if (row.bool("is_descending_key")) IndexSortDirection.DESC else null
                },
                where = group.first()["filter_definition"] as? String,
                includeColumns = group.filter { it.bool("is_included_column") }
                    .map { it.string("column_name") },
                clustered = group.first().int("type") == CLUSTERED_INDEX_TYPE,
            )
        }
        return IndexScan(indices = indices)
    }

    /**
     * Die Partitionierung einer Tabelle, wie SQL Server sie fuehrt.
     *
     * Anders als PostgreSQL und MySQL beschreibt SQL Server sie nicht an der
     * Tabelle, sondern in zwei eigenstaendigen Objekten: einer Partition
     * Function (Grenzwerte und die Seite, auf der sie liegen) und einem
     * Partition Scheme (Zuordnung der Partitionen zu Filegroups). Die Tabelle
     * haengt ueber die Ablage ihres clustered Index bzw. Heaps daran --
     * `index_id IN (0, 1)` trifft genau diese beiden.
     *
     * `partition_ordinal = 1` liefert die Partitionierungsspalte; SQL Server
     * partitioniert immer nach genau einer.
     */
    /**
     * Die Spalten des Volltext-Index einer Tabelle, in Katalogreihenfolge.
     *
     * Leer, wenn die Tabelle keinen traegt. SQL Server erlaubt hoechstens einen
     * je Tabelle, deshalb genuegt eine Liste ohne Gruppierung.
     */
    fun scanFullTextColumns(session: JdbcOperations, qualifiedTable: String): List<String> = session.queryList(
        """
        SELECT col.name AS column_name
        FROM sys.fulltext_index_columns ftc
        JOIN sys.columns col ON col.object_id = ftc.object_id AND col.column_id = ftc.column_id
        WHERE ftc.object_id = OBJECT_ID(?)
        ORDER BY ftc.column_id
        """.trimIndent(),
        qualifiedTable,
    ).mapNotNull { it.string("column_name") }

    fun scanPartitioning(session: JdbcOperations, qualifiedTable: String): PartitionScan? {
        val head = session.queryList(
            """
            SELECT pf.name AS function_name, ps.name AS scheme_name, pf.function_id,
                   pf.boundary_value_on_right, col.name AS column_name
            FROM sys.tables t
            JOIN sys.indexes i ON i.object_id = t.object_id AND i.index_id IN (0, 1)
            JOIN sys.partition_schemes ps ON ps.data_space_id = i.data_space_id
            JOIN sys.partition_functions pf ON pf.function_id = ps.function_id
            JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                AND ic.partition_ordinal = 1
            JOIN sys.columns col ON col.object_id = ic.object_id AND col.column_id = ic.column_id
            WHERE t.object_id = OBJECT_ID(?)
            """.trimIndent(),
            qualifiedTable,
        ).firstOrNull() ?: return null

        val functionId = head.int("function_id") ?: return null
        val boundaries = session.queryList(
            """
            SELECT prv.value
            FROM sys.partition_range_values prv
            WHERE prv.function_id = ?
            ORDER BY prv.boundary_id
            """.trimIndent(),
            functionId,
        ).map { boundaryLiteral(it["value"]) }

        return PartitionScan(
            functionName = head.string("function_name"),
            schemeName = head.string("scheme_name"),
            boundaryOnRight = head.bool("boundary_value_on_right"),
            column = head.string("column_name"),
            boundaries = boundaries,
        )
    }

    /**
     * Ein Grenzwert als kanonisches Literal in derselben Form, die der
     * PostgreSQL-Reverse liefert: Zahlen blank, alles andere in einfachen
     * Anfuehrungszeichen. Der Wert kommt als `sql_variant` und damit je nach
     * Spaltentyp als Zahl, Datum oder Zeichenkette aus dem Treiber.
     */
    private fun boundaryLiteral(value: Any?): String = when (value) {
        null -> error("partition boundary value is NULL; sys.partition_range_values does not allow it")
        is java.sql.Timestamp -> "'" + timestampLiteral(value) + "'"
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        else -> "'" + value.toString().replace("'", "''") + "'"
    }

    /**
     * Ein Zeitstempel in der Form, die der PostgreSQL-Reverse liefert:
     * `2024-01-01 00:00:00`, Bruchteile nur wenn sie ungleich null sind.
     *
     * `java.sql.Timestamp.toString()` haengt **immer** mindestens `.0` an -- gegen
     * echtes SQL Server gemessen, nicht vermutet. Damit verglichen zwei identische
     * Schemata ueber Dialekte hinweg als verschieden, denn der Comparator
     * vergleicht das Literal zeichenweise und der Fingerabdruck projiziert es
     * woertlich (Vertrag in `MysqlPartitionBoundRenderer`).
     */
    private fun timestampLiteral(value: java.sql.Timestamp): String {
        val moment = value.toLocalDateTime()
        val base = "%04d-%02d-%02d %02d:%02d:%02d".format(
            moment.year, moment.monthValue, moment.dayOfMonth,
            moment.hour, moment.minute, moment.second,
        )
        if (moment.nano == 0) return base
        return base + "." + "%09d".format(moment.nano).trimEnd('0')
    }

    fun listCheckConstraints(session: JdbcOperations, qualifiedTable: String): List<ConstraintProjection> =
        session.queryList(
            """
            SELECT cc.name AS constraint_name, cc.definition
            FROM sys.check_constraints cc
            WHERE cc.parent_object_id = OBJECT_ID(?) AND cc.is_ms_shipped = 0
            ORDER BY cc.name
            """.trimIndent(),
            qualifiedTable,
        ).map { row ->
            ConstraintProjection(
                name = row.string("constraint_name"),
                type = "CHECK",
                expression = MssqlTypeMapping.normalizeCheckExpression(row.string("definition")),
            )
        }

    fun listSequences(session: JdbcOperations, schema: String): List<SequenceRow> =
        session.queryList(
            """
            SELECT seq.name AS sequence_name, ty.name AS type_name,
                   CAST(seq.start_value AS bigint) AS start_value,
                   CAST(seq.increment AS bigint) AS increment_value,
                   CAST(seq.minimum_value AS bigint) AS minimum_value,
                   CAST(seq.maximum_value AS bigint) AS maximum_value,
                   seq.is_cycling,
                   seq.is_cached,
                   seq.cache_size
            FROM sys.sequences seq
            JOIN sys.schemas s ON s.schema_id = seq.schema_id
            JOIN sys.types ty ON ty.user_type_id = seq.user_type_id
            WHERE s.name = ?
            ORDER BY seq.name
            """.trimIndent(),
            schema,
        ).map { row ->
            SequenceRow(
                name = row.string("sequence_name"),
                typeName = row.string("type_name"),
                start = row.long("start_value") ?: 1L,
                increment = row.long("increment_value") ?: 1L,
                minValue = row.long("minimum_value"),
                maxValue = row.long("maximum_value"),
                cycle = row.bool("is_cycling"),
                cache = if (row.bool("is_cached")) row.int("cache_size") else null,
            )
        }

    fun listViews(session: JdbcOperations, schema: String): List<ViewRow> =
        session.queryList(
            """
            SELECT v.name AS view_name, m.definition
            FROM sys.views v
            JOIN sys.sql_modules m ON m.object_id = v.object_id
            WHERE v.schema_id = SCHEMA_ID(?) AND v.is_ms_shipped = 0
            ORDER BY v.name
            """.trimIndent(),
            schema,
        ).map { row -> ViewRow(name = row.string("view_name"), definition = row.string("definition")) }

    /**
     * IDENTITY-Spalte samt deklariertem Seed/Increment. [lastValue] ist `null`,
     * solange **nie** eine Zeile eingefügt wurde — `DBCC CHECKIDENT … RESEED n`
     * verhält sich dann anders (erster Wert = `n` statt `n + increment`).
     */
    data class IdentityColumn(
        val column: String,
        val seed: Long,
        val increment: Long,
        val lastValue: Long?,
    )

    /** IDENTITY-Spalte der Tabelle (`OBJECT_ID`-Form), oder `null`. */
    fun identityColumn(session: JdbcOperations, qualifiedTable: String): IdentityColumn? =
        session.querySingle(
            """
            SELECT TOP 1 c.name AS column_name,
                   CAST(c.seed_value AS bigint) AS seed_value,
                   CAST(c.increment_value AS bigint) AS increment_value,
                   CAST(c.last_value AS bigint) AS last_value
            FROM sys.identity_columns c
            WHERE c.object_id = OBJECT_ID(?)
            """.trimIndent(),
            qualifiedTable,
        )?.let { row ->
            val name = row["column_name"] as? String ?: return null
            IdentityColumn(
                column = name,
                seed = (row["seed_value"] as? Number)?.toLong() ?: 1L,
                increment = (row["increment_value"] as? Number)?.toLong() ?: 1L,
                lastValue = (row["last_value"] as? Number)?.toLong(),
            )
        }

    /**
     * Computed Columns der Tabelle — SQL Server lehnt jedes explizite Schreiben
     * darauf ab (Msg 271), auch mit `SET IDENTITY_INSERT`.
     */
    fun computedColumns(session: JdbcOperations, qualifiedTable: String): Set<String> =
        session.queryList(
            """
            SELECT c.name AS column_name
            FROM sys.computed_columns c
            WHERE c.object_id = OBJECT_ID(?)
            """.trimIndent(),
            qualifiedTable,
        ).mapNotNullTo(mutableSetOf()) { it["column_name"] as? String }

    /** Alle IDENTITY-/Computed-Spalten, die ein Import nicht selbst schreiben darf. */
    fun identityColumns(session: JdbcOperations, qualifiedTable: String): Set<String> =
        session.queryList(
            """
            SELECT c.name AS column_name
            FROM sys.identity_columns c
            WHERE c.object_id = OBJECT_ID(?)
            """.trimIndent(),
            qualifiedTable,
        ).mapNotNullTo(mutableSetOf()) { it["column_name"] as? String }

    /** `MAX(<column>)` der Tabelle; `null` bei leerer Tabelle. */
    fun maxValue(session: JdbcOperations, quotedTable: String, column: String): Long? =
        (
            session.querySingle(
                "SELECT MAX(${MssqlIdentifiers.bracket(column)}) AS max_value FROM $quotedTable",
            )?.get("max_value") as? Number
            )?.toLong()

    data class UnreadObject(val type: String, val name: String)

    /**
     * Routinen/Trigger im Schema, die der Reverse-Reader nicht liest —
     * inklusive der CLR-Varianten (PC/FS/FT/TA), damit kein Objekt still
     * aus dem Ergebnis fällt.
     */
    /** Ein Routinen-Parameter aus `sys.parameters`. */
    data class RoutineParamRow(
        val routine: String,
        val name: String,
        val typeName: String,
        val isOutput: Boolean,
        val isReadonly: Boolean = false,
        val isReturnValue: Boolean,
        val precision: Int? = null,
        val scale: Int? = null,
    )

    /**
     * Parameter und Rueckgabetyp der Routinen eines Schemas.
     *
     * Aus dem Katalog statt aus dem Definitionstext: `sys.parameters` traegt
     * Name, Typ und Richtung strukturiert, und der Rueckgabewert einer Funktion
     * steht dort als Parameter mit `parameter_id = 0`.
     */
    fun listRoutineParameters(session: JdbcOperations, schema: String): List<RoutineParamRow> = session.queryList(
        """
        SELECT o.name AS routine_name, p.name AS param_name, t.name AS type_name,
               p.is_output, p.is_readonly, p.parameter_id, p.precision, p.scale
        FROM sys.objects o
        JOIN sys.parameters p ON p.object_id = o.object_id
        JOIN sys.types t ON t.user_type_id = p.user_type_id
        WHERE o.schema_id = SCHEMA_ID(?) AND o.is_ms_shipped = 0
          AND o.type IN ('P', 'FN', 'IF', 'TF')
        ORDER BY o.name, p.parameter_id
        """.trimIndent(),
        schema,
    ).map { row ->
        val id = row.int("parameter_id") ?: 0
        RoutineParamRow(
            routine = row.string("routine_name"),
            // Der Rueckgabewert traegt einen leeren Namen.
            name = (row["param_name"]?.toString() ?: "").removePrefix("@"),
            typeName = row.string("type_name"),
            isOutput = row.bool("is_output") == true,
            // READONLY gibt es in T-SQL nur fuer tabellenwertige Parameter.
            isReadonly = row.bool("is_readonly") == true,
            isReturnValue = id == 0,
            precision = row.int("precision"),
            scale = row.int("scale"),
        )
    }

    /**
     * Eine gelesene Routine: Rumpf aus `sys.sql_modules`, plus die
     * Trigger-Angaben, die nur fuer `TR` gefuellt sind.
     */
    data class RoutineRow(
        val type: String,
        val name: String,
        val definition: String,
        val table: String?,
        val isInsert: Boolean,
        val isUpdate: Boolean,
        val isDelete: Boolean,
        val isInsteadOf: Boolean,
    )

    /**
     * Funktionen, Prozeduren und Trigger mit T-SQL-Rumpf.
     *
     * Der `JOIN` auf `sys.sql_modules` schliesst CLR-Routinen aus: ihr Code
     * liegt in einer Assembly und steht dort nicht. Genau diese bleiben
     * ungelesen und werden weiterhin gemeldet.
     */
    fun listRoutines(session: JdbcOperations, schema: String): List<RoutineRow> = session.queryList(
        """
        SELECT o.type AS object_type, o.name AS object_name, m.definition,
               OBJECT_NAME(o.parent_object_id) AS parent_name,
               OBJECTPROPERTY(o.object_id, 'ExecIsInsertTrigger') AS is_insert,
               OBJECTPROPERTY(o.object_id, 'ExecIsUpdateTrigger') AS is_update,
               OBJECTPROPERTY(o.object_id, 'ExecIsDeleteTrigger') AS is_delete,
               OBJECTPROPERTY(o.object_id, 'ExecIsInsteadOfTrigger') AS is_instead_of
        FROM sys.objects o
        JOIN sys.sql_modules m ON m.object_id = o.object_id
        WHERE o.schema_id = SCHEMA_ID(?) AND o.is_ms_shipped = 0
          AND o.type IN ('P', 'FN', 'IF', 'TF', 'TR')
        ORDER BY o.type, o.name
        """.trimIndent(),
        schema,
    ).mapNotNull { row ->
        val definition = row["definition"]?.toString() ?: return@mapNotNull null
        RoutineRow(
            type = row.string("object_type").trim(),
            name = row.string("object_name"),
            definition = definition,
            table = row["parent_name"]?.toString(),
            isInsert = row.int("is_insert") == 1,
            isUpdate = row.int("is_update") == 1,
            isDelete = row.int("is_delete") == 1,
            isInsteadOf = row.int("is_instead_of") == 1,
        )
    }

    /**
     * Routinen **ohne** lesbaren T-SQL-Rumpf.
     *
     * Zwei Faelle fallen darunter, und beide bleiben ungelesen: CLR-Routinen
     * (`PC`, `FS`, `FT`, `TA`) haben gar keine `sys.sql_modules`-Zeile, ihr Code
     * liegt in einer .NET-Assembly; verschluesselte Routinen (`WITH ENCRYPTION`)
     * haben eine Zeile, aber `definition IS NULL`. Der `LEFT JOIN` faengt beide.
     */
    fun listUnreadObjects(session: JdbcOperations, schema: String): List<UnreadObject> =
        session.queryList(
            """
            SELECT o.type AS object_type, o.name AS object_name
            FROM sys.objects o
            LEFT JOIN sys.sql_modules m ON m.object_id = o.object_id
            WHERE o.schema_id = SCHEMA_ID(?) AND o.is_ms_shipped = 0
              AND o.type IN ('P', 'PC', 'FN', 'FS', 'FT', 'IF', 'TF', 'TR', 'TA')
              AND m.definition IS NULL
            ORDER BY o.type, o.name
            """.trimIndent(),
            schema,
        ).map { row -> UnreadObject(type = row.string("object_type").trim(), name = row.string("object_name")) }

    // T-SQL-Action-Descs kommen mit Unterstrich (`SET_NULL`); der geteilte
    // SchemaReaderUtils.toReferentialAction erwartet die Leerzeichen-Form.
    private fun Map<String, Any?>.actionDesc(key: String): String? =
        (this[key] as? String)?.replace('_', ' ')

    private fun Map<String, Any?>.string(key: String): String =
        requireNotNull(this[key] as? String) { "missing '$key' in catalog row" }

    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

    private fun Map<String, Any?>.bool(key: String): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }
}
