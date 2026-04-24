package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.dmigrate.core.model.*

internal fun buildCustomTypes(
    mapper: ObjectMapper,
    types: Map<String, CustomTypeDefinition>,
): ObjectNode {
    val node = mapper.createObjectNode()
    for ((name, definition) in types.entries.sortedBy { it.key }) {
        val typeNode = mapper.createObjectNode()
        typeNode.put("kind", definition.kind.name.lowercase())
        if (!definition.values.isNullOrEmpty()) {
            typeNode.set<ArrayNode>("values", stringArray(mapper, definition.values!!))
        }
        if (!definition.fields.isNullOrEmpty()) {
            typeNode.set<ObjectNode>("fields", buildColumns(mapper, definition.fields!!))
        }
        if (definition.baseType != null) typeNode.put("base_type", definition.baseType)
        if (definition.precision != null) typeNode.put("precision", definition.precision)
        if (definition.scale != null) typeNode.put("scale", definition.scale)
        if (definition.check != null) typeNode.put("check", definition.check)
        if (definition.description != null) typeNode.put("description", definition.description)
        node.set<ObjectNode>(name, typeNode)
    }
    return node
}

internal fun buildTables(
    mapper: ObjectMapper,
    tables: Map<String, TableDefinition>,
): ObjectNode {
    val node = mapper.createObjectNode()
    for ((name, definition) in tables.entries.sortedBy { it.key }) {
        node.set<ObjectNode>(name, buildTable(mapper, definition))
    }
    return node
}

private fun buildTable(mapper: ObjectMapper, definition: TableDefinition): ObjectNode {
    val node = mapper.createObjectNode()
    if (definition.description != null) node.put("description", definition.description)
    if (definition.columns.isNotEmpty()) node.set<ObjectNode>("columns", buildColumns(mapper, definition.columns))
    if (definition.primaryKey.isNotEmpty()) {
        node.set<ArrayNode>("primary_key", stringArray(mapper, definition.primaryKey))
    }
    if (definition.indices.isNotEmpty()) {
        node.set<ArrayNode>("indices", buildIndices(mapper, definition.indices))
    }
    if (definition.constraints.isNotEmpty()) {
        node.set<ArrayNode>("constraints", buildConstraints(mapper, definition.constraints))
    }
    if (definition.partitioning != null) {
        node.set<ObjectNode>("partitioning", buildPartitioning(mapper, definition.partitioning!!))
    }
    if (definition.metadata != null) {
        node.set<ObjectNode>("metadata", buildTableMetadata(mapper, definition.metadata!!))
    }
    return node
}

private fun buildTableMetadata(mapper: ObjectMapper, metadata: TableMetadata): ObjectNode {
    val node = mapper.createObjectNode()
    if (metadata.engine != null) node.put("engine", metadata.engine)
    if (metadata.withoutRowid) node.put("without_rowid", true)
    return node
}

internal fun buildColumns(
    mapper: ObjectMapper,
    columns: Map<String, ColumnDefinition>,
): ObjectNode {
    val node = mapper.createObjectNode()
    for ((name, column) in columns.entries.sortedBy { it.key }) {
        node.set<ObjectNode>(name, buildColumn(mapper, column))
    }
    return node
}

private fun buildColumn(mapper: ObjectMapper, column: ColumnDefinition): ObjectNode {
    val node = mapper.createObjectNode()
    buildNeutralType(node, column.type)
    if (column.required) node.put("required", true)
    if (column.unique) node.put("unique", true)
    if (column.default != null) buildDefault(node, column.default!!)
    if (column.references != null) {
        node.set<ObjectNode>("references", buildReference(mapper, column.references!!))
    }
    return node
}

private fun buildDefault(node: ObjectNode, default: DefaultValue) {
    when (default) {
        is DefaultValue.StringLiteral -> node.put("default", default.value)
        is DefaultValue.NumberLiteral -> {
            val number = default.value
            when (number) {
                is Int -> node.put("default", number)
                is Long -> node.put("default", number)
                is Double -> node.put("default", number)
                is Float -> node.put("default", number)
                else -> node.put("default", number.toDouble())
            }
        }
        is DefaultValue.BooleanLiteral -> node.put("default", default.value)
        is DefaultValue.FunctionCall -> node.put("default", default.name)
        is DefaultValue.SequenceNextVal -> {
            val defaultNode = node.putObject("default")
            defaultNode.put("sequence_nextval", default.sequenceName)
        }
    }
}

private fun buildReference(mapper: ObjectMapper, reference: ReferenceDefinition): ObjectNode {
    val node = mapper.createObjectNode()
    node.put("table", reference.table)
    node.put("column", reference.column)
    if (reference.onDelete != null) node.put("on_delete", reference.onDelete!!.name.lowercase())
    if (reference.onUpdate != null) node.put("on_update", reference.onUpdate!!.name.lowercase())
    return node
}

private fun buildIndices(
    mapper: ObjectMapper,
    indices: List<IndexDefinition>,
): ArrayNode {
    val arrayNode = mapper.createArrayNode()
    for (index in indices) {
        val node = mapper.createObjectNode()
        if (index.name != null) node.put("name", index.name)
        node.set<ArrayNode>("columns", stringArray(mapper, index.columns))
        if (index.type != IndexType.BTREE) node.put("type", index.type.name.lowercase())
        if (index.unique) node.put("unique", true)
        arrayNode.add(node)
    }
    return arrayNode
}

private fun buildConstraints(
    mapper: ObjectMapper,
    constraints: List<ConstraintDefinition>,
): ArrayNode {
    val arrayNode = mapper.createArrayNode()
    for (constraint in constraints) {
        val node = mapper.createObjectNode()
        node.put("name", constraint.name)
        node.put("type", constraint.type.name.lowercase())
        if (!constraint.columns.isNullOrEmpty()) {
            node.set<ArrayNode>("columns", stringArray(mapper, constraint.columns!!))
        }
        if (constraint.expression != null) node.put("expression", constraint.expression)
        if (constraint.references != null) {
            node.set<ObjectNode>("references", buildConstraintReference(mapper, constraint.references!!))
        }
        arrayNode.add(node)
    }
    return arrayNode
}

private fun buildConstraintReference(
    mapper: ObjectMapper,
    reference: ConstraintReferenceDefinition,
): ObjectNode {
    val node = mapper.createObjectNode()
    node.put("table", reference.table)
    node.set<ArrayNode>("columns", stringArray(mapper, reference.columns))
    if (reference.onDelete != null) node.put("on_delete", reference.onDelete!!.name.lowercase())
    if (reference.onUpdate != null) node.put("on_update", reference.onUpdate!!.name.lowercase())
    return node
}

private fun buildPartitioning(mapper: ObjectMapper, partitioning: PartitionConfig): ObjectNode {
    val node = mapper.createObjectNode()
    node.put("type", partitioning.type.name.lowercase())
    node.set<ArrayNode>("key", stringArray(mapper, partitioning.key))
    if (partitioning.partitions.isNotEmpty()) {
        val partitionsNode = mapper.createArrayNode()
        for (partition in partitioning.partitions) {
            val partitionNode = mapper.createObjectNode()
            partitionNode.put("name", partition.name)
            if (partition.from != null) partitionNode.put("from", partition.from)
            if (partition.to != null) partitionNode.put("to", partition.to)
            if (!partition.values.isNullOrEmpty()) {
                partitionNode.set<ArrayNode>("values", stringArray(mapper, partition.values!!))
            }
            partitionsNode.add(partitionNode)
        }
        node.set<ArrayNode>("partitions", partitionsNode)
    }
    return node
}
