package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.PreGenerationValidator

/**
 * oracle-doppelter-generierungsausdruck.md: faengt beide gemessenen
 * `CREATE TABLE`-Ablehnungen **vor** der ersten Anweisung, statt Oracle
 * mitten im Lauf scheitern zu lassen (jedes DDL committet dort implizit —
 * vorige Anweisungen blieben angewandt). Siehe
 * [OracleComputedExpressionDuplication] fuer die gemessene Grundlage.
 */
internal object OraclePreGenerationValidator : PreGenerationValidator {
    override fun validate(schema: SchemaDefinition, options: DdlGenerationOptions): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((tableName, table) in schema.tables) {
            for (group in OracleComputedExpressionDuplication.duplicateGroups(table)) {
                errors += ValidationError(
                    code = OracleComputedExpressionDuplication.DUPLICATE_EXPRESSION,
                    message = "Computed columns ${group.joinToString { "'$it'" }} of table '$tableName' share " +
                        "the same expression text; Oracle refuses that with ORA-54015 (\"Duplicate column " +
                        "expression was specified\", measured against 23). Give each column a distinct " +
                        "expression, or drop the duplicate.",
                    objectPath = "$tableName.${group.joinToString(",")}",
                )
            }
            for ((columnName, column) in table.columns) {
                val computed = column.generation as? ColumnGeneration.Computed ?: continue
                if (!OracleComputedExpressionDuplication.collidesWithExpressionIndex(
                        table, columnName, computed.expression,
                    )
                ) {
                    continue
                }
                errors += ValidationError(
                    code = OracleComputedExpressionDuplication.EXPRESSION_INDEX_COLLISION,
                    message = "Table '$tableName' has both an expression index over '${computed.expression}' " +
                        "and a plain index on the computed column '$columnName', which carries the same " +
                        "expression. Oracle treats both as the same index and refuses the second one with " +
                        "ORA-01408 (\"such column list already indexed\", measured against 23). Drop one of " +
                        "the two indexes.",
                    objectPath = "$tableName.$columnName",
                )
            }
        }
        return errors
    }
}
