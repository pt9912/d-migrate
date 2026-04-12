package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.yaml.YamlSchemaCodec
import dev.dmigrate.streaming.ImportInput
import java.sql.Types
import java.nio.file.Files
import java.nio.file.Path

object DataImportSchemaPreflight {

    fun prepare(
        schemaPath: Path,
        input: ImportInput,
        format: DataExportFormat,
    ): SchemaPreflightResult {
        val schema = readSchema(schemaPath)
        validateSchema(schemaPath, schema)

        val preparedInput = when (input) {
            is ImportInput.Directory -> input.copy(
                tableOrder = resolveTableOrder(schemaPath, schema, input, format)
            )
            else -> input
        }

        return SchemaPreflightResult(
            input = preparedInput,
            schema = schema,
        )
    }

    fun validateTargetTable(
        schema: SchemaDefinition,
        table: String,
        targetColumns: List<TargetColumn>,
    ) {
        val schemaTable = schema.tables[table]
            ?: throw ImportSchemaMismatchException(
                "Table '$table' is not defined in the provided --schema file"
            )

        val targetByName = targetColumns.associateBy { it.name }
        val missing = schemaTable.columns.keys.filterNot(targetByName::containsKey)
        val unexpected = targetByName.keys.filterNot(schemaTable.columns::containsKey)

        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            val details = buildList {
                if (missing.isNotEmpty()) add("missing target columns: ${missing.joinToString()}")
                if (unexpected.isNotEmpty()) add("unexpected target columns: ${unexpected.joinToString()}")
            }
            throw ImportSchemaMismatchException(
                "Table '$table' does not match the provided --schema (${details.joinToString("; ")})"
            )
        }

        val mismatches = buildList {
            for ((columnName, schemaColumn) in schemaTable.columns) {
                val targetColumn = targetByName.getValue(columnName)
                if (schemaColumn.required == targetColumn.nullable) {
                    add(
                        "column '$columnName' nullability mismatch: schema requires " +
                            "${if (schemaColumn.required) "NOT NULL" else "NULLABLE"} but target is " +
                            "${if (targetColumn.nullable) "NULLABLE" else "NOT NULL"}"
                    )
                }
                if (!isTypeCompatible(schemaColumn.type, targetColumn)) {
                    add(
                        "column '$columnName' type mismatch: schema expects ${describe(schemaColumn.type)} " +
                            "but target is ${targetColumn.sqlTypeName ?: "jdbcType=${targetColumn.jdbcType}"}"
                    )
                }
            }
        }

        if (mismatches.isNotEmpty()) {
            throw ImportSchemaMismatchException(
                "Table '$table' does not match the provided --schema (${mismatches.joinToString("; ")})"
            )
        }
    }

    private fun readSchema(schemaPath: Path): SchemaDefinition {
        if (!Files.exists(schemaPath)) {
            throw ImportPreflightException("Schema path does not exist: $schemaPath")
        }
        if (!Files.isRegularFile(schemaPath)) {
            throw ImportPreflightException("Schema path is not a file: $schemaPath")
        }

        return try {
            Files.newInputStream(schemaPath).use { input ->
                YamlSchemaCodec().read(input)
            }
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to parse schema file '$schemaPath': ${t.message ?: t::class.simpleName}",
                t,
            )
        }
    }

    private fun validateSchema(schemaPath: Path, schema: SchemaDefinition) {
        val result = try {
            SchemaValidator().validate(schema)
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to validate schema file '$schemaPath': ${t.message ?: t::class.simpleName}",
                t,
            )
        }

        if (!result.isValid) {
            val preview = result.errors.take(3).joinToString("; ") {
                "${it.code} ${it.objectPath}: ${it.message}"
            }
            val suffix = if (result.errors.size > 3) "; ..." else ""
            throw ImportPreflightException(
                "Schema validation failed for '$schemaPath': $preview$suffix"
            )
        }
    }

    private fun resolveTableOrder(
        schemaPath: Path,
        schema: SchemaDefinition,
        input: ImportInput.Directory,
        format: DataExportFormat,
    ): List<String> {
        val candidateTables = resolveCandidateTables(input, format)
        if (candidateTables.isEmpty()) return emptyList()

        val selectedTables = linkedMapOf<String, TableDefinition>()
        for ((name, table) in schema.tables) {
            if (name in candidateTables) {
                selectedTables[name] = table
            }
        }

        val missingInSchema = candidateTables.filterNot(selectedTables::containsKey)
        if (missingInSchema.isNotEmpty()) {
            throw ImportPreflightException(
                "Schema file '$schemaPath' does not define tables required for directory import: " +
                    missingInSchema.joinToString()
            )
        }

        val sortResult = topologicalSort(selectedTables)
        if (sortResult.circularEdges.isNotEmpty()) {
            val edges = sortResult.circularEdges.joinToString("; ") {
                "${it.fromTable}.${it.fromColumn} -> ${it.toTable}.${it.toColumn}"
            }
            throw ImportPreflightException(
                "Schema-defined table dependency cycle detected for directory import: $edges"
            )
        }

        return sortResult.sorted
    }

    private fun resolveCandidateTables(
        input: ImportInput.Directory,
        format: DataExportFormat,
    ): List<String> {
        val suffix = ".${format.cliName}"
        val candidates = linkedMapOf<String, Path>()

        try {
            Files.newDirectoryStream(input.path).use { entries ->
                for (entry in entries) {
                    val fileName = entry.fileName.toString()
                    if (!Files.isRegularFile(entry) || !fileName.endsWith(suffix)) continue
                    candidates[fileName.removeSuffix(suffix)] = entry
                }
            }
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to list directory import source '${input.path}': ${t.message ?: t::class.simpleName}",
                t,
            )
        }

        val tableFilter = input.tableFilter
        if (tableFilter != null) {
            val missing = tableFilter.filterNot(candidates::containsKey)
            if (missing.isNotEmpty()) {
                throw ImportPreflightException(
                    "Directory import filter references tables without matching files: ${missing.joinToString()}"
                )
            }
            return tableFilter
        }

        return candidates.keys.toList()
    }

    private data class CircularEdge(
        val fromTable: String,
        val fromColumn: String,
        val toTable: String,
        val toColumn: String,
    )

    private data class SortResult(
        val sorted: List<String>,
        val circularEdges: List<CircularEdge>,
    )

    private fun topologicalSort(tables: Map<String, TableDefinition>): SortResult {
        val deps = linkedMapOf<String, MutableSet<String>>()
        val allEdges = mutableListOf<CircularEdge>()

        for ((tableName, table) in tables) {
            deps.getOrPut(tableName) { linkedSetOf() }
            for ((columnName, column) in table.columns) {
                val reference = column.references ?: continue
                addDependency(
                    deps = deps,
                    edges = allEdges,
                    fromTable = tableName,
                    fromColumn = columnName,
                    toTable = reference.table,
                    toColumn = reference.column,
                    tables = tables,
                )
            }
            for (constraint in table.constraints) {
                if (constraint.type != ConstraintType.FOREIGN_KEY) continue
                val reference = constraint.references ?: continue
                val sourceColumns = constraint.columns.orEmpty()
                val targetColumns = reference.columns
                val pairCount = maxOf(sourceColumns.size, targetColumns.size, 1)
                for (index in 0 until pairCount) {
                    addDependency(
                        deps = deps,
                        edges = allEdges,
                        fromTable = tableName,
                        fromColumn = sourceColumns.getOrElse(index) { constraint.name },
                        toTable = reference.table,
                        toColumn = targetColumns.getOrElse(index) { constraint.name },
                        tables = tables,
                    )
                }
            }
        }

        val inDegree = linkedMapOf<String, Int>()
        for (name in tables.keys) {
            inDegree[name] = deps[name]?.size ?: 0
        }

        val queue = ArrayDeque(inDegree.filterValues { it == 0 }.keys)
        val sorted = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted += current
            for ((table, depSet) in deps) {
                if (depSet.remove(current)) {
                    val remaining = (inDegree.getValue(table) - 1).also { inDegree[table] = it }
                    if (remaining == 0) {
                        queue.addLast(table)
                    }
                }
            }
        }

        val remaining = tables.keys - sorted.toSet()
        val circularEdges = if (remaining.isEmpty()) {
            emptyList()
        } else {
            allEdges.filter { it.fromTable in remaining && it.toTable in remaining }
        }

        return SortResult(sorted = sorted, circularEdges = circularEdges)
    }

    private fun addDependency(
        deps: MutableMap<String, MutableSet<String>>,
        edges: MutableList<CircularEdge>,
        fromTable: String,
        fromColumn: String,
        toTable: String,
        toColumn: String,
        tables: Map<String, TableDefinition>,
    ) {
        if (toTable == fromTable || toTable !in tables) return
        deps.getOrPut(fromTable) { linkedSetOf() }.add(toTable)
        edges += CircularEdge(
            fromTable = fromTable,
            fromColumn = fromColumn,
            toTable = toTable,
            toColumn = toColumn,
        )
    }

    private fun isTypeCompatible(
        schemaType: NeutralType,
        targetColumn: TargetColumn,
    ): Boolean {
        val sqlTypeName = targetColumn.sqlTypeName?.uppercase().orEmpty()
        return when (schemaType) {
            is NeutralType.Identifier ->
                targetColumn.jdbcType in setOf(Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL)
            is NeutralType.Text,
            is NeutralType.Email ->
                targetColumn.jdbcType in setOf(
                    Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB
                ) || sqlTypeName.contains("TEXT")
            is NeutralType.Char ->
                targetColumn.jdbcType in setOf(Types.CHAR, Types.NCHAR)
            NeutralType.Integer ->
                targetColumn.jdbcType == Types.INTEGER || sqlTypeName == "INT4"
            NeutralType.SmallInt ->
                targetColumn.jdbcType == Types.SMALLINT || sqlTypeName == "INT2"
            NeutralType.BigInteger ->
                targetColumn.jdbcType == Types.BIGINT || sqlTypeName == "INT8"
            is NeutralType.Float ->
                if (schemaType.floatPrecision.name == "SINGLE") {
                    targetColumn.jdbcType in setOf(Types.REAL, Types.FLOAT)
                } else {
                    targetColumn.jdbcType in setOf(Types.DOUBLE, Types.FLOAT, Types.REAL)
                }
            is NeutralType.Decimal ->
                targetColumn.jdbcType in setOf(Types.DECIMAL, Types.NUMERIC)
            NeutralType.BooleanType ->
                targetColumn.jdbcType == Types.BOOLEAN ||
                    (targetColumn.jdbcType == Types.BIT && !isMultiBit(sqlTypeName))
            is NeutralType.DateTime ->
                targetColumn.jdbcType in setOf(Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE)
            NeutralType.Date ->
                targetColumn.jdbcType == Types.DATE
            NeutralType.Time ->
                targetColumn.jdbcType in setOf(Types.TIME, Types.TIME_WITH_TIMEZONE)
            NeutralType.Uuid ->
                sqlTypeName == "UUID" ||
                    targetColumn.jdbcType in setOf(Types.OTHER, Types.CHAR, Types.VARCHAR)
            NeutralType.Json ->
                sqlTypeName in setOf("JSON", "JSONB") ||
                    targetColumn.jdbcType in setOf(Types.OTHER, Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB)
            NeutralType.Xml ->
                targetColumn.jdbcType == Types.SQLXML ||
                    sqlTypeName == "XML" ||
                    targetColumn.jdbcType in setOf(Types.OTHER, Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB)
            NeutralType.Binary ->
                targetColumn.jdbcType in setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)
            is NeutralType.Enum ->
                sqlTypeName == "ENUM" ||
                    targetColumn.jdbcType in setOf(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.OTHER)
            is NeutralType.Array ->
                targetColumn.jdbcType == Types.ARRAY || sqlTypeName.endsWith("[]")
        }
    }

    private fun isMultiBit(sqlTypeName: String): Boolean {
        if (!sqlTypeName.startsWith("BIT")) return false
        val start = sqlTypeName.indexOf('(')
        val end = sqlTypeName.indexOf(')')
        if (start < 0 || end <= start + 1) return false
        return sqlTypeName.substring(start + 1, end).trim().toIntOrNull()?.let { it > 1 } == true
    }

    private fun describe(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> "identifier-compatible integer"
        is NeutralType.Text -> "text-compatible type"
        is NeutralType.Char -> "fixed-width char"
        NeutralType.Integer -> "INTEGER"
        NeutralType.SmallInt -> "SMALLINT"
        NeutralType.BigInteger -> "BIGINT"
        is NeutralType.Float -> if (type.floatPrecision.name == "SINGLE") "single-precision float" else "double-precision float"
        is NeutralType.Decimal -> "DECIMAL/NUMERIC"
        NeutralType.BooleanType -> "BOOLEAN"
        is NeutralType.DateTime -> "TIMESTAMP"
        NeutralType.Date -> "DATE"
        NeutralType.Time -> "TIME"
        NeutralType.Uuid -> "UUID-compatible type"
        NeutralType.Json -> "JSON-compatible type"
        NeutralType.Xml -> "XML-compatible type"
        NeutralType.Binary -> "binary/blob type"
        NeutralType.Email -> "text-compatible type"
        is NeutralType.Enum -> "enum/text-compatible type"
        is NeutralType.Array -> "array-compatible type"
    }
}
