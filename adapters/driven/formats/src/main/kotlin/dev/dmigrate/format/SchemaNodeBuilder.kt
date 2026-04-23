package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.dmigrate.core.model.SchemaDefinition

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
}
