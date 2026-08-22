package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition

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

    /**
     * Neutrale Typnamen (wie sie der Reverse in `base_type` ablegt) und die
     * gängigen PostgreSQL-Katalognamen, die der PG-Reverse roh durchreicht.
     * `null`, wenn kein T-SQL-Mapping existiert (Aufrufer: E053, kein Roh-Durchreichen).
     */
    fun resolveDomainBaseType(baseType: String, precision: Int?, scale: Int?): NeutralType? {
        // Hand-geschriebene Formen `VARCHAR(20)` / `DECIMAL(10,2)`: Klammerwerte
        // zaehlen, wenn keine expliziten precision/scale am Typ stehen.
        val match = TYPE_WITH_PARAMS.matchEntire(baseType.trim())
        val name = match?.groupValues?.get(1) ?: baseType
        val parsedPrecision = match?.groupValues?.get(2)?.toIntOrNull()
        val parsedScale = match?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        return resolveBaseTypeName(name, precision ?: parsedPrecision, scale ?: parsedScale)
    }

    private fun resolveBaseTypeName(baseType: String, precision: Int?, scale: Int?): NeutralType? =
        when (baseType.lowercase().trim()) {
            "integer", "int", "int4" -> NeutralType.Integer
            "biginteger", "bigint", "int8" -> NeutralType.BigInteger
            "smallint", "int2" -> NeutralType.SmallInt
            "boolean", "bool" -> NeutralType.BooleanType
            "real", "float4" -> NeutralType.Float(FloatPrecision.SINGLE)
            // Neutrales `float` ist double (Schema-Default float_precision=double).
            "float", "double precision", "float8", "double" -> NeutralType.Float()
            "decimal", "numeric" -> if (precision != null) NeutralType.Decimal(precision, scale ?: 0) else null
            "text", "character varying", "varchar" -> if (precision != null) NeutralType.Text(precision) else NeutralType.Text()
            "char", "character" -> NeutralType.Char(precision ?: 1)
            "uuid" -> NeutralType.Uuid
            "json", "jsonb" -> NeutralType.Json
            "xml" -> NeutralType.Xml
            "binary", "bytea" -> NeutralType.Binary
            "date" -> NeutralType.Date
            "time", "time without time zone" -> NeutralType.Time
            "datetime", "timestamp", "timestamp without time zone" -> NeutralType.DateTime()
            "timestamptz", "timestamp with time zone" -> NeutralType.DateTime(timezone = true)
            else -> null
        }

    /** Ersetzt das Wort `VALUE` außerhalb von `'…'`-Literalen (`''` = Escape). */
    fun substituteValueToken(check: String, replacement: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < check.length) {
            if (check[index] == '\'') {
                val end = endOfStringLiteral(check, index)
                out.append(check, index, end)
                index = end
            } else {
                val next = check.indexOf('\'', index).let { if (it < 0) check.length else it }
                out.append(DOMAIN_VALUE_TOKEN.replace(check.substring(index, next), replacement))
                index = next
            }
        }
        return out.toString()
    }

    private fun endOfStringLiteral(value: String, openQuote: Int): Int {
        var index = openQuote + 1
        while (index < value.length) {
            index += when {
                value[index] != '\'' -> 1
                index + 1 < value.length && value[index + 1] == '\'' -> 2
                else -> return index + 1
            }
        }
        return index
    }

    private companion object {
        val DOMAIN_VALUE_TOKEN = Regex("""(?i)\bVALUE\b""")
        val TYPE_WITH_PARAMS = Regex("""^([A-Za-z][A-Za-z0-9_ ]*?)\s*\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)$""")
    }
}
