package dev.dmigrate.core.validation

import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition

internal object SchemaStructureValidationRules {

    fun validate(schema: SchemaDefinition): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()

        for ((tableName, table) in schema.tables) {
            validateTableHasColumns(tableName, table, errors)
            validatePrimaryKey(tableName, table, warnings)
            validateIndexColumns(tableName, table, errors)
            validatePartitionKey(tableName, table, errors)
            validateCheckExpressionColumns(tableName, table, errors)
            checkFloatWarning(tableName, table, warnings)
        }

        validateTriggerTableReferences(schema, errors)
        validateViewDependencies(schema, errors)

        return ValidationResult(errors, warnings)
    }

    private fun validateTableHasColumns(
        tableName: String,
        table: TableDefinition,
        errors: MutableList<ValidationError>,
    ) {
        if (table.columns.isEmpty()) {
            errors += ValidationError("E001", "Table has no columns", "tables.$tableName")
        }
    }

    private fun validatePrimaryKey(
        tableName: String,
        table: TableDefinition,
        warnings: MutableList<ValidationWarning>,
    ) {
        val hasExplicitPk = table.primaryKey.isNotEmpty()
        val hasIdentifierColumn = table.columns.values.any { it.type is NeutralType.Identifier }
        if (!hasExplicitPk && !hasIdentifierColumn) {
            warnings += ValidationWarning("E008", "Table has no primary key", "tables.$tableName")
        }
    }

    private fun validateIndexColumns(
        tableName: String,
        table: TableDefinition,
        errors: MutableList<ValidationError>,
    ) {
        for (index in table.indices) {
            for (column in index.columns) {
                if (column !in table.columns) {
                    val indexName = index.name ?: index.columns.joinToString(",")
                    errors += ValidationError(
                        "E005",
                        "Index '$indexName' references non-existent column '$column'",
                        "tables.$tableName.indices",
                    )
                }
            }
        }
    }

    private fun validatePartitionKey(
        tableName: String,
        table: TableDefinition,
        errors: MutableList<ValidationError>,
    ) {
        val partitioning = table.partitioning ?: return
        for (key in partitioning.key) {
            if (key !in table.columns) {
                errors += ValidationError(
                    "E016",
                    "Partition key references non-existent column '$key'",
                    "tables.$tableName.partitioning.key",
                )
            }
        }
    }

    private fun validateCheckExpressionColumns(
        tableName: String,
        table: TableDefinition,
        errors: MutableList<ValidationError>,
    ) {
        for (constraint in table.constraints) {
            if (constraint.type != ConstraintType.CHECK) continue
            val expression = constraint.expression ?: continue
            val referencedColumns = extractIdentifiers(expression)
            for (reference in referencedColumns) {
                if (reference !in table.columns) {
                    errors += ValidationError(
                        "E012",
                        "Check expression '${constraint.name}' references unknown column '$reference'",
                        "tables.$tableName.constraints.${constraint.name}",
                    )
                }
            }
        }
    }

    private fun checkFloatWarning(
        tableName: String,
        table: TableDefinition,
        warnings: MutableList<ValidationWarning>,
    ) {
        val monetaryPatterns = setOf(
            "price", "amount", "cost", "total", "balance", "fee", "tax",
            "salary", "wage", "revenue", "profit", "discount", "payment",
        )
        for ((columnName, column) in table.columns) {
            if (column.type is NeutralType.Float) {
                val segments = columnName.lowercase().split("_")
                if (monetaryPatterns.any { it in segments }) {
                    warnings += ValidationWarning(
                        "W001",
                        "Column uses FLOAT — consider DECIMAL for monetary values",
                        "tables.$tableName.columns.$columnName",
                    )
                }
            }
        }
    }

    private fun validateTriggerTableReferences(
        schema: SchemaDefinition,
        errors: MutableList<ValidationError>,
    ) {
        for ((triggerName, trigger) in schema.triggers) {
            if (trigger.table !in schema.tables) {
                errors += ValidationError(
                    "E018",
                    "Trigger references non-existent table '${trigger.table}'",
                    "triggers.$triggerName.table",
                )
            }
        }
    }

    private fun validateViewDependencies(
        schema: SchemaDefinition,
        errors: MutableList<ValidationError>,
    ) {
        for ((viewName, view) in schema.views) {
            for (dependency in view.dependencies?.views.orEmpty()) {
                if (dependency !in schema.views) {
                    errors += ValidationError(
                        "E020",
                        "View dependency references non-existent view '$dependency'",
                        "views.$viewName.dependencies.views",
                    )
                }
            }
        }
    }

    private fun extractIdentifiers(expression: String): List<String> {
        val stripped = expression.replace(Regex("'[^']*'"), "")
        val sqlKeywords = setOf(
            "AND", "OR", "NOT", "IN", "IS", "NULL", "TRUE", "FALSE",
            "BETWEEN", "LIKE", "ILIKE", "SIMILAR", "ANY", "ALL", "SOME",
            "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END",
            "CHECK", "VALUE", "CURRENT_TIMESTAMP",
            "ASC", "DESC", "HAVING", "OLD", "NEW",
        )
        return Regex("[a-zA-Z_][a-zA-Z0-9_]*")
            .findAll(stripped)
            .map { it.value }
            .filter { it.uppercase() !in sqlKeywords }
            .distinct()
            .toList()
    }
}
