package dev.dmigrate.format

import com.fasterxml.jackson.databind.JsonNode
import dev.dmigrate.core.model.*

/**
 * Parses a Jackson [JsonNode] tree into a [SchemaDefinition].
 *
 * Shared between [dev.dmigrate.format.yaml.YamlSchemaCodec] and
 * [dev.dmigrate.format.json.JsonSchemaCodec] — both produce a JsonNode
 * tree from their respective format and delegate parsing here.
 */
internal object SchemaNodeParser {

    fun parse(root: JsonNode): SchemaDefinition = SchemaDefinition(
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
        sequences = parseSequences(root["sequences"]),
    )

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

    // ── Columns ─────────────────────────────────

    internal fun parseColumns(node: JsonNode?): Map<String, ColumnDefinition> {
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
        references = parseReference(node["references"]),
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

    // ── Default Values ──────────────────────────

    private fun parseDefault(node: JsonNode?): DefaultValue? {
        if (node == null || node.isNull) return null
        return when {
            node.isBoolean -> DefaultValue.BooleanLiteral(node.booleanValue())
            node.isNumber -> DefaultValue.NumberLiteral(node.numberValue())
            node.isTextual -> {
                val text = node.textValue()
                when {
                    text == "current_timestamp" -> DefaultValue.FunctionCall("current_timestamp")
                    text == "gen_uuid" -> DefaultValue.FunctionCall("gen_uuid")
                    // Legacy nextval detection: keep as FunctionCall so the validator
                    // can emit a targeted migration error (E122)
                    text.matches(Regex("""^nextval\(.+\)$""", RegexOption.IGNORE_CASE)) ->
                        DefaultValue.FunctionCall(text)
                    else -> DefaultValue.StringLiteral(text)
                }
            }
            node.isObject -> {
                val fields = node.fieldNames().asSequence().toList()
                if (fields.size != 1) {
                    throw IllegalArgumentException(
                        "Default object must have exactly one key, got ${fields.size}: ${fields.joinToString(", ")}. " +
                            "Supported: { sequence_nextval: <name> }"
                    )
                }
                val fieldName = fields.first()
                when {
                    fieldName == "sequence_nextval" ->
                        DefaultValue.SequenceNextVal(node.get("sequence_nextval").asText())
                    fieldName == "nextval" -> {
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
            else -> throw IllegalArgumentException(
                "Unsupported default node type: ${node.nodeType}. " +
                    "Supported: scalar (string, number, boolean), or object with 'sequence_nextval'."
            )
        }
    }

    // ── References ──────────────────────────────

    private fun parseReference(node: JsonNode?): ReferenceDefinition? {
        if (node == null || !node.isObject) return null
        return ReferenceDefinition(
            table = node.requiredText("table"),
            column = node.requiredText("column"),
            onDelete = node.optionalText("on_delete")?.toReferentialAction(),
            onUpdate = node.optionalText("on_update")?.toReferentialAction(),
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
                unique = n.boolOrDefault("unique", false),
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
                references = parseConstraintReference(n["references"]),
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
                    values = n["values"]?.toStringList(),
                )
            } ?: emptyList(),
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
                description = typeNode.optionalText("description"),
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
                sourceDialect = n.optionalText("source_dialect"),
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
                sourceDialect = n.optionalText("source_dialect"),
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
                direction = n.optionalText("direction")?.toParameterDirection() ?: ParameterDirection.IN,
            )
        }
    }

    private fun parseReturnType(node: JsonNode?): ReturnType? {
        if (node == null || !node.isObject) return null
        return ReturnType(
            type = node.requiredText("type"),
            precision = node.optionalInt("precision"),
            scale = node.optionalInt("scale"),
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
                sourceDialect = n.optionalText("source_dialect"),
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
                sourceDialect = n.optionalText("source_dialect"),
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
                cache = n.optionalInt("cache"),
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
            views = node["views"]?.toStringList() ?: emptyList(),
            columns = columns,
            functions = node["functions"]?.toStringList() ?: emptyList(),
        )
    }
}

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
