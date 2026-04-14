package dev.dmigrate.format.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.format.SchemaCodec
import dev.dmigrate.format.SchemaNodeBuilder
import dev.dmigrate.format.SchemaNodeParser
import java.io.InputStream
import java.io.OutputStream

/**
 * Explicit JSON codec for schema definitions.
 *
 * This is intentionally separate from [dev.dmigrate.format.yaml.YamlSchemaCodec]
 * — JSON support is not an accidental side-effect of a YAML parser but an
 * explicitly documented schema file format.
 */
class JsonSchemaCodec : SchemaCodec {

    private val readMapper = ObjectMapper(JsonFactory()).apply {
        enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
    }

    private val writeMapper = ObjectMapper(JsonFactory()).apply {
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    override fun read(input: InputStream): SchemaDefinition {
        val root = readMapper.readTree(input)
            ?: throw IllegalArgumentException("Empty JSON document")
        return SchemaNodeParser.parse(root)
    }

    override fun write(output: OutputStream, schema: SchemaDefinition) {
        val tree = SchemaNodeBuilder.build(writeMapper, schema)
        val writer = writeMapper.writerWithDefaultPrettyPrinter()
        output.write(writer.writeValueAsBytes(tree))
    }
}
