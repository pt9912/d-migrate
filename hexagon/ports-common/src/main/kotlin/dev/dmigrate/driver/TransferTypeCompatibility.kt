package dev.dmigrate.driver

import dev.dmigrate.core.model.NeutralType

/**
 * Decides whether data of a source column type can be transferred into a target
 * column type **for a specific target dialect**. Provided by the target
 * [DatabaseDriver] (`transferCompatibility()`) so the decision is derived from the
 * dialect's own type mapping rather than a hand-maintained case list.
 */
fun interface TransferTypeCompatibility {
    fun isCompatible(source: NeutralType, target: NeutralType): Boolean
}

/**
 * Structural transfer compatibility: source and target are compatible when the
 * **target dialect's** [TypeMapper] maps both to the same (normalised) SQL
 * storage type. Because the target column type is itself the tool's generated
 * mapping, every tool-own cross-dialect mapping (e.g. PG `array`→MySQL `JSON`,
 * `decimal`→SQLite `REAL`, `enum`→`TEXT`, `timestamptz`→`DATETIME`) is covered
 * by construction — no per-mapping rule needed.
 */
class StructuralTransferTypeCompatibility(private val typeMapper: TypeMapper) : TransferTypeCompatibility {

    override fun isCompatible(source: NeutralType, target: NeutralType): Boolean {
        if (source == target) return true
        // Bounded, dialect-agnostic allowance: integer storage classes are mutually
        // transfer-compatible (widening). The target dialect's SQL spellings differ
        // (SMALLINT/INT/BIGINT on PG/MySQL), so the structural check below cannot
        // see this; range is a value-level concern, not a type-shape one.
        if (isIntegral(source) && isIntegral(target)) return true
        // Likewise timezone variance between two timestamps is value-level, not a
        // type-shape mismatch (PG spells them TIMESTAMP vs TIMESTAMPTZ).
        if (source is NeutralType.DateTime && target is NeutralType.DateTime) return true
        return normalize(typeMapper.toSql(source)) == normalize(typeMapper.toSql(target))
    }

    private fun isIntegral(type: NeutralType): Boolean =
        type is NeutralType.SmallInt || type is NeutralType.Integer ||
            type is NeutralType.BigInteger || type is NeutralType.Identifier

    /**
     * Reduces a dialect SQL type to its storage family: drops length/precision
     * (`VARCHAR(50)` → `VARCHAR`) and folds the string-type spellings into one
     * token, so `VARCHAR`/`CHAR`/`TEXT` count as the same storage.
     */
    private fun normalize(sqlType: String): String {
        val base = sqlType.substringBefore('(').trim().uppercase()
        return if (base in TEXT_SQL_TYPES) "TEXT" else base
    }

    private companion object {
        private val TEXT_SQL_TYPES = setOf(
            "TEXT", "VARCHAR", "VARCHAR2", "CHAR", "CHARACTER", "CHARACTER VARYING",
            "NVARCHAR", "NCHAR", "CLOB", "LONGTEXT", "MEDIUMTEXT", "TINYTEXT",
        )
    }
}
