package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

/**
 * Stateless SQL fragment builders for the Oracle diff renderer. Thin on
 * purpose: unlike PostgreSQL, Oracle's Diff path reuses the Generate-path
 * helpers ([OracleColumnConstraintHelper], [OracleIndexDdlBuilder]) for
 * whole-column/whole-index rendering instead of re-implementing them here --
 * this file only wraps the primitives those helpers don't cover
 * (identifier quoting, bare type/default rendering for `ALTER ... MODIFY`).
 */
internal class OracleDiffSqlBuilders(private val typeMapper: OracleTypeMapper) {

    fun quote(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.ORACLE)

    fun toSql(type: NeutralType): String = typeMapper.toSql(type)

    fun toDefaultSql(default: DefaultValue, type: NeutralType): String = typeMapper.toDefaultSql(default, type)
}
