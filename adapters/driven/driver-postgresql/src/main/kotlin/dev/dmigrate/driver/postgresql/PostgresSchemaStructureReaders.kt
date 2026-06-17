package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.metadata.SchemaReaderUtils

internal fun readPostgresTables(
    session: JdbcOperations,
    schema: String,
    notes: MutableList<SchemaReadNote>,
): Map<String, TableDefinition> {
    val tableRefs = PostgresMetadataQueries.listTableRefs(session, schema)
    val result = LinkedHashMap<String, TableDefinition>()
    for (ref in tableRefs) {
        result[ref.name] = readPostgresTable(session, schema, ref.name, notes)
    }
    return result
}

private fun readPostgresTable(
    session: JdbcOperations,
    schema: String,
    tableName: String,
    notes: MutableList<SchemaReadNote>,
): TableDefinition {
    val columnRows = PostgresMetadataQueries.listColumns(session, schema, tableName)
    val primaryKeyColumns = PostgresMetadataQueries.listPrimaryKeyColumns(session, schema, tableName)
    val foreignKeys = PostgresMetadataQueries.listForeignKeys(session, schema, tableName)
    val uniqueConstraints = PostgresMetadataQueries.listUniqueConstraintColumns(session, schema, tableName)
    val checkConstraints = PostgresMetadataQueries.listCheckConstraints(session, schema, tableName)
    val indexRows = PostgresMetadataQueries.listIndices(session, schema, tableName)

    val singleColumnUnique = SchemaReaderUtils.singleColumnUniqueFromConstraints(uniqueConstraints)

    val columns = LinkedHashMap<String, ColumnDefinition>()
    for (row in columnRows) {
        val columnName = row["column_name"] as String
        val isPrimaryKeyColumn = columnName in primaryKeyColumns
        val isIdentity = (row["is_identity"] as? String) == "YES"
        val mapping = PostgresTypeMapping.mapColumn(
            PostgresTypeMapping.ColumnInput(
                dataType = row["data_type"] as String,
                udtName = (row["udt_name"] as? String) ?: (row["data_type"] as String),
                isPkCol = isPrimaryKeyColumn,
                isIdentity = isIdentity,
                identityGeneration = row["identity_generation"] as? String,
                colDefault = row["column_default"] as? String,
                generatedSequenceName = row["generated_sequence_name"] as? String,
                charMaxLen = (row["character_maximum_length"] as? Number)?.toInt(),
                numPrecision = (row["numeric_precision"] as? Number)?.toInt(),
                numScale = (row["numeric_scale"] as? Number)?.toInt(),
                tableName = tableName,
                colName = columnName,
            )
        )
        if (mapping.note != null) notes += mapping.note

        val required = (row["is_nullable"] as String) == "NO"
        val unique = if (isPrimaryKeyColumn) false else columnName in singleColumnUnique
        val defaultValue = if (
            isPrimaryKeyColumn &&
            PostgresTypeMapping.isSerialDefault(row["column_default"] as? String)
        ) {
            null
        } else {
            PostgresTypeMapping.parseDefault(row["column_default"] as? String)
        }

        columns[columnName] = ColumnDefinition(
            type = mapping.type,
            required = required,
            unique = unique,
            default = defaultValue,
            generation = mapping.generation,
        )
    }

    val constraints = mutableListOf<ConstraintDefinition>()
    constraints += SchemaReaderUtils.buildForeignKeyConstraints(foreignKeys)
    constraints += SchemaReaderUtils.buildMultiColumnUniqueFromConstraints(uniqueConstraints)
    constraints += SchemaReaderUtils.buildCheckConstraints(checkConstraints)

    val indices = indexRows.map { index ->
        IndexDefinition(
            name = index.name,
            columns = index.indexColumns,
            type = when (index.type) {
                "btree" -> IndexType.BTREE
                "hash" -> IndexType.HASH
                "gin" -> IndexType.GIN
                "gist" -> IndexType.GIST
                "brin" -> IndexType.BRIN
                else -> IndexType.BTREE
            },
            unique = index.isUnique,
            where = index.where,
        )
    }

    return TableDefinition(
        columns = columns,
        primaryKey = primaryKeyColumns,
        indices = indices,
        constraints = constraints,
        partitioning = readPostgresPartitioning(session, schema, tableName),
    )
}

private fun readPostgresPartitioning(
    session: JdbcOperations,
    schema: String,
    tableName: String,
): PartitionConfig? {
    val info = PostgresMetadataQueries.getPartitionInfo(session, schema, tableName)
        ?: return null
    val strategy = when (info["partstrat"] as? String) {
        "r" -> PartitionType.RANGE
        "l" -> PartitionType.LIST
        "h" -> PartitionType.HASH
        else -> return null
    }
    val keyColumns = info["key_columns"]
    val key = when (keyColumns) {
        is java.sql.Array -> (keyColumns.array as Array<*>).map { it.toString() }
        is String -> keyColumns.removeSurrounding("{", "}").split(",")
        else -> emptyList()
    }
    return PartitionConfig(type = strategy, key = key)
}

internal fun readPostgresSequences(
    session: JdbcOperations,
    schema: String,
): Map<String, SequenceDefinition> {
    val rows = PostgresMetadataQueries.listSequences(session, schema)
    val result = LinkedHashMap<String, SequenceDefinition>()
    for (row in rows) {
        val name = row["sequence_name"] as String
        result[name] = SequenceDefinition(
            start = toLongOrNull(row["start_value"]) ?: 1,
            increment = toLongOrNull(row["increment"]) ?: 1,
            minValue = toLongOrNull(row["minimum_value"]),
            maxValue = toLongOrNull(row["maximum_value"]),
            cycle = (row["cycle_option"] as? String) == "YES",
            cache = toLongOrNull(row["cache_size"])?.toInt(),
        )
    }
    return result
}

private fun toLongOrNull(value: Any?): Long? = when (value) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

internal fun readPostgresCustomTypes(
    session: JdbcOperations,
    schema: String,
): Map<String, CustomTypeDefinition> {
    val result = LinkedHashMap<String, CustomTypeDefinition>()

    for ((name, values) in PostgresMetadataQueries.listEnumTypes(session, schema)) {
        result[name] = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = values)
    }

    for (row in PostgresMetadataQueries.listDomainTypes(session, schema)) {
        val name = row["typname"] as String
        val baseType = row["base_type"] as? String ?: "text"
        result[name] = CustomTypeDefinition(
            kind = CustomTypeKind.DOMAIN,
            baseType = PostgresTypeMapping.mapParamType(baseType),
            precision = (row["numeric_precision"] as? Number)?.toInt(),
            scale = (row["numeric_scale"] as? Number)?.toInt(),
            check = normalizeDomainCheck(row["check_clause"] as? String),
        )
    }

    val compositeRows = PostgresMetadataQueries.listCompositeTypes(session, schema)
    for ((typeName, fieldRows) in compositeRows.groupBy { it["typname"] as String }) {
        val fields = LinkedHashMap<String, ColumnDefinition>()
        for (fieldRow in fieldRows.sortedBy { (it["attnum"] as Number).toInt() }) {
            val fieldName = fieldRow["attname"] as String
            val columnType = fieldRow["column_type"] as? String ?: "text"
            fields[fieldName] = ColumnDefinition(
                type = PostgresTypeMapping.mapCompositeFieldType(columnType),
            )
        }
        result[typeName] = CustomTypeDefinition(
            kind = CustomTypeKind.COMPOSITE,
            fields = fields,
        )
    }

    return result
}

/**
 * `pg_get_constraintdef` liefert den vollständigen Domain-Check inkl. Hülle, z. B.
 * `CHECK ((VALUE > 0))`. Das Modell soll nur das Prädikat halten, damit die
 * Generate-Seite genau einmal `CHECK (...)` wrappt (sonst entsteht
 * `CHECK (CHECK (...))`). Entfernt das führende `CHECK`-Token und – falls die
 * äußerste Klammer das gesamte Prädikat umschließt – genau dieses eine Klammerpaar.
 */
internal fun normalizeDomainCheck(raw: String?): String? {
    if (raw == null) return null
    var s = raw.trim()
    if (s.regionMatches(0, "CHECK", 0, 5, ignoreCase = true)) {
        s = s.substring(5).trim()
        if (s.startsWith("(") && outerParenSpansWhole(s)) {
            s = s.substring(1, s.length - 1).trim()
        }
    }
    return s.ifEmpty { null }
}

/** True, wenn die öffnende Klammer an Index 0 erst am letzten Zeichen geschlossen wird. */
private fun outerParenSpansWhole(s: String): Boolean {
    var depth = 0
    for ((i, c) in s.withIndex()) {
        when (c) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i == s.length - 1
            }
        }
    }
    return false
}
