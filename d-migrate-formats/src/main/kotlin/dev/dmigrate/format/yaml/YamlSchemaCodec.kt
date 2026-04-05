package dev.dmigrate.format.yaml

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import dev.dmigrate.core.model.*
import dev.dmigrate.format.SchemaCodec
import java.io.InputStream
import java.io.OutputStream

class YamlSchemaCodec : SchemaCodec {

    private val mapper = ObjectMapper(YAMLFactory())

    override fun read(input: InputStream): SchemaDefinition {
        val root = mapper.readTree(input)
            ?: throw IllegalArgumentException("Empty YAML document")

        return SchemaDefinition(
            schemaFormat = root.textOrDefault("schema_format", "1.0"),
            name = root.requiredText("name"),
            version = root.requiredText("version"),
            description = root.optionalText("description"),
            encoding = root.textOrDefault("encoding", "utf-8"),
            locale = root.optionalText("locale"),
            customTypes = parseCustomTypes(root["custom_types"]),
            tables = parseTables(root["tables"]),
            procedures = parseProcedures(root["procedures"]),
            functions = parseFunctions(root["functions"]),
            views = parseViews(root["views"]),
            triggers = parseTriggers(root["triggers"]),
            sequences = parseSequences(root["sequences"])
        )
    }

    override fun write(output: OutputStream, schema: SchemaDefinition) {
        TODO("Write support will be implemented in a future milestone")
    }

    // ── Tables ──────────────────────────────────

    private fun parseTables(node: JsonNode?): Map<String, TableDefinition> {
        if (node == null || !node.isObject) return emptyMap()
        val result = LinkedHashMap<String, TableDefinition>()
        for ((name, tableNode) in node.fields()) {
            result[name] = parseTable(tableNode)
        }
        return result
    }

    private fun parseTable(node: JsonNode): TableDefinition = TableDefinition(
        description = node.optionalText("description"),
        columns = parseColumns(node["columns"]),
        primaryKey = node["primary_key"]?.toStringList(),
        indices = parseIndices(node["indices"]),
        constraints = parseConstraints(node["constraints"]),
        partitioning = parsePartitioning(node["partitioning"])
    )

    // ── Columns ─────────────────────────────────

    private fun parseColumns(node: JsonNode?): Map<String, ColumnDefinition> {
        if (node == null || !node.isObject) return emptyMap()
        val result = LinkedHashMap<String, ColumnDefinition>()
        for ((name, colNode) in node.fields()) {
            result[name] = parseColumn(colNode)
        }
        return result
    }

    private fun parseColumn(node: JsonNode): ColumnDefinition = ColumnDefinition(
        type = parseNeutralType(node),
        required = node.boolOrDefault("required", false),
        unique = node.boolOrDefault("unique", false),
        default = parseDefault(node["default"]),
        references = parseReference(node["references"])
    )

    // ── NeutralType ─────────────────────────────

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
                scale = node.requiredInt("scale")
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
            "email" -> NeutralType.Email(
                maxLength = node.optionalInt("max_length") ?: 254
            )
            "enum" -> NeutralType.Enum(
                values = node["values"]?.toStringList(),
                refType = node.optionalText("ref_type")
            )
            "array" -> NeutralType.Array(
                elementType = node.requiredText("element_type")
            )
            else -> throw IllegalArgumentException("Unknown type: $typeName")
        }
    }

    // ── Default Values ──────────────────────────

    private fun parseDefault(node: JsonNode?): DefaultValue? {
        if (node == null || node.isNull) return null
        return when {
            node.isBoolean -> DefaultValue.BooleanLiteral(node.booleanValue())
            node.isNumber -> DefaultValue.NumberLiteral(node.numberValue())
            node.isTextual -> {
                val text = node.textValue()
                when (text) {
                    "current_timestamp" -> DefaultValue.FunctionCall("current_timestamp")
                    "gen_uuid" -> DefaultValue.FunctionCall("gen_uuid")
                    else -> DefaultValue.StringLiteral(text)
                }
            }
            else -> DefaultValue.StringLiteral(node.toString())
        }
    }

    // ── References ──────────────────────────────

    private fun parseReference(node: JsonNode?): ReferenceDefinition? {
        if (node == null || !node.isObject) return null
        return ReferenceDefinition(
            table = node.requiredText("table"),
            column = node.requiredText("column"),
            onDelete = node.optionalText("on_delete")?.toReferentialAction(),
            onUpdate = node.optionalText("on_update")?.toReferentialAction()
        )
    }

    // ── Indices ─────────────────────────────────

    private fun parseIndices(node: JsonNode?): List<IndexDefinition> {
        if (node == null || !node.isArray) return emptyList()
        return node.map { n ->
            IndexDefinition(
                name = n.optionalText("name"),
                columns = n["columns"]?.toStringList() ?: emptyList(),
                type = n.optionalText("type")?.toIndexType() ?: IndexType.BTREE,
                unique = n.boolOrDefault("unique", false)
            )
        }
    }

    // ── Constraints ─────────────────────────────

    private fun parseConstraints(node: JsonNode?): List<ConstraintDefinition> {
        if (node == null || !node.isArray) return emptyList()
        return node.map { n ->
            ConstraintDefinition(
                name = n.requiredText("name"),
                type = n.requiredText("type").toConstraintType(),
                columns = n["columns"]?.toStringList(),
                expression = n.optionalText("expression"),
                references = parseConstraintReference(n["references"])
            )
        }
    }

    private fun parseConstraintReference(node: JsonNode?): ConstraintReferenceDefinition? {
        if (node == null || !node.isObject) return null
        return ConstraintReferenceDefinition(
            table = node.requiredText("table"),
            columns = node["columns"]?.toStringList() ?: emptyList(),
            onDelete = node.optionalText("on_delete")?.toReferentialAction(),
            onUpdate = node.optionalText("on_update")?.toReferentialAction()
        )
    }

    // ── Partitioning ────────────────────────────

    private fun parsePartitioning(node: JsonNode?): PartitionConfig? {
        if (node == null || !node.isObject) return null
        return PartitionConfig(
            type = node.requiredText("type").toPartitionType(),
            key = node["key"]?.toStringList() ?: emptyList(),
            partitions = node["partitions"]?.map { n ->
                PartitionDefinition(
                    name = n.requiredText("name"),
                    from = n.optionalText("from"),
                    to = n.optionalText("to"),
                    values = n["values"]?.toStringList()
                )
            } ?: emptyList()
        )
    }

    // ── Custom Types ────────────────────────────

    private fun parseCustomTypes(node: JsonNode?): Map<String, CustomTypeDefinition> {
        if (node == null || !node.isObject) return emptyMap()
        val result = LinkedHashMap<String, CustomTypeDefinition>()
        for ((name, typeNode) in node.fields()) {
            result[name] = CustomTypeDefinition(
                kind = typeNode.requiredText("kind").toCustomTypeKind(),
                values = typeNode["values"]?.toStringList(),
                fields = parseColumns(typeNode["fields"]),
                baseType = typeNode.optionalText("base_type"),
                precision = typeNode.optionalInt("precision"),
                scale = typeNode.optionalInt("scale"),
                check = typeNode.optionalText("check"),
                description = typeNode.optionalText("description")
            )
        }
        return result
    }

    // ── Procedures & Functions ───────────────────

    private fun parseProcedures(node: JsonNode?): Map<String, ProcedureDefinition> {
        if (node == null || !node.isObject) return emptyMap()
        val result = LinkedHashMap<String, ProcedureDefinition>()
        for ((name, n) in node.fields()) {
            result[name] = ProcedureDefinition(
                description = n.optionalText("description"),
                parameters = parseParameters(n["parameters"]),
                language = n.optionalText("language"),
                body = n.optionalText("body"),
                dependencies = parseDependencies(n["dependencies"]),
                sourceDialect = n.optionalText("source_dialect")
            )
        }
        return result
    }

    private fun parseFunctions(node: JsonNode?): Map<String, FunctionDefinition> {
        if (node == null || !node.isObject) return emptyMap()
        val result = LinkedHashMap<String, FunctionDefinition>()
        for ((name, n) in node.fields()) {
            result[name] = FunctionDefinition(
                description = n.optionalText("description"),
                parameters = parseParameters(n["parameters"]),
                returns = parseReturnType(n["returns"]),
                language = n.optionalText("language"),
                deterministic = n.optionalBool("deterministic"),
                body = n.optionalText("body"),
                dependencies = parseDependencies(n["dependencies"]),
                sourceDialect = n.optionalText("source_dialect")
            )
        }
        return result
    }

    private fun parseParameters(node: JsonNode?): List<ParameterDefinition> {
        if (node == null || !node.isArray) return emptyList()
        return node.map { n ->
            ParameterDefinition(
                name = n.requiredText("name"),
                type = n.requiredText("type"),
                direction = n.optionalText("direction")?.toParameterDirection() ?: ParameterDirection.IN
            )
        }
    }

    private fun parseReturnType(node: JsonNode?): ReturnType? {
        if (node == null || !node.isObject) return null
        return ReturnType(
            type = node.requiredText("type"),
            precision = node.optionalInt("precision"),
            scale = node.optionalInt("scale")
        )
    }

    // ── Views ───────────────────────────────────

    private fun parseViews(node: JsonNode?): Map<String, ViewDefinition> {
        if (node == null || !node.isObject) return emptyMap()
        val result = LinkedHashMap<String, ViewDefinition>()
        for ((name, n) in node.fields()) {
            result[name] = ViewDefinition(
                description = n.optionalText("description"),
                materialized = n.boolOrDefault("materialized", false),
                refresh = n.optionalText("refresh"),
                query = n.optionalText("query"),
                dependencies = parseDependencies(n["dependencies"]),
                sourceDialect = n.optionalText("source_dialect")
            )
        }
        return result
    }

    // ── Triggers ────────────────────────────────

    private fun parseTriggers(node: JsonNode?): Map<String, TriggerDefinition> {
        if (node == null || !node.isObject) return emptyMap()
        val result = LinkedHashMap<String, TriggerDefinition>()
        for ((name, n) in node.fields()) {
            result[name] = TriggerDefinition(
                description = n.optionalText("description"),
                table = n.requiredText("table"),
                event = n.requiredText("event").toTriggerEvent(),
                timing = n.requiredText("timing").toTriggerTiming(),
                forEach = n.optionalText("for_each")?.toTriggerForEach() ?: TriggerForEach.ROW,
                condition = n.optionalText("condition"),
                body = n.optionalText("body"),
                dependencies = parseDependencies(n["dependencies"]),
                sourceDialect = n.optionalText("source_dialect")
            )
        }
        return result
    }

    // ── Sequences ───────────────────────────────

    private fun parseSequences(node: JsonNode?): Map<String, SequenceDefinition> {
        if (node == null || !node.isObject) return emptyMap()
        val result = LinkedHashMap<String, SequenceDefinition>()
        for ((name, n) in node.fields()) {
            result[name] = SequenceDefinition(
                description = n.optionalText("description"),
                start = n.optionalLong("start") ?: 1,
                increment = n.optionalLong("increment") ?: 1,
                minValue = n.optionalLong("min_value"),
                maxValue = n.optionalLong("max_value"),
                cycle = n.boolOrDefault("cycle", false),
                cache = n.optionalInt("cache")
            )
        }
        return result
    }

    // ── Dependencies ────────────────────────────

    private fun parseDependencies(node: JsonNode?): DependencyInfo? {
        if (node == null || !node.isObject) return null
        val columns = mutableMapOf<String, List<String>>()
        node["columns"]?.fields()?.forEach { (table, cols) ->
            columns[table] = cols.toStringList()
        }
        return DependencyInfo(
            tables = node["tables"]?.toStringList() ?: emptyList(),
            columns = columns
        )
    }

    // ── Extension Helpers ───────────────────────

    private fun JsonNode.requiredText(field: String): String =
        this[field]?.asText() ?: throw IllegalArgumentException("Missing required field: $field")

    private fun JsonNode.optionalText(field: String): String? =
        this[field]?.takeIf { !it.isNull }?.asText()

    private fun JsonNode.textOrDefault(field: String, default: String): String =
        this[field]?.asText() ?: default

    private fun JsonNode.requiredInt(field: String): Int =
        this[field]?.asInt() ?: throw IllegalArgumentException("Missing required field: $field")

    private fun JsonNode.optionalInt(field: String): Int? =
        this[field]?.takeIf { !it.isNull && it.isNumber }?.asInt()

    private fun JsonNode.optionalLong(field: String): Long? =
        this[field]?.takeIf { !it.isNull && it.isNumber }?.asLong()

    private fun JsonNode.optionalBool(field: String): Boolean? =
        this[field]?.takeIf { !it.isNull && it.isBoolean }?.booleanValue()

    private fun JsonNode.boolOrDefault(field: String, default: Boolean): Boolean =
        this[field]?.takeIf { !it.isNull }?.asBoolean() ?: default

    private fun JsonNode.toStringList(): List<String> =
        if (isArray) map { it.asText() } else emptyList()

    // ── Enum Conversions ────────────────────────

    private fun String.toReferentialAction(): ReferentialAction = when (lowercase()) {
        "restrict" -> ReferentialAction.RESTRICT
        "cascade" -> ReferentialAction.CASCADE
        "set_null" -> ReferentialAction.SET_NULL
        "set_default" -> ReferentialAction.SET_DEFAULT
        "no_action" -> ReferentialAction.NO_ACTION
        else -> throw IllegalArgumentException("Unknown referential action: $this")
    }

    private fun String.toIndexType(): IndexType = when (lowercase()) {
        "btree" -> IndexType.BTREE
        "hash" -> IndexType.HASH
        "gin" -> IndexType.GIN
        "gist" -> IndexType.GIST
        "brin" -> IndexType.BRIN
        else -> throw IllegalArgumentException("Unknown index type: $this")
    }

    private fun String.toConstraintType(): ConstraintType = when (lowercase()) {
        "check" -> ConstraintType.CHECK
        "unique" -> ConstraintType.UNIQUE
        "exclude" -> ConstraintType.EXCLUDE
        "foreign_key" -> ConstraintType.FOREIGN_KEY
        else -> throw IllegalArgumentException("Unknown constraint type: $this")
    }

    private fun String.toPartitionType(): PartitionType = when (lowercase()) {
        "range" -> PartitionType.RANGE
        "hash" -> PartitionType.HASH
        "list" -> PartitionType.LIST
        else -> throw IllegalArgumentException("Unknown partition type: $this")
    }

    private fun String.toCustomTypeKind(): CustomTypeKind = when (lowercase()) {
        "enum" -> CustomTypeKind.ENUM
        "composite" -> CustomTypeKind.COMPOSITE
        "domain" -> CustomTypeKind.DOMAIN
        else -> throw IllegalArgumentException("Unknown custom type kind: $this")
    }

    private fun String.toParameterDirection(): ParameterDirection = when (lowercase()) {
        "in" -> ParameterDirection.IN
        "out" -> ParameterDirection.OUT
        "inout" -> ParameterDirection.INOUT
        else -> throw IllegalArgumentException("Unknown parameter direction: $this")
    }

    private fun String.toTriggerEvent(): TriggerEvent = when (lowercase()) {
        "insert" -> TriggerEvent.INSERT
        "update" -> TriggerEvent.UPDATE
        "delete" -> TriggerEvent.DELETE
        else -> throw IllegalArgumentException("Unknown trigger event: $this")
    }

    private fun String.toTriggerTiming(): TriggerTiming = when (lowercase()) {
        "before" -> TriggerTiming.BEFORE
        "after" -> TriggerTiming.AFTER
        "instead_of" -> TriggerTiming.INSTEAD_OF
        else -> throw IllegalArgumentException("Unknown trigger timing: $this")
    }

    private fun String.toTriggerForEach(): TriggerForEach = when (lowercase()) {
        "row" -> TriggerForEach.ROW
        "statement" -> TriggerForEach.STATEMENT
        else -> throw IllegalArgumentException("Unknown trigger for_each: $this")
    }
}
