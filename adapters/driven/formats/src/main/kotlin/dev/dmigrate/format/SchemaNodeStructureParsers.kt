package dev.dmigrate.format

import com.fasterxml.jackson.databind.JsonNode
import dev.dmigrate.core.model.*

internal fun parseTables(node: JsonNode?): Map<String, TableDefinition> =
    parseNamedObjectMap(node, ::parseTable)

private fun parseTable(node: JsonNode): TableDefinition = TableDefinition(
    description = node.optionalText("description"),
    columns = parseColumns(node["columns"]),
    primaryKey = node["primary_key"]?.toStringList() ?: emptyList(),
    indices = parseIndices(node["indices"]),
    constraints = parseConstraints(node["constraints"]),
    partitioning = parsePartitioning(node["partitioning"]),
    metadata = parseTableMetadata(node["metadata"]),
)

private fun parseTableMetadata(node: JsonNode?): TableMetadata? {
    if (node == null || !node.isObject) return null
    return TableMetadata(
        engine = node.optionalText("engine"),
        withoutRowid = node.boolOrDefault("without_rowid", false),
    )
}

internal fun parseColumns(node: JsonNode?): Map<String, ColumnDefinition> =
    parseNamedObjectMap(node, ::parseColumn)

private fun parseColumn(node: JsonNode): ColumnDefinition = ColumnDefinition(
    type = parseNeutralType(node),
    required = node.boolOrDefault("required", false),
    unique = node.boolOrDefault("unique", false),
    default = parseDefault(node["default"]),
    references = parseReference(node["references"]),
)

private fun parseNeutralType(node: JsonNode): NeutralType {
    val typeName = node.requiredText("type")
    return when (typeName) {
        "identifier" -> NeutralType.Identifier(
            autoIncrement = node.boolOrDefault("auto_increment", false)
        )
        "text" -> NeutralType.Text(maxLength = node.optionalInt("max_length"))
        "char" -> NeutralType.Char(length = node.requiredInt("length"))
        "integer" -> NeutralType.Integer
        "smallint" -> NeutralType.SmallInt
        "biginteger" -> NeutralType.BigInteger
        "float" -> NeutralType.Float(
            floatPrecision = when (node.optionalText("float_precision")) {
                "single" -> FloatPrecision.SINGLE
                else -> FloatPrecision.DOUBLE
            }
        )
        "decimal" -> NeutralType.Decimal(
            precision = node.requiredInt("precision"),
            scale = node.requiredInt("scale"),
        )
        "boolean" -> NeutralType.BooleanType
        "datetime" -> NeutralType.DateTime(
            timezone = node.boolOrDefault("timezone", false)
        )
        "date" -> NeutralType.Date
        "time" -> NeutralType.Time
        "uuid" -> NeutralType.Uuid
        "json" -> NeutralType.Json
        "xml" -> NeutralType.Xml
        "binary" -> NeutralType.Binary
        "email" -> NeutralType.Email
        "enum" -> NeutralType.Enum(
            values = node["values"]?.toStringList(),
            refType = node.optionalText("ref_type"),
        )
        "array" -> NeutralType.Array(
            elementType = node.requiredText("element_type")
        )
        "geometry" -> NeutralType.Geometry(
            geometryType = GeometryType.of(node.optionalText("geometry_type")),
            srid = node.optionalInt("srid"),
        )
        else -> throw IllegalArgumentException("Unknown type: $typeName")
    }
}

private fun parseDefault(node: JsonNode?): DefaultValue? {
    if (node == null || node.isNull) return null
    return when {
        node.isBoolean -> DefaultValue.BooleanLiteral(node.booleanValue())
        node.isNumber -> DefaultValue.NumberLiteral(node.numberValue())
        node.isTextual -> parseScalarDefault(node.textValue())
        node.isObject -> parseObjectDefault(node)
        else -> throw IllegalArgumentException(
            "Unsupported default node type: ${node.nodeType}. " +
                "Supported: scalar (string, number, boolean), or object with 'sequence_nextval'."
        )
    }
}

private fun parseScalarDefault(text: String): DefaultValue =
    when {
        text == "current_timestamp" -> DefaultValue.FunctionCall("current_timestamp")
        text == "gen_uuid" -> DefaultValue.FunctionCall("gen_uuid")
        text.matches(Regex("""^nextval\(.+\)$""", RegexOption.IGNORE_CASE)) ->
            DefaultValue.FunctionCall(text)
        else -> DefaultValue.StringLiteral(text)
    }

private fun parseObjectDefault(node: JsonNode): DefaultValue {
    val fields = node.fieldNames().asSequence().toList()
    if (fields.size != 1) {
        throw IllegalArgumentException(
            "Default object must have exactly one key, got ${fields.size}: ${fields.joinToString(", ")}. " +
                "Supported: { sequence_nextval: <name> }"
        )
    }
    return when (val fieldName = fields.first()) {
        "sequence_nextval" -> DefaultValue.SequenceNextVal(node.get("sequence_nextval").asText())
        "nextval" -> {
            val seqName = node.get("nextval")?.asText() ?: "<name>"
            throw IllegalArgumentException(
                "Legacy default form '{ nextval: $seqName }' is not supported since 0.9.3. " +
                    "Use '{ sequence_nextval: $seqName }' instead. " +
                    "(Before: default: { nextval: $seqName } → After: default: { sequence_nextval: $seqName })"
            )
        }
        else -> throw IllegalArgumentException(
            "Unsupported default object form with key '$fieldName'. " +
                "Supported: sequence_nextval, or scalar values (string, number, boolean)."
        )
    }
}

private fun parseReference(node: JsonNode?): ReferenceDefinition? {
    if (node == null || !node.isObject) return null
    return ReferenceDefinition(
        table = node.requiredText("table"),
        column = node.requiredText("column"),
        onDelete = node.optionalText("on_delete")?.toReferentialAction(),
        onUpdate = node.optionalText("on_update")?.toReferentialAction(),
    )
}

private fun parseIndices(node: JsonNode?): List<IndexDefinition> {
    if (node == null || !node.isArray) return emptyList()
    return node.map { childNode ->
        IndexDefinition(
            name = childNode.optionalText("name"),
            columns = childNode["columns"]?.toStringList() ?: emptyList(),
            type = childNode.optionalText("type")?.toIndexType() ?: IndexType.BTREE,
            unique = childNode.boolOrDefault("unique", false),
        )
    }
}

private fun parseConstraints(node: JsonNode?): List<ConstraintDefinition> {
    if (node == null || !node.isArray) return emptyList()
    return node.map { childNode ->
        ConstraintDefinition(
            name = childNode.requiredText("name"),
            type = childNode.requiredText("type").toConstraintType(),
            columns = childNode["columns"]?.toStringList(),
            expression = childNode.optionalText("expression"),
            references = parseConstraintReference(childNode["references"]),
        )
    }
}

private fun parseConstraintReference(node: JsonNode?): ConstraintReferenceDefinition? {
    if (node == null || !node.isObject) return null
    return ConstraintReferenceDefinition(
        table = node.requiredText("table"),
        columns = node["columns"]?.toStringList() ?: emptyList(),
        onDelete = node.optionalText("on_delete")?.toReferentialAction(),
        onUpdate = node.optionalText("on_update")?.toReferentialAction(),
    )
}

private fun parsePartitioning(node: JsonNode?): PartitionConfig? {
    if (node == null || !node.isObject) return null
    return PartitionConfig(
        type = node.requiredText("type").toPartitionType(),
        key = node["key"]?.toStringList() ?: emptyList(),
        partitions = node["partitions"]?.map { childNode ->
            PartitionDefinition(
                name = childNode.requiredText("name"),
                from = childNode.optionalText("from"),
                to = childNode.optionalText("to"),
                values = childNode["values"]?.toStringList(),
            )
        } ?: emptyList(),
    )
}

internal fun parseCustomTypes(node: JsonNode?): Map<String, CustomTypeDefinition> =
    parseNamedObjectMap(node) { childNode ->
        CustomTypeDefinition(
            kind = childNode.requiredText("kind").toCustomTypeKind(),
            values = childNode["values"]?.toStringList(),
            fields = parseColumns(childNode["fields"]),
            baseType = childNode.optionalText("base_type"),
            precision = childNode.optionalInt("precision"),
            scale = childNode.optionalInt("scale"),
            check = childNode.optionalText("check"),
            description = childNode.optionalText("description"),
        )
    }
