package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType

/**
 * MySQL requires an AUTO_INCREMENT column to be the leading column of a key
 * (ERROR 1075). Single source of truth for that ordering, shared by the
 * full-schema generator ([MysqlDdlGenerator]) and the diff CREATE TABLE renderer
 * ([MysqlDiffTableOps]) so both paths agree on what counts as AUTO_INCREMENT.
 */
internal object MysqlPrimaryKeyOrdering {

    data class Result(val columns: List<String>, val reordered: String?)

    /**
     * Reorders [primaryKey] so an AUTO_INCREMENT column leads. [Result.reordered]
     * is the moved column's name, or null when no reordering was needed.
     */
    fun autoIncrementFirst(primaryKey: List<String>, columns: Map<String, ColumnDefinition>): Result {
        if (primaryKey.size < 2) return Result(primaryKey, null)
        val autoInc = primaryKey.firstOrNull { columns[it]?.let(::isAutoIncrement) == true }
            ?: return Result(primaryKey, null)
        if (primaryKey.first() == autoInc) return Result(primaryKey, null)
        return Result(listOf(autoInc) + primaryKey.filterNot { it == autoInc }, autoInc)
    }

    /** True when [col] renders as an AUTO_INCREMENT column in MySQL DDL. */
    fun isAutoIncrement(col: ColumnDefinition): Boolean =
        (col.generation is ColumnGeneration.Identity && supportsIdentityGeneration(col.type)) ||
            (col.type is NeutralType.Identifier && (col.type as NeutralType.Identifier).autoIncrement)

    /** Identity generation maps to AUTO_INCREMENT only for INT/BIGINT in MySQL. */
    fun supportsIdentityGeneration(type: NeutralType): Boolean =
        type is NeutralType.Integer || type is NeutralType.BigInteger
}
