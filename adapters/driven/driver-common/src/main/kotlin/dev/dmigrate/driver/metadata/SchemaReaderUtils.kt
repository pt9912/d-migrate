package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.*

/**
 * Shared utility functions for SchemaReader implementations.
 *
 * Extracted from the three dialect-specific readers to eliminate
 * code duplication. These are pure functions operating on the
 * typed metadata projections — no JDBC, no connections.
 */
object SchemaReaderUtils {

    /**
     * Maps a referential action string (CASCADE, SET NULL, etc.)
     * to the neutral [ReferentialAction] enum. Returns null for
     * NO ACTION or unknown values.
     */
    fun toReferentialAction(action: String?): ReferentialAction? = when (action?.uppercase()) {
        "CASCADE" -> ReferentialAction.CASCADE
        "SET NULL" -> ReferentialAction.SET_NULL
        "SET DEFAULT" -> ReferentialAction.SET_DEFAULT
        "RESTRICT" -> ReferentialAction.RESTRICT
        "NO ACTION" -> ReferentialAction.NO_ACTION
        else -> null
    }

    /**
     * Lifts single-column foreign keys to column-level [ReferenceDefinition]s.
     * Returns a map from column name to its reference.
     */
    fun liftSingleColumnFks(fks: List<ForeignKeyProjection>): Map<String, ReferenceDefinition> =
        fks.filter { it.columns.size == 1 && it.referencedColumns.size == 1 }
            .associate { fk ->
                fk.columns[0] to ReferenceDefinition(
                    table = fk.referencedTable,
                    column = fk.referencedColumns[0],
                    onDelete = toReferentialAction(fk.onDelete),
                    onUpdate = toReferentialAction(fk.onUpdate),
                )
            }

    /**
     * Builds FK constraints from foreign key projections.
     *
     * Reverse output keeps FK names at table constraint level even for
     * single-column FKs. Column-level references remain supported for
     * hand-written schemas, but database metadata models FKs as named
     * constraints.
     */
    fun buildForeignKeyConstraints(fks: List<ForeignKeyProjection>): List<ConstraintDefinition> =
        fks.map { fk ->
            ConstraintDefinition(
                name = fk.name,
                type = ConstraintType.FOREIGN_KEY,
                columns = fk.columns,
                references = ConstraintReferenceDefinition(
                    table = fk.referencedTable,
                    columns = fk.referencedColumns,
                    onDelete = toReferentialAction(fk.onDelete),
                    onUpdate = toReferentialAction(fk.onUpdate),
                ),
            )
        }

    /**
     * Builds multi-column FK constraints from foreign key projections.
     * Only includes FKs with more than one column.
     */
    fun buildMultiColumnFkConstraints(fks: List<ForeignKeyProjection>): List<ConstraintDefinition> =
        buildForeignKeyConstraints(fks).filter { (it.columns?.size ?: 0) > 1 }

    /**
     * Builds multi-column UNIQUE constraints from unique constraint
     * column groups (PostgreSQL style: constraint name → column list).
     */
    fun buildMultiColumnUniqueFromConstraints(
        uniqueConstraints: Map<String, List<String>>,
    ): List<ConstraintDefinition> =
        uniqueConstraints.filter { it.value.size > 1 }.map { (name, cols) ->
            ConstraintDefinition(name = name, type = ConstraintType.UNIQUE, columns = cols)
        }

    /**
     * Builds multi-column UNIQUE constraints from index projections
     * (MySQL/SQLite style: unique indices with >1 column).
     */
    fun buildMultiColumnUniqueFromIndices(
        indices: List<IndexProjection>,
    ): List<ConstraintDefinition> =
        indices.filter { it.isUnique && it.columns.size > 1 && it.where == null }.map { idx ->
            ConstraintDefinition(name = idx.name, type = ConstraintType.UNIQUE, columns = idx.columns)
        }

    /**
     * Builds CHECK constraints from constraint projections.
     */
    fun buildCheckConstraints(checks: List<ConstraintProjection>): List<ConstraintDefinition> =
        checks.map { check ->
            ConstraintDefinition(
                name = check.name,
                type = ConstraintType.CHECK,
                expression = check.expression,
            )
        }

    /**
     * Extracts single-column unique column names from index projections
     * (MySQL/SQLite pattern).
     */
    fun singleColumnUniqueFromIndices(indices: List<IndexProjection>): Set<String> =
        indices.filter { it.isUnique && it.columns.size == 1 && it.where == null }
            .map { it.columns[0] }.toSet()

    /**
     * Extracts single-column unique column names from named unique
     * constraint groups (PostgreSQL pattern).
     */
    fun singleColumnUniqueFromConstraints(
        uniqueConstraints: Map<String, List<String>>,
    ): Set<String> =
        uniqueConstraints.values.filter { it.size == 1 }.map { it[0] }.toSet()

    private val PAREN_LENGTH = Regex("""\((\d+)\)""")
    private val PAREN_PRECISION_SCALE = Regex("""\((\d+)\s*,\s*(\d+)\)""")

    /**
     * The single numeric length out of a parenthesised type spelling
     * (`VARCHAR(50)` → 50), or null when absent or two-valued.
     */
    fun parenLength(raw: String): Int? =
        if (PAREN_PRECISION_SCALE.containsMatchIn(raw)) null
        else PAREN_LENGTH.find(raw)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The precision/scale pair out of a parenthesised type spelling
     * (`DECIMAL(10,2)` → 10 to 2), or null/null when absent.
     */
    fun parenPrecisionScale(raw: String): Pair<Int?, Int?> {
        val match = PAREN_PRECISION_SCALE.find(raw) ?: return null to null
        return match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
    }
}
