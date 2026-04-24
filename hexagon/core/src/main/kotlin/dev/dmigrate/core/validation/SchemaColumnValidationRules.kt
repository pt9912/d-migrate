package dev.dmigrate.core.validation

import dev.dmigrate.core.model.*

internal object SchemaColumnValidationRules {

    fun validate(
        path: String,
        column: ColumnDefinition,
        schema: SchemaDefinition,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        validateColumnType(path, column, errors)
        validateForeignKeyTableExists(path, column, schema, errors)
        validateForeignKeyColumnExists(path, column, schema, errors)
        validateForeignKeyTypeCompatibility(path, column, schema, errors)
        validateRefTypeExists(path, column, schema, errors)
        validateDefaultTypeCompatibility(path, column, errors)
        validateSequenceDefaultReference(path, column, schema, errors)
        return errors
    }

    private fun validateColumnType(
        path: String,
        column: ColumnDefinition,
        errors: MutableList<ValidationError>,
    ) {
        when (val type = column.type) {
            is NeutralType.Enum -> {
                val hasValues = !type.values.isNullOrEmpty()
                val hasRefType = !type.refType.isNullOrBlank()
                if (!hasValues && !hasRefType) {
                    errors += ValidationError("E006", "Enum values must not be empty", path)
                }
                if (hasValues && hasRefType) {
                    errors += ValidationError(
                        "E013",
                        "Enum: exactly one of ref_type or values required",
                        path,
                    )
                }
            }
            is NeutralType.Decimal -> {
                if (type.precision <= 0 || type.scale < 0) {
                    errors += ValidationError(
                        "E010",
                        "precision and scale required for decimal type",
                        path,
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
                        "E014",
                        "char: length is required and must be positive",
                        path,
                    )
                }
            }
            is NeutralType.Array -> {
                if (type.elementType.isBlank()) {
                    errors += ValidationError("E015", "array: element_type is required", path)
                } else if (type.elementType !in SchemaValidator.ARRAY_ELEMENT_TYPE_NAMES) {
                    errors += ValidationError(
                        "E015",
                        "array: unknown element_type '${type.elementType}'",
                        path,
                    )
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
            else -> Unit
        }
    }

    private fun validateForeignKeyTableExists(
        path: String,
        column: ColumnDefinition,
        schema: SchemaDefinition,
        errors: MutableList<ValidationError>,
    ) {
        val reference = column.references ?: return
        if (reference.table !in schema.tables) {
            errors += ValidationError(
                "E002",
                "Foreign key references non-existent table '${reference.table}'",
                "$path.references.table",
            )
        }
    }

    private fun validateForeignKeyColumnExists(
        path: String,
        column: ColumnDefinition,
        schema: SchemaDefinition,
        errors: MutableList<ValidationError>,
    ) {
        val reference = column.references ?: return
        val targetTable = schema.tables[reference.table] ?: return
        if (reference.column !in targetTable.columns) {
            errors += ValidationError(
                "E003",
                "Foreign key references non-existent column '${reference.table}.${reference.column}'",
                "$path.references.column",
            )
        }
    }

    private fun validateForeignKeyTypeCompatibility(
        path: String,
        column: ColumnDefinition,
        schema: SchemaDefinition,
        errors: MutableList<ValidationError>,
    ) {
        val reference = column.references ?: return
        val targetTable = schema.tables[reference.table] ?: return
        val targetColumn = targetTable.columns[reference.column] ?: return
        if (!isTypeCompatible(column.type, targetColumn.type)) {
            errors += ValidationError(
                "E017",
                "Foreign key type incompatible with referenced column '${reference.table}.${reference.column}'",
                path,
            )
        }
    }

    private fun validateRefTypeExists(
        path: String,
        column: ColumnDefinition,
        schema: SchemaDefinition,
        errors: MutableList<ValidationError>,
    ) {
        val type = column.type
        if (type is NeutralType.Enum && !type.refType.isNullOrBlank() && type.refType !in schema.customTypes) {
            errors += ValidationError(
                "E007",
                "ref_type '${type.refType}' references non-existent custom type",
                path,
            )
        }
    }

    private fun validateDefaultTypeCompatibility(
        path: String,
        column: ColumnDefinition,
        errors: MutableList<ValidationError>,
    ) {
        val default = column.default ?: return
        if (!isDefaultCompatible(default, column.type)) {
            errors += ValidationError(
                "E009",
                "Default value incompatible with column type",
                path,
            )
        }
    }

    private fun validateSequenceDefaultReference(
        path: String,
        column: ColumnDefinition,
        schema: SchemaDefinition,
        errors: MutableList<ValidationError>,
    ) {
        val default = column.default ?: return
        if (default is DefaultValue.FunctionCall &&
            default.name.matches(Regex("""nextval\(.*\)""", RegexOption.IGNORE_CASE))
        ) {
            val sequenceName = Regex("""nextval\(\s*'?([^')]+)'?\s*\)""", RegexOption.IGNORE_CASE)
                .find(default.name)?.groupValues?.get(1)?.trim() ?: default.name
            errors += ValidationError(
                "E122",
                "Legacy 'nextval(...)' notation is no longer supported. " +
                    "Replace with: default: { sequence_nextval: $sequenceName }  " +
                    "(before: default: \"${default.name}\" → after: default: { sequence_nextval: $sequenceName })",
                path,
            )
        }

        if (default is DefaultValue.SequenceNextVal) {
            val sequences = schema.sequences
            if (default.sequenceName !in sequences) {
                val available = sequences.keys.sorted().take(10).joinToString(", ").ifBlank { "(none)" }
                errors += ValidationError(
                    "E123",
                    "Sequence '${default.sequenceName}' referenced in default does not exist. " +
                        "Available sequences: $available",
                    path,
                )
            }
        }
    }

    private fun isTypeCompatible(source: NeutralType, target: NeutralType): Boolean {
        if (source == target) return true
        if (target is NeutralType.Identifier && source is NeutralType.Integer) return true
        if (target is NeutralType.Identifier && source is NeutralType.BigInteger) return true
        if (source is NeutralType.Integer && target is NeutralType.Identifier) return true
        if (source is NeutralType.BigInteger && target is NeutralType.Identifier) return true
        if (source is NeutralType.Integer && target is NeutralType.BigInteger) return true
        if (source is NeutralType.BigInteger && target is NeutralType.Integer) return true
        return false
    }

    private fun isDefaultCompatible(default: DefaultValue, type: NeutralType): Boolean = when (default) {
        is DefaultValue.StringLiteral -> isStringDefaultCompatible(type)
        is DefaultValue.NumberLiteral -> isNumericDefaultCompatible(type)
        is DefaultValue.BooleanLiteral -> type is NeutralType.BooleanType
        is DefaultValue.FunctionCall -> isFunctionDefaultCompatible(default, type)
        is DefaultValue.SequenceNextVal -> isSequenceDefaultCompatible(type)
    }

    private fun isStringDefaultCompatible(type: NeutralType): Boolean =
        type is NeutralType.Text || type is NeutralType.Char ||
            type is NeutralType.Enum || type == NeutralType.Email || type is NeutralType.Uuid

    private fun isNumericDefaultCompatible(type: NeutralType): Boolean =
        type is NeutralType.Integer || type is NeutralType.SmallInt ||
            type is NeutralType.BigInteger || type is NeutralType.Float ||
            type is NeutralType.Decimal || type is NeutralType.Identifier

    private fun isFunctionDefaultCompatible(
        default: DefaultValue.FunctionCall,
        type: NeutralType,
    ): Boolean = when (default.name) {
        "current_timestamp" -> type is NeutralType.DateTime || type is NeutralType.Date || type is NeutralType.Time
        "gen_uuid" -> type is NeutralType.Uuid
        else -> true
    }

    private fun isSequenceDefaultCompatible(type: NeutralType): Boolean =
        (type is NeutralType.Integer || type is NeutralType.SmallInt ||
            type is NeutralType.BigInteger || type is NeutralType.Identifier) &&
            !(type is NeutralType.Identifier && type.autoIncrement)
}
