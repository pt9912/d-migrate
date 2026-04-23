package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.dmigrate.core.model.*

internal fun stringArray(mapper: ObjectMapper, values: List<String>): ArrayNode {
    val arrayNode = mapper.createArrayNode()
    values.forEach { arrayNode.add(it) }
    return arrayNode
}

internal fun buildNeutralType(node: ObjectNode, type: NeutralType) {
    when (type) {
        is NeutralType.Identifier -> buildIdentifierType(node, type)
        is NeutralType.Text -> buildTextType(node, type)
        is NeutralType.Char -> { node.put("type", "char"); node.put("length", type.length) }
        is NeutralType.Integer -> node.put("type", "integer")
        is NeutralType.SmallInt -> node.put("type", "smallint")
        is NeutralType.BigInteger -> node.put("type", "biginteger")
        is NeutralType.Float -> buildFloatType(node, type)
        is NeutralType.Decimal -> buildDecimalType(node, type)
        is NeutralType.BooleanType -> node.put("type", "boolean")
        is NeutralType.DateTime -> buildDateTimeType(node, type)
        is NeutralType.Date -> node.put("type", "date")
        is NeutralType.Time -> node.put("type", "time")
        is NeutralType.Uuid -> node.put("type", "uuid")
        is NeutralType.Json -> node.put("type", "json")
        is NeutralType.Xml -> node.put("type", "xml")
        is NeutralType.Binary -> node.put("type", "binary")
        is NeutralType.Email -> node.put("type", "email")
        is NeutralType.Enum -> buildEnumType(node, type)
        is NeutralType.Array -> {
            node.put("type", "array")
            node.put("element_type", type.elementType)
        }
        is NeutralType.Geometry -> buildGeometryType(node, type)
    }
}

private fun buildIdentifierType(node: ObjectNode, type: NeutralType.Identifier) {
    node.put("type", "identifier")
    if (type.autoIncrement) node.put("auto_increment", true)
}

private fun buildTextType(node: ObjectNode, type: NeutralType.Text) {
    node.put("type", "text")
    if (type.maxLength != null) node.put("max_length", type.maxLength)
}

private fun buildFloatType(node: ObjectNode, type: NeutralType.Float) {
    node.put("type", "float")
    if (type.floatPrecision == FloatPrecision.SINGLE) node.put("float_precision", "single")
}

private fun buildDecimalType(node: ObjectNode, type: NeutralType.Decimal) {
    node.put("type", "decimal")
    node.put("precision", type.precision)
    node.put("scale", type.scale)
}

private fun buildDateTimeType(node: ObjectNode, type: NeutralType.DateTime) {
    node.put("type", "datetime")
    if (type.timezone) node.put("timezone", true)
}

private fun buildEnumType(node: ObjectNode, type: NeutralType.Enum) {
    node.put("type", "enum")
    if (type.refType != null) node.put("ref_type", type.refType)
    if (!type.values.isNullOrEmpty()) {
        val valuesNode = node.putArray("values")
        type.values!!.forEach { valuesNode.add(it) }
    }
}

private fun buildGeometryType(node: ObjectNode, type: NeutralType.Geometry) {
    node.put("type", "geometry")
    if (type.geometryType != GeometryType.GEOMETRY) {
        node.put("geometry_type", type.geometryType.schemaName)
    }
    if (type.srid != null) node.put("srid", type.srid)
}
