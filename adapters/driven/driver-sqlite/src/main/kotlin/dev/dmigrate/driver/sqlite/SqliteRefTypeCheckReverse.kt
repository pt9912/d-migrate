package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.EnumCheckProjection
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType

/**
 * Macht aus dem unbenannten CHECK eines `ref_type`-Enums (Wertevorrat einer
 * Spalte, so schreibt ihn [SqliteRefTypeColumns]) einen `ConstraintDefinition`
 * mit synthetischem Namen.
 *
 * Das Modell schluesselt Constraints nach Namen; ein unbenannter CHECK ging
 * bisher als `R203` verloren, und ein Wertevorrat, der sich gegen eine echte
 * Datenbank aendert, blieb fuer den Planer unsichtbar. Der synthetische
 * Constraint faltet [EnumCheckProjection] auf der Ist-Seite zum Wertevorrat der
 * Spalte — formbasiert, der Name zaehlt dort nicht.
 *
 * Nur mit `SchemaReadOptions.sqliteRefTypeChecks` (Migrate und Rollback). Ein
 * Constraint entsteht nur, wenn die Spalte **genau einen** unbenannten,
 * Wertelisten-foermigen CHECK traegt und der Name frei ist; sonst bleibt es bei
 * `R203`. d-migrate selbst schreibt genau einen, mehrere sind handgeschrieben.
 */
internal object SqliteRefTypeCheckReverse {

    /** Fester Namenspraefix; `ck_<tabelle>_<spalte>` ist der Name des Inline-Enum-CHECK. */
    const val NAME_PREFIX = "__dm_enum_"

    fun name(table: String, column: String): String = "$NAME_PREFIX${table}_$column"

    fun isSynthetic(constraintName: String): Boolean = constraintName.startsWith(NAME_PREFIX)

    /**
     * Die Spalte, deren Wertevorrat [constraint] aufzaehlt -- `null`, wenn er
     * nicht synthetisch ist (exakt der Name, den [extract] vergibt) oder keine
     * Spalte der Tabelle trifft.
     */
    fun columnOf(table: String, constraint: ConstraintDefinition, columns: Collection<String>): String? =
        columns.firstOrNull {
            constraint.name == name(table, it) && EnumCheckProjection.valuesOf(constraint.expression, it) != null
        }

    data class Result(
        val constraints: List<ConstraintDefinition>,
        /** Die unbenannten CHECKs, die kein synthetischer Constraint wurden (weiter `R203`). */
        val remaining: List<String>,
    )

    fun extract(
        table: String,
        columns: Collection<String>,
        unnamedExpressions: List<String>,
        takenNames: Set<String>,
    ): Result {
        val matches = unnamedExpressions.mapNotNull { expr ->
            columns.firstOrNull { EnumCheckProjection.valuesOf(expr, it) != null }?.let { it to expr }
        }
        val perColumn = matches.groupBy({ it.first }, { it.second })
        val constraints = mutableListOf<ConstraintDefinition>()
        val consumed = mutableListOf<String>()
        for ((column, expressions) in perColumn) {
            val constraintName = name(table, column)
            if (expressions.size != 1 || constraintName in takenNames) continue
            constraints += ConstraintDefinition(
                name = constraintName,
                type = ConstraintType.CHECK,
                expression = expressions.single(),
            )
            consumed += expressions.single()
        }
        val remaining = unnamedExpressions.toMutableList()
        consumed.forEach { remaining.remove(it) }
        return Result(constraints, remaining)
    }
}
