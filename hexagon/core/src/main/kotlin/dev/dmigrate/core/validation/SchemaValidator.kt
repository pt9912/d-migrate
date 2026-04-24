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
        val structureResult = SchemaStructureValidationRules.validate(schema)
        val columnErrors = mutableListOf<ValidationError>()
        for ((tableName, table) in schema.tables) {
            for ((colName, col) in table.columns) {
                val path = "tables.$tableName.columns.$colName"
                columnErrors += SchemaColumnValidationRules.validate(path, col, schema)
            }
        }
        return ValidationResult(
            errors = structureResult.errors + columnErrors,
            warnings = structureResult.warnings,
        )
    }
}
