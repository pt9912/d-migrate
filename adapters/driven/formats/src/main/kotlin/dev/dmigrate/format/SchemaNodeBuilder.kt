package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.dmigrate.core.model.*

/**
 * Builds a deterministic Jackson [ObjectNode] tree from a
 * [SchemaDefinition]. Shared between YAML and JSON writers.
 *
 * Field ordering is fixed (not alphabetical) to match the canonical
 * schema YAML layout. Maps are sorted by key for deterministic output.
 */
internal object SchemaNodeBuilder {

    fun build(mapper: ObjectMapper, schema: SchemaDefinition): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("schema_format", schema.schemaFormat)
        root.put("name", schema.name)
        root.put("version", schema.version)
        if (schema.description != null) root.put("description", schema.description)
        if (schema.encoding != "utf-8") root.put("encoding", schema.encoding)
        if (schema.locale != null) root.put("locale", schema.locale)

        if (schema.customTypes.isNotEmpty()) {
            root.set<ObjectNode>("custom_types", buildCustomTypes(mapper, schema.customTypes))
        }
        if (schema.tables.isNotEmpty()) {
            root.set<ObjectNode>("tables", buildTables(mapper, schema.tables))
        }
        if (schema.procedures.isNotEmpty()) {
            root.set<ObjectNode>("procedures", buildProcedures(mapper, schema.procedures))
        }
        if (schema.functions.isNotEmpty()) {
            root.set<ObjectNode>("functions", buildFunctions(mapper, schema.functions))
        }
        if (schema.views.isNotEmpty()) {
            root.set<ObjectNode>("views", buildViews(mapper, schema.views))
        }
        if (schema.triggers.isNotEmpty()) {
            root.set<ObjectNode>("triggers", buildTriggers(mapper, schema.triggers))
        }
        if (schema.sequences.isNotEmpty()) {
            root.set<ObjectNode>("sequences", buildSequences(mapper, schema.sequences))
        }

        return root
    }

    // ── Custom Types ────────────────────────────

    private fun buildCustomTypes(mapper: ObjectMapper, types: Map<String, CustomTypeDefinition>): ObjectNode {
        val node = mapper.createObjectNode()
        for ((name, def) in types.entries.sortedBy { it.key }) {
            val typeNode = mapper.createObjectNode()
            typeNode.put("kind", def.kind.name.lowercase())
            if (!def.values.isNullOrEmpty()) typeNode.set<ArrayNode>("values", stringArray(mapper, def.values!!))
            if (!def.fields.isNullOrEmpty()) typeNode.set<ObjectNode>("fields", buildColumns(mapper, def.fields!!))
            if (def.baseType != null) typeNode.put("base_type", def.baseType)
            if (def.precision != null) typeNode.put("precision", def.precision)
            if (def.scale != null) typeNode.put("scale", def.scale)
            if (def.check != null) typeNode.put("check", def.check)
            if (def.description != null) typeNode.put("description", def.description)
            node.set<ObjectNode>(name, typeNode)
        }
        return node
    }

    // ── Tables ──────────────────────────────────

    private fun buildTables(mapper: ObjectMapper, tables: Map<String, TableDefinition>): ObjectNode {
        val node = mapper.createObjectNode()
        for ((name, def) in tables.entries.sortedBy { it.key }) {
            node.set<ObjectNode>(name, buildTable(mapper, def))
        }
        return node
    }

    private fun buildTable(mapper: ObjectMapper, def: TableDefinition): ObjectNode {
        val node = mapper.createObjectNode()
        if (def.description != null) node.put("description", def.description)
        if (def.columns.isNotEmpty()) node.set<ObjectNode>("columns", buildColumns(mapper, def.columns))
        if (def.primaryKey.isNotEmpty()) node.set<ArrayNode>("primary_key", stringArray(mapper, def.primaryKey))
        if (def.indices.isNotEmpty()) node.set<ArrayNode>("indices", buildIndices(mapper, def.indices))
        if (def.constraints.isNotEmpty()) node.set<ArrayNode>("constraints", buildConstraints(mapper, def.constraints))
        if (def.partitioning != null) node.set<ObjectNode>("partitioning", buildPartitioning(mapper, def.partitioning!!))
        if (def.metadata != null) node.set<ObjectNode>("metadata", buildTableMetadata(mapper, def.metadata!!))
        return node
    }

    private fun buildTableMetadata(mapper: ObjectMapper, meta: TableMetadata): ObjectNode {
        val node = mapper.createObjectNode()
        if (meta.engine != null) node.put("engine", meta.engine)
        if (meta.withoutRowid) node.put("without_rowid", true)
        return node
    }

    // ── Columns ─────────────────────────────────

    private fun buildColumns(mapper: ObjectMapper, columns: Map<String, ColumnDefinition>): ObjectNode {
        val node = mapper.createObjectNode()
        for ((name, col) in columns.entries.sortedBy { it.key }) {
            node.set<ObjectNode>(name, buildColumn(mapper, col))
        }
        return node
    }

    private fun buildColumn(mapper: ObjectMapper, col: ColumnDefinition): ObjectNode {
        val node = mapper.createObjectNode()
        buildNeutralType(node, col.type)
        if (col.required) node.put("required", true)
        if (col.unique) node.put("unique", true)
        if (col.default != null) buildDefault(node, col.default!!)
        if (col.references != null) node.set<ObjectNode>("references", buildReference(mapper, col.references!!))
        return node
    }

    private fun buildNeutralType(node: ObjectNode, type: NeutralType) {
        when (type) {
            is NeutralType.Identifier -> {
                node.put("type", "identifier")
                if (type.autoIncrement) node.put("auto_increment", true)
            }
            is NeutralType.Text -> {
                node.put("type", "text")
                if (type.maxLength != null) node.put("max_length", type.maxLength)
            }
            is NeutralType.Char -> {
                node.put("type", "char")
                node.put("length", type.length)
            }
            is NeutralType.Integer -> node.put("type", "integer")
            is NeutralType.SmallInt -> node.put("type", "smallint")
            is NeutralType.BigInteger -> node.put("type", "biginteger")
            is NeutralType.Float -> {
                node.put("type", "float")
                if (type.floatPrecision == FloatPrecision.SINGLE) node.put("float_precision", "single")
            }
            is NeutralType.Decimal -> {
                node.put("type", "decimal")
                node.put("precision", type.precision)
                node.put("scale", type.scale)
            }
            is NeutralType.BooleanType -> node.put("type", "boolean")
            is NeutralType.DateTime -> {
                node.put("type", "datetime")
                if (type.timezone) node.put("timezone", true)
            }
            is NeutralType.Date -> node.put("type", "date")
            is NeutralType.Time -> node.put("type", "time")
            is NeutralType.Uuid -> node.put("type", "uuid")
            is NeutralType.Json -> node.put("type", "json")
            is NeutralType.Xml -> node.put("type", "xml")
            is NeutralType.Binary -> node.put("type", "binary")
            is NeutralType.Email -> node.put("type", "email")
            is NeutralType.Enum -> {
                node.put("type", "enum")
                if (type.refType != null) node.put("ref_type", type.refType)
                if (!type.values.isNullOrEmpty()) {
                    val arr = node.putArray("values")
                    type.values!!.forEach { arr.add(it) }
                }
            }
            is NeutralType.Array -> {
                node.put("type", "array")
                node.put("element_type", type.elementType)
            }
            is NeutralType.Geometry -> {
                node.put("type", "geometry")
                if (type.geometryType != GeometryType.GEOMETRY) {
                    node.put("geometry_type", type.geometryType.schemaName)
                }
                if (type.srid != null) node.put("srid", type.srid)
            }
        }
    }

    private fun buildDefault(node: ObjectNode, default: DefaultValue) {
        when (default) {
            is DefaultValue.StringLiteral -> node.put("default", default.value)
            is DefaultValue.NumberLiteral -> {
                val num = default.value
                when (num) {
                    is Int -> node.put("default", num)
                    is Long -> node.put("default", num)
                    is Double -> node.put("default", num)
                    is Float -> node.put("default", num)
                    else -> node.put("default", num.toDouble())
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

    private fun buildReference(mapper: ObjectMapper, ref: ReferenceDefinition): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("table", ref.table)
        node.put("column", ref.column)
        if (ref.onDelete != null) node.put("on_delete", ref.onDelete!!.name.lowercase())
        if (ref.onUpdate != null) node.put("on_update", ref.onUpdate!!.name.lowercase())
        return node
    }

    // ── Indices ─────────────────────────────────

    private fun buildIndices(mapper: ObjectMapper, indices: List<IndexDefinition>): ArrayNode {
        val arr = mapper.createArrayNode()
        for (idx in indices) {
            val node = mapper.createObjectNode()
            if (idx.name != null) node.put("name", idx.name)
            node.set<ArrayNode>("columns", stringArray(mapper, idx.columns))
            if (idx.type != IndexType.BTREE) node.put("type", idx.type.name.lowercase())
            if (idx.unique) node.put("unique", true)
            arr.add(node)
        }
        return arr
    }

    // ── Constraints ─────────────────────────────

    private fun buildConstraints(mapper: ObjectMapper, constraints: List<ConstraintDefinition>): ArrayNode {
        val arr = mapper.createArrayNode()
        for (c in constraints) {
            val node = mapper.createObjectNode()
            node.put("name", c.name)
            node.put("type", c.type.name.lowercase())
            if (!c.columns.isNullOrEmpty()) node.set<ArrayNode>("columns", stringArray(mapper, c.columns!!))
            if (c.expression != null) node.put("expression", c.expression)
            if (c.references != null) {
                val refNode = mapper.createObjectNode()
                refNode.put("table", c.references!!.table)
                refNode.set<ArrayNode>("columns", stringArray(mapper, c.references!!.columns))
                if (c.references!!.onDelete != null) refNode.put("on_delete", c.references!!.onDelete!!.name.lowercase())
                if (c.references!!.onUpdate != null) refNode.put("on_update", c.references!!.onUpdate!!.name.lowercase())
                node.set<ObjectNode>("references", refNode)
            }
            arr.add(node)
        }
        return arr
    }

    // ── Partitioning ────────────────────────────

    private fun buildPartitioning(mapper: ObjectMapper, part: PartitionConfig): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("type", part.type.name.lowercase())
        node.set<ArrayNode>("key", stringArray(mapper, part.key))
        if (part.partitions.isNotEmpty()) {
            val arr = mapper.createArrayNode()
            for (p in part.partitions) {
                val pNode = mapper.createObjectNode()
                pNode.put("name", p.name)
                if (p.from != null) pNode.put("from", p.from)
                if (p.to != null) pNode.put("to", p.to)
                if (!p.values.isNullOrEmpty()) pNode.set<ArrayNode>("values", stringArray(mapper, p.values!!))
                arr.add(pNode)
            }
            node.set<ArrayNode>("partitions", arr)
        }
        return node
    }

    // ── Procedures & Functions ──────────────────

    private fun buildProcedures(mapper: ObjectMapper, procs: Map<String, ProcedureDefinition>): ObjectNode {
        val node = mapper.createObjectNode()
        for ((name, def) in procs.entries.sortedBy { it.key }) {
            val pNode = mapper.createObjectNode()
            if (def.description != null) pNode.put("description", def.description)
            if (def.parameters.isNotEmpty()) pNode.set<ArrayNode>("parameters", buildParameters(mapper, def.parameters))
            if (def.language != null) pNode.put("language", def.language)
            if (def.body != null) pNode.put("body", def.body)
            if (def.dependencies != null) pNode.set<ObjectNode>("dependencies", buildDependencies(mapper, def.dependencies!!))
            if (def.sourceDialect != null) pNode.put("source_dialect", def.sourceDialect)
            node.set<ObjectNode>(name, pNode)
        }
        return node
    }

    private fun buildFunctions(mapper: ObjectMapper, funcs: Map<String, FunctionDefinition>): ObjectNode {
        val node = mapper.createObjectNode()
        for ((name, def) in funcs.entries.sortedBy { it.key }) {
            val fNode = mapper.createObjectNode()
            if (def.description != null) fNode.put("description", def.description)
            if (def.parameters.isNotEmpty()) fNode.set<ArrayNode>("parameters", buildParameters(mapper, def.parameters))
            if (def.returns != null) {
                val rNode = mapper.createObjectNode()
                rNode.put("type", def.returns!!.type)
                if (def.returns!!.precision != null) rNode.put("precision", def.returns!!.precision!!)
                if (def.returns!!.scale != null) rNode.put("scale", def.returns!!.scale!!)
                fNode.set<ObjectNode>("returns", rNode)
            }
            if (def.language != null) fNode.put("language", def.language)
            if (def.deterministic != null) fNode.put("deterministic", def.deterministic!!)
            if (def.body != null) fNode.put("body", def.body)
            if (def.dependencies != null) fNode.set<ObjectNode>("dependencies", buildDependencies(mapper, def.dependencies!!))
            if (def.sourceDialect != null) fNode.put("source_dialect", def.sourceDialect)
            node.set<ObjectNode>(name, fNode)
        }
        return node
    }

    private fun buildParameters(mapper: ObjectMapper, params: List<ParameterDefinition>): ArrayNode {
        val arr = mapper.createArrayNode()
        for (p in params) {
            val pNode = mapper.createObjectNode()
            pNode.put("name", p.name)
            pNode.put("type", p.type)
            if (p.direction != ParameterDirection.IN) pNode.put("direction", p.direction.name.lowercase())
            arr.add(pNode)
        }
        return arr
    }

    // ── Views ───────────────────────────────────

    private fun buildViews(mapper: ObjectMapper, views: Map<String, ViewDefinition>): ObjectNode {
        val node = mapper.createObjectNode()
        for ((name, def) in views.entries.sortedBy { it.key }) {
            val vNode = mapper.createObjectNode()
            if (def.description != null) vNode.put("description", def.description)
            if (def.materialized) vNode.put("materialized", true)
            if (def.refresh != null) vNode.put("refresh", def.refresh)
            if (def.query != null) vNode.put("query", def.query)
            if (def.dependencies != null) vNode.set<ObjectNode>("dependencies", buildDependencies(mapper, def.dependencies!!))
            if (def.sourceDialect != null) vNode.put("source_dialect", def.sourceDialect)
            node.set<ObjectNode>(name, vNode)
        }
        return node
    }

    // ── Triggers ────────────────────────────────

    private fun buildTriggers(mapper: ObjectMapper, triggers: Map<String, TriggerDefinition>): ObjectNode {
        val node = mapper.createObjectNode()
        for ((name, def) in triggers.entries.sortedBy { it.key }) {
            val tNode = mapper.createObjectNode()
            if (def.description != null) tNode.put("description", def.description)
            tNode.put("table", def.table)
            tNode.put("event", def.event.name.lowercase())
            tNode.put("timing", def.timing.name.lowercase())
            if (def.forEach != TriggerForEach.ROW) tNode.put("for_each", def.forEach.name.lowercase())
            if (def.condition != null) tNode.put("condition", def.condition)
            if (def.body != null) tNode.put("body", def.body)
            if (def.dependencies != null) tNode.set<ObjectNode>("dependencies", buildDependencies(mapper, def.dependencies!!))
            if (def.sourceDialect != null) tNode.put("source_dialect", def.sourceDialect)
            node.set<ObjectNode>(name, tNode)
        }
        return node
    }

    // ── Sequences ───────────────────────────────

    private fun buildSequences(mapper: ObjectMapper, seqs: Map<String, SequenceDefinition>): ObjectNode {
        val node = mapper.createObjectNode()
        for ((name, def) in seqs.entries.sortedBy { it.key }) {
            val sNode = mapper.createObjectNode()
            if (def.description != null) sNode.put("description", def.description)
            if (def.start != 1L) sNode.put("start", def.start)
            if (def.increment != 1L) sNode.put("increment", def.increment)
            if (def.minValue != null) sNode.put("min_value", def.minValue!!)
            if (def.maxValue != null) sNode.put("max_value", def.maxValue!!)
            if (def.cycle) sNode.put("cycle", true)
            if (def.cache != null) sNode.put("cache", def.cache!!)
            node.set<ObjectNode>(name, sNode)
        }
        return node
    }

    // ── Dependencies ────────────────────────────

    private fun buildDependencies(mapper: ObjectMapper, deps: DependencyInfo): ObjectNode {
        val node = mapper.createObjectNode()
        if (deps.tables.isNotEmpty()) node.set<ArrayNode>("tables", stringArray(mapper, deps.tables))
        if (deps.views.isNotEmpty()) node.set<ArrayNode>("views", stringArray(mapper, deps.views))
        if (deps.columns.isNotEmpty()) {
            val colsNode = mapper.createObjectNode()
            for ((table, cols) in deps.columns.entries.sortedBy { it.key }) {
                colsNode.set<ArrayNode>(table, stringArray(mapper, cols))
            }
            node.set<ObjectNode>("columns", colsNode)
        }
        if (deps.functions.isNotEmpty()) node.set<ArrayNode>("functions", stringArray(mapper, deps.functions))
        return node
    }

    // ── Helpers ─────────────────────────────────

    private fun stringArray(mapper: ObjectMapper, values: List<String>): ArrayNode {
        val arr = mapper.createArrayNode()
        values.forEach { arr.add(it) }
        return arr
    }
}
