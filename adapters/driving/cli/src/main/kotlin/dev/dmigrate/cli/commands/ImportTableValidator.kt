package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.data.TargetColumn

/**
 * Validates that a target database table matches the neutral schema
 * definition before import. Checks column presence, nullability, and
 * type compatibility.
 */
internal object ImportTableValidator {

    fun validateTargetTable(
        schema: SchemaDefinition,
        table: String,
        targetColumns: List<TargetColumn>,
    ) {
        val matches = ImportDirectoryResolver.matchingSchemaTableNames(schema.tables.keys, table)
        val schemaTableName = when {
            matches.isEmpty() ->
                throw ImportSchemaMismatchException(
                    "Table '$table' is not defined in the provided --schema file"
                )
            matches.size > 1 ->
                throw ImportSchemaMismatchException(
                    "Table '$table' matches multiple tables in the provided --schema: ${matches.joinToString()}"
                )
            else -> matches.single()
        }
        val schemaTable = schema.tables.getValue(schemaTableName)

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
                if (!ImportTypeCompatibility.isTypeCompatible(schemaColumn.type, targetColumn)) {
                    add(
                        "column '$columnName' type mismatch: schema expects ${ImportTypeCompatibility.describe(schemaColumn.type)} " +
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
}
