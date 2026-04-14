package dev.dmigrate.format.yaml

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.format.SchemaCodec
import dev.dmigrate.format.SchemaNodeBuilder
import dev.dmigrate.format.SchemaNodeParser
import java.io.InputStream
import java.io.OutputStream

class YamlSchemaCodec : SchemaCodec {

    private val readMapper = ObjectMapper(YAMLFactory()).apply {
        enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
    }

    private val writeMapper = ObjectMapper(
        YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    )

    override fun read(input: InputStream): SchemaDefinition {
        val root = readMapper.readTree(input)
            ?: throw IllegalArgumentException("Empty YAML document")
        return SchemaNodeParser.parse(root)
    }

    override fun write(output: OutputStream, schema: SchemaDefinition) {
        val tree = SchemaNodeBuilder.build(writeMapper, schema)
        writeMapper.writeTree(writeMapper.factory.createGenerator(output), tree)
    }
}
