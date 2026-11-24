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
        YAMLFactory.builder()
            // Quotet number-/bool-/null-/timestamp-artige String-Werte, die
            // `MINIMIZE_QUOTES` sonst unquotiert ließe und die YAML-Lese-Seite
            // umdeuten würde (LN-046). Delegiert an SnakeYAMLs Resolver.
            .stringQuotingChecker(YamlImplicitAwareQuotingChecker())
            .build()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    )

    override fun read(input: InputStream): SchemaDefinition {
        // Zweimal gelesen: einmal von Jackson zum Baum (mit der Pruefung auf doppelte Schluessel), einmal als
        // Token-Strom, der den geschriebenen Text jedes typisierten Skalars liefert (YamlWrittenScalars).
        val bytes = input.readBytes()
        val root = readMapper.readTree(bytes)?.takeUnless { it.isMissingNode }
            ?: throw IllegalArgumentException("Empty YAML document")
        return SchemaNodeParser.parse(YamlWrittenScalars.mark(root, readMapper.factory.createParser(bytes)))
    }

    override fun write(output: OutputStream, schema: SchemaDefinition) {
        val tree = SchemaNodeBuilder.build(writeMapper, schema)
        writeMapper.writeTree(writeMapper.factory.createGenerator(output), tree)
    }
}
