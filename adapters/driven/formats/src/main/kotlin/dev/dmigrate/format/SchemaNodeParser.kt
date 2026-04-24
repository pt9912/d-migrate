package dev.dmigrate.format

import com.fasterxml.jackson.databind.JsonNode
import dev.dmigrate.core.model.SchemaDefinition

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
}
