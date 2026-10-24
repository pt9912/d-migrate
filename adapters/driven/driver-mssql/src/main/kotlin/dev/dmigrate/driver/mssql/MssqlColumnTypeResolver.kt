package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.metadata.DomainTypes

/**
 * Typauflösung jenseits des reinen [MssqlTypeMapper]: Domain-Basistypen
 * (neutrale Namen und PostgreSQL-Katalognamen), die LOB-Eigenschaft einer
 * Spalte (auch über Enum-`refType`/Domain) und die `VALUE`-Ersetzung in
 * Domain-CHECKs. Geteilt von Spalten- und Index-Pfad.
 */
internal class MssqlColumnTypeResolver(private val typeMapper: MssqlTypeMapper) {

    /** Spalten, die als `NVARCHAR(MAX)`/`VARBINARY(MAX)`/`XML` gerendert werden (keine Schlüsselspalten). */
    fun lobColumns(table: TableDefinition, schema: SchemaDefinition): Set<String> =
        table.columns.filterValues { isLobColumn(it, schema) }.keys

    fun isLobColumn(col: ColumnDefinition, schema: SchemaDefinition): Boolean {
        val type = col.type
        if (type !is NeutralType.Enum) return typeMapper.isLargeObject(type)
        val refType = type.refType
        if (refType != null) {
            val customType = schema.customTypes[refType] ?: return true
            return when (customType.kind) {
                CustomTypeKind.DOMAIN -> isLobDomain(customType)
                else -> customType.values == null
            }
        }
        return type.values == null
    }

    private fun isLobDomain(customType: CustomTypeDefinition): Boolean {
        val neutral = resolveDomainBaseType(customType.baseType ?: "text", customType.precision, customType.scale)
            ?: return true
        return typeMapper.isLargeObject(neutral)
    }

    /** Siehe [DomainTypes.resolveBaseType]; `null` heisst E053 beim Aufrufer. */
    fun resolveDomainBaseType(baseType: String, precision: Int?, scale: Int?): NeutralType? =
        DomainTypes.resolveBaseType(baseType, precision, scale)

    /** Siehe [DomainTypes.substituteValueToken]. */
    fun substituteValueToken(check: String, replacement: String): String =
        DomainTypes.substituteValueToken(check, replacement)
}
