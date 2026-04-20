package dev.dmigrate.core.validation

import dev.dmigrate.core.model.*

class SchemaValidator {

    companion object {
        /** All neutral base types recognized by the schema model. */
        val BASE_TYPE_NAMES = setOf(
            "identifier", "text", "char", "integer", "smallint", "biginteger",
            "float", "decimal", "boolean", "datetime", "date", "time",
            "uuid", "json", "xml", "binary", "email", "enum", "array",
            "geometry",
        )

        /**
         * Types allowed as `array.element_type`. Deliberately separate from
         * [BASE_TYPE_NAMES] so that adding a new base type (e.g. geometry)
         * does not automatically make it a valid array element.
         */
        /**
         * Excludes only `geometry` (not a valid array element in 0.5.5).
         * `enum` and `array` remain allowed to preserve pre-0.5.5 behavior.
         */
        val ARRAY_ELEMENT_TYPE_NAMES = BASE_TYPE_NAMES - setOf("geometry")

        @Deprecated("Use BASE_TYPE_NAMES or ARRAY_ELEMENT_TYPE_NAMES instead",
            replaceWith = ReplaceWith("BASE_TYPE_NAMES"))
        val VALID_TYPE_NAMES = BASE_TYPE_NAMES
    }

    fun validate(schema: SchemaDefinition): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()

        for ((tableName, table) in schema.tables) {
            validateTableHasColumns(tableName, table, errors)
            validatePrimaryKey(tableName, table, warnings)
            validateIndexColumns(tableName, table, errors)
            validatePartitionKey(tableName, table, errors)
            validateCheckExpressionColumns(tableName, table, errors)

            for ((colName, col) in table.columns) {
                val path = "tables.$tableName.columns.$colName"
                validateColumnType(path, col, errors)
                validateForeignKeyTableExists(path, col, schema, errors)
                validateForeignKeyColumnExists(path, col, schema, errors)
                validateForeignKeyTypeCompatibility(path, colName, col, schema, errors)
                validateRefTypeExists(path, col, schema, errors)
                validateDefaultTypeCompatibility(path, col, errors)
                validateSequenceDefaultReference(path, col, schema, errors)
            }

            checkFloatWarning(tableName, table, warnings)
        }

        validateTriggerTableReferences(schema, errors)
        validateViewDependencies(schema, errors)

        return ValidationResult(errors, warnings)
    }

    // E001: Table must have at least one column
    private fun validateTableHasColumns(
        tableName: String, table: TableDefinition, errors: MutableList<ValidationError>
    ) {
        if (table.columns.isEmpty()) {
            errors += ValidationError("E001", "Table has no columns", "tables.$tableName")
        }
    }

    // E008: Table should have a primary key (downgraded to warning for 0.6.0
    // reverse-engineering support — reverse-generated schemas may lack PKs)
    private fun validatePrimaryKey(
        tableName: String, table: TableDefinition, warnings: MutableList<ValidationWarning>
    ) {
        val hasExplicitPk = table.primaryKey.isNotEmpty()
        val hasIdentifierColumn = table.columns.values.any { it.type is NeutralType.Identifier }
        if (!hasExplicitPk && !hasIdentifierColumn) {
            warnings += ValidationWarning("E008", "Table has no primary key", "tables.$tableName")
        }
    }

    // E005: Index columns must exist in table
    private fun validateIndexColumns(
        tableName: String, table: TableDefinition, errors: MutableList<ValidationError>
    ) {
        for (index in table.indices) {
            for (col in index.columns) {
                if (col !in table.columns) {
                    val indexName = index.name ?: index.columns.joinToString(",")
                    errors += ValidationError(
                        "E005",
                        "Index '$indexName' references non-existent column '$col'",
                        "tables.$tableName.indices"
                    )
                }
            }
        }
    }

    // E016: Partition key must reference existing columns
    private fun validatePartitionKey(
        tableName: String, table: TableDefinition, errors: MutableList<ValidationError>
    ) {
        val partitioning = table.partitioning ?: return
        for (key in partitioning.key) {
            if (key !in table.columns) {
                errors += ValidationError(
                    "E016",
                    "Partition key references non-existent column '$key'",
                    "tables.$tableName.partitioning.key"
                )
            }
        }
    }

    // E012: Check expression references unknown column
    private fun validateCheckExpressionColumns(
        tableName: String, table: TableDefinition, errors: MutableList<ValidationError>
    ) {
        for (constraint in table.constraints) {
            if (constraint.type != ConstraintType.CHECK) continue
            val expr = constraint.expression ?: continue
            val referencedColumns = extractIdentifiers(expr)
            for (ref in referencedColumns) {
                if (ref !in table.columns) {
                    errors += ValidationError(
                        "E012",
                        "Check expression '${constraint.name}' references unknown column '$ref'",
                        "tables.$tableName.constraints.${constraint.name}"
                    )
                }
            }
        }
    }

    // E006, E013, E010, E011, E014, E015: Column type-specific validations
    private fun validateColumnType(
        path: String, col: ColumnDefinition, errors: MutableList<ValidationError>
    ) {
        when (val type = col.type) {
            is NeutralType.Enum -> {
                val hasValues = !type.values.isNullOrEmpty()
                val hasRefType = !type.refType.isNullOrBlank()
                if (!hasValues && !hasRefType) {
                    errors += ValidationError("E006", "Enum values must not be empty", path)
                }
                if (hasValues && hasRefType) {
                    errors += ValidationError(
                        "E013", "Enum: exactly one of ref_type or values required", path
                    )
                }
            }
            is NeutralType.Decimal -> {
                if (type.precision <= 0 || type.scale < 0) {
                    errors += ValidationError(
                        "E010", "precision and scale required for decimal type", path
                    )
                }
            }
            is NeutralType.Text -> {
                if (type.maxLength != null && type.maxLength <= 0) {
                    errors += ValidationError("E011", "max_length must be positive", path)
                }
            }
            is NeutralType.Char -> {
                if (type.length <= 0) {
                    errors += ValidationError(
                        "E014", "char: length is required and must be positive", path
                    )
                }
            }
            is NeutralType.Array -> {
                if (type.elementType.isBlank()) {
                    errors += ValidationError("E015", "array: element_type is required", path)
                } else if (type.elementType !in ARRAY_ELEMENT_TYPE_NAMES) {
                    errors += ValidationError("E015", "array: unknown element_type '${type.elementType}'", path)
                }
            }
            is NeutralType.Geometry -> {
                if (!type.geometryType.isKnown()) {
                    errors += ValidationError("E120", "Unknown geometry_type '${type.geometryType}'", path)
                }
                val srid = type.srid
                if (srid != null && srid <= 0) {
                    errors += ValidationError("E121", "srid must be greater than 0, got $srid", path)
                }
            }
            else -> { /* no type-specific validation */ }
        }
    }

    // E002: Foreign key references non-existent table
    private fun validateForeignKeyTableExists(
        path: String, col: ColumnDefinition, schema: SchemaDefinition,
        errors: MutableList<ValidationError>
    ) {
        val ref = col.references ?: return
        if (ref.table !in schema.tables) {
            errors += ValidationError(
                "E002",
                "Foreign key references non-existent table '${ref.table}'",
                "$path.references.table"
            )
        }
    }

    // E003: Foreign key references non-existent column
    private fun validateForeignKeyColumnExists(
        path: String, col: ColumnDefinition, schema: SchemaDefinition,
        errors: MutableList<ValidationError>
    ) {
        val ref = col.references ?: return
        val targetTable = schema.tables[ref.table] ?: return // E002 already covers missing table
        if (ref.column !in targetTable.columns) {
            errors += ValidationError(
                "E003",
                "Foreign key references non-existent column '${ref.table}.${ref.column}'",
                "$path.references.column"
            )
        }
    }

    // E017: Foreign key type incompatible with referenced column
    private fun validateForeignKeyTypeCompatibility(
        path: String, colName: String, col: ColumnDefinition, schema: SchemaDefinition,
        errors: MutableList<ValidationError>
    ) {
        val ref = col.references ?: return
        val targetTable = schema.tables[ref.table] ?: return
        val targetCol = targetTable.columns[ref.column] ?: return
        if (!isTypeCompatible(col.type, targetCol.type)) {
            errors += ValidationError(
                "E017",
                "Foreign key type incompatible with referenced column '${ref.table}.${ref.column}'",
                path
            )
        }
    }

    // E007: ref_type must exist in custom_types
    private fun validateRefTypeExists(
        path: String, col: ColumnDefinition, schema: SchemaDefinition,
        errors: MutableList<ValidationError>
    ) {
        val type = col.type
        if (type is NeutralType.Enum && !type.refType.isNullOrBlank()) {
            if (type.refType !in schema.customTypes) {
                errors += ValidationError(
                    "E007",
                    "ref_type '${type.refType}' references non-existent custom type",
                    path
                )
            }
        }
    }

    // E009: Default value incompatible with column type
    private fun validateDefaultTypeCompatibility(
        path: String, col: ColumnDefinition, errors: MutableList<ValidationError>
    ) {
        val default = col.default ?: return
        if (!isDefaultCompatible(default, col.type)) {
            errors += ValidationError(
                "E009", "Default value incompatible with column type", path
            )
        }
    }

    // W001: FLOAT used for potentially monetary column
    private fun checkFloatWarning(
        tableName: String, table: TableDefinition, warnings: MutableList<ValidationWarning>
    ) {
        val monetaryPatterns = setOf(
            "price", "amount", "cost", "total", "balance", "fee", "tax",
            "salary", "wage", "revenue", "profit", "discount", "payment"
        )
        for ((colName, col) in table.columns) {
            if (col.type is NeutralType.Float) {
                val lowerName = colName.lowercase()
                if (monetaryPatterns.any { lowerName.contains(it) }) {
                    warnings += ValidationWarning(
                        "W001",
                        "Column uses FLOAT — consider DECIMAL for monetary values",
                        "tables.$tableName.columns.$colName"
                    )
                }
            }
        }
    }

    private fun isTypeCompatible(source: NeutralType, target: NeutralType): Boolean {
        if (source == target) return true
        // identifier is compatible with integer/biginteger
        if (target is NeutralType.Identifier && source is NeutralType.Integer) return true
        if (target is NeutralType.Identifier && source is NeutralType.BigInteger) return true
        if (source is NeutralType.Integer && target is NeutralType.Identifier) return true
        if (source is NeutralType.BigInteger && target is NeutralType.Identifier) return true
        // integer ↔ biginteger
        if (source is NeutralType.Integer && target is NeutralType.BigInteger) return true
        if (source is NeutralType.BigInteger && target is NeutralType.Integer) return true
        return false
    }

    // Note: Geometry columns with defaults are not in 0.5.5 scope. A WKT string
    // default (e.g. "POINT(0 0)") would currently produce E009 because StringLiteral
    // is not compatible with Geometry. This is acceptable for now.
    private fun isDefaultCompatible(default: DefaultValue, type: NeutralType): Boolean = when (default) {
        is DefaultValue.StringLiteral -> type is NeutralType.Text || type is NeutralType.Char
                || type is NeutralType.Enum || type == NeutralType.Email || type is NeutralType.Uuid
        is DefaultValue.NumberLiteral -> type is NeutralType.Integer || type is NeutralType.SmallInt
                || type is NeutralType.BigInteger || type is NeutralType.Float
                || type is NeutralType.Decimal || type is NeutralType.Identifier
        is DefaultValue.BooleanLiteral -> type is NeutralType.BooleanType
        is DefaultValue.FunctionCall -> when (default.name) {
            "current_timestamp" -> type is NeutralType.DateTime || type is NeutralType.Date
                    || type is NeutralType.Time
            "gen_uuid" -> type is NeutralType.Uuid
            else -> true // unknown functions are allowed
        }
        is DefaultValue.SequenceNextVal -> type is NeutralType.Integer || type is NeutralType.SmallInt
                || type is NeutralType.BigInteger || type is NeutralType.Identifier
    }

    // E122: Legacy nextval(...) notation → migration error
    // E123: SequenceNextVal references non-existent sequence
    private fun validateSequenceDefaultReference(
        path: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        errors: MutableList<ValidationError>,
    ) {
        val default = col.default ?: return
        // E122: Legacy nextval(...) as FunctionCall
        if (default is DefaultValue.FunctionCall &&
            default.name.matches(Regex("""nextval\(.*\)""", RegexOption.IGNORE_CASE))
        ) {
            val seqName = default.name
                .removePrefix("nextval(").removePrefix("nextval('")
                .removeSuffix(")").removeSuffix("')")
                .removeSuffix("'")
            errors += ValidationError(
                "E122",
                "Legacy 'nextval(...)' notation is no longer supported. " +
                    "Replace with: default: { sequence_nextval: $seqName }  " +
                    "(before: default: \"${default.name}\" → after: default: { sequence_nextval: $seqName })",
                path,
            )
        }
        // E123: SequenceNextVal references non-existent sequence
        if (default is DefaultValue.SequenceNextVal) {
            val sequences = schema.sequences
            if (sequences == null || default.sequenceName !in sequences) {
                val available = sequences?.keys?.sorted()?.take(10)?.joinToString(", ") ?: "(none)"
                errors += ValidationError(
                    "E123",
                    "Sequence '${default.sequenceName}' referenced in default does not exist. " +
                        "Available sequences: $available",
                    path,
                )
            }
        }
    }

    // I1: Trigger table must reference existing table
    private fun validateTriggerTableReferences(
        schema: SchemaDefinition, errors: MutableList<ValidationError>
    ) {
        for ((triggerName, trigger) in schema.triggers) {
            if (trigger.table !in schema.tables) {
                errors += ValidationError(
                    "E018",
                    "Trigger references non-existent table '${trigger.table}'",
                    "triggers.$triggerName.table"
                )
            }
        }
    }

    // E020: Declared view dependency must reference an existing view
    private fun validateViewDependencies(
        schema: SchemaDefinition, errors: MutableList<ValidationError>
    ) {
        for ((viewName, view) in schema.views) {
            for (dependency in view.dependencies?.views.orEmpty()) {
                if (dependency !in schema.views) {
                    errors += ValidationError(
                        "E020",
                        "View dependency references non-existent view '$dependency'",
                        "views.$viewName.dependencies.views"
                    )
                }
            }
        }
    }

    private fun extractIdentifiers(expression: String): List<String> {
        // Strip single-quoted string literals before scanning to avoid false positives
        val stripped = expression.replace(Regex("'[^']*'"), "")
        val sqlKeywords = setOf(
            "AND", "OR", "NOT", "IN", "IS", "NULL", "TRUE", "FALSE",
            "BETWEEN", "LIKE", "ILIKE", "SIMILAR", "ANY", "ALL", "SOME",
            "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END",
            "CHECK", "VALUE", "CURRENT_TIMESTAMP",
            "ASC", "DESC", "HAVING", "OLD", "NEW"
        )
        return Regex("[a-zA-Z_][a-zA-Z0-9_]*")
            .findAll(stripped)
            .map { it.value }
            .filter { it.uppercase() !in sqlKeywords }
            .distinct()
            .toList()
    }
}
