package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.SequenceDefinition
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
                val fieldsSql = fields.entries.joinToString(",\n    ") { (fieldName, col) ->
                    "${quoteIdentifier(fieldName)} ${typeMapper.toSql(col.type)}"
                }
                listOf(DdlStatement("CREATE TYPE ${quoteIdentifier(name)} AS (\n    $fieldsSql\n);"))
            }
            CustomTypeKind.DOMAIN -> {
                val baseType = typeDef.baseType ?: return emptyList()
                val sqlType = buildString {
                    append(baseType.uppercase())
                    if (typeDef.precision != null) {
                        append("(${typeDef.precision}")
                        if (typeDef.scale != null) append(",${typeDef.scale}")
                        append(")")
                    }
                }
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
