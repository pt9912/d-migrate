package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.TypeMapper

internal class PostgresTypeSequenceDdlSupport(
    private val quoteIdentifier: (String) -> String,
    private val typeMapper: TypeMapper,
) {

    fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> =
        types.flatMap { (name, typeDef) -> generateCustomType(name, typeDef) }

    fun generateSequences(sequences: Map<String, SequenceDefinition>): List<DdlStatement> =
        sequences.map { (name, seq) -> generateSequence(name, seq) }

    private fun generateCustomType(name: String, typeDef: CustomTypeDefinition): List<DdlStatement> {
        return when (typeDef.kind) {
            CustomTypeKind.ENUM -> {
                val values = typeDef.values ?: return emptyList()
                val enumValues = values.joinToString(", ") { "'${it.replace("'", "''")}'" }
                listOf(DdlStatement("CREATE TYPE ${quoteIdentifier(name)} AS ENUM ($enumValues);"))
            }
            CustomTypeKind.COMPOSITE -> {
                val fields = typeDef.fields ?: return emptyList()
                // Composite-Felder in physischer Ordinalreihenfolge (ADR 0021: einheitlich
                // über alle DDL-Generate-Pfade), konsistent mit der Serialisierung.
                val fieldsSql = fields.inOrdinalOrder().joinToString(",\n    ") { (fieldName, col) ->
                    "${quoteIdentifier(fieldName)} ${typeMapper.toSql(col.type)}"
                }
                listOf(DdlStatement("CREATE TYPE ${quoteIdentifier(name)} AS (\n    $fieldsSql\n);"))
            }
            CustomTypeKind.DOMAIN -> {
                val baseType = typeDef.baseType ?: return emptyList()
                val sqlType = domainBaseTypeSql(baseType, typeDef.precision, typeDef.scale)
                val sql = buildString {
                    append("CREATE DOMAIN ${quoteIdentifier(name)} AS $sqlType")
                    if (typeDef.check != null) {
                        append(" CHECK (${typeDef.check})")
                    }
                    append(";")
                }
                listOf(DdlStatement(sql))
            }
        }
    }

    /**
     * Rendert den Domain-Basistyp über denselben Typ-Mapper wie Spalten/Composite-Felder,
     * wenn [baseType] ein neutraler Typname ist (Reverse-Seite speichert z. B. "biginteger").
     * Rohe SQL-Typstrings (z. B. handgeschriebenes "VARCHAR(254)") werden unverändert
     * durchgereicht — sie sind bereits gültiges DDL.
     */
    private fun domainBaseTypeSql(baseType: String, precision: Int?, scale: Int?): String {
        val neutral = resolveNeutralBaseType(baseType, precision, scale)
        if (neutral != null) return typeMapper.toSql(neutral)
        return buildString {
            append(baseType.uppercase())
            if (precision != null) {
                append("($precision")
                if (scale != null) append(",$scale")
                append(")")
            }
        }
    }

    private fun resolveNeutralBaseType(baseType: String, precision: Int?, scale: Int?): NeutralType? =
        when (baseType.lowercase()) {
            "integer", "int", "int4" -> NeutralType.Integer
            "biginteger", "bigint", "int8" -> NeutralType.BigInteger
            "smallint", "int2" -> NeutralType.SmallInt
            "boolean", "bool" -> NeutralType.BooleanType
            "float", "real", "double precision", "float4", "float8" -> NeutralType.Float()
            "decimal", "numeric" ->
                if (precision != null) NeutralType.Decimal(precision, scale ?: 0) else null
            "text" -> NeutralType.Text()
            "uuid" -> NeutralType.Uuid
            "json", "jsonb" -> NeutralType.Json
            "xml" -> NeutralType.Xml
            "binary", "bytea" -> NeutralType.Binary
            "date" -> NeutralType.Date
            "time" -> NeutralType.Time
            else -> null
        }

    private fun generateSequence(name: String, seq: SequenceDefinition): DdlStatement {
        val sql = buildString {
            append("CREATE SEQUENCE ${quoteIdentifier(name)}")
            append(" START WITH ${seq.start}")
            append(" INCREMENT BY ${seq.increment}")
            if (seq.minValue != null) append(" MINVALUE ${seq.minValue}")
            if (seq.maxValue != null) append(" MAXVALUE ${seq.maxValue}")
            if (seq.cycle) append(" CYCLE") else append(" NO CYCLE")
            if (seq.cache != null) append(" CACHE ${seq.cache}")
            append(";")
        }
        return DdlStatement(sql)
    }
}
