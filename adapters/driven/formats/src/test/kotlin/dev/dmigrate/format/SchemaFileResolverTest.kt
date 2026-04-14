package dev.dmigrate.format

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.format.json.JsonSchemaCodec
import dev.dmigrate.format.yaml.YamlSchemaCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class SchemaFileResolverTest : FunSpec({

    test(".yaml resolves to YAML codec") {
        SchemaFileResolver.codecForPath(Path.of("schema.yaml"))
            .shouldBeInstanceOf<YamlSchemaCodec>()
    }

    test(".yml resolves to YAML codec") {
        SchemaFileResolver.codecForPath(Path.of("schema.yml"))
            .shouldBeInstanceOf<YamlSchemaCodec>()
    }

    test(".json resolves to JSON codec") {
        SchemaFileResolver.codecForPath(Path.of("schema.json"))
            .shouldBeInstanceOf<JsonSchemaCodec>()
    }

    test(".YAML resolves case-insensitively") {
        SchemaFileResolver.codecForPath(Path.of("schema.YAML"))
            .shouldBeInstanceOf<YamlSchemaCodec>()
    }

    test(".JSON resolves case-insensitively") {
        SchemaFileResolver.codecForPath(Path.of("schema.JSON"))
            .shouldBeInstanceOf<JsonSchemaCodec>()
    }

    test("unknown extension throws with clear message") {
        val ex = shouldThrow<IllegalArgumentException> {
            SchemaFileResolver.codecForPath(Path.of("schema.txt"))
        }
        ex.message shouldContain "Unknown schema file extension"
        ex.message shouldContain ".yaml"
        ex.message shouldContain ".json"
    }

    test("no extension throws with clear message") {
        val ex = shouldThrow<IllegalArgumentException> {
            SchemaFileResolver.codecForPath(Path.of("schema"))
        }
        ex.message shouldContain "Unknown schema file extension"
    }

    test("detectFormat returns yaml for .yaml") {
        SchemaFileResolver.detectFormat(Path.of("test.yaml")) shouldBe "yaml"
    }

    test("detectFormat returns yaml for .yml") {
        SchemaFileResolver.detectFormat(Path.of("test.yml")) shouldBe "yaml"
    }

    test("detectFormat returns json for .json") {
        SchemaFileResolver.detectFormat(Path.of("test.json")) shouldBe "json"
    }

    test("codecForFormat with yaml") {
        SchemaFileResolver.codecForFormat("yaml")
            .shouldBeInstanceOf<YamlSchemaCodec>()
    }

    test("codecForFormat with json") {
        SchemaFileResolver.codecForFormat("json")
            .shouldBeInstanceOf<JsonSchemaCodec>()
    }

    test("codecForFormat with unknown format throws") {
        shouldThrow<IllegalArgumentException> {
            SchemaFileResolver.codecForFormat("xml")
        }.message shouldContain "Unknown schema file format"
    }

    test("validateOutputPath does not throw when extension matches format") {
        SchemaFileResolver.validateOutputPath(Path.of("out.yaml"), "yaml")
        SchemaFileResolver.validateOutputPath(Path.of("out.json"), "json")
    }

    test("validateOutputPath throws when extension mismatches format") {
        shouldThrow<IllegalArgumentException> {
            SchemaFileResolver.validateOutputPath(Path.of("out.yaml"), "json")
        }.message shouldContain "does not match"
    }

    test("validateOutputPath with null format does not throw") {
        SchemaFileResolver.validateOutputPath(Path.of("out.yaml"), null)
    }

    test("validateOutputPath rejects extensionless file with explicit format") {
        shouldThrow<IllegalArgumentException> {
            SchemaFileResolver.validateOutputPath(Path.of("output"), "yaml")
        }.message shouldContain "no recognized schema extension"
    }

    test("validateOutputPath rejects unknown extension with explicit format") {
        shouldThrow<IllegalArgumentException> {
            SchemaFileResolver.validateOutputPath(Path.of("output.txt"), "json")
        }.message shouldContain "no recognized schema extension"
    }

    test("path with directory resolves correctly") {
        SchemaFileResolver.codecForPath(Path.of("/tmp/schemas/db.json"))
            .shouldBeInstanceOf<JsonSchemaCodec>()
    }

    // ── writeSchema ─────────────────────────────

    val testSchema = SchemaDefinition(
        name = "WriteTest", version = "1.0",
        tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(true))),
            primaryKey = listOf("id"),
        )),
    )

    test("writeSchema writes YAML and round-trips") {
        val dir = Files.createTempDirectory("resolver-test")
        val path = dir.resolve("output.yaml")
        try {
            SchemaFileResolver.writeSchema(path, testSchema)
            val read = SchemaFileResolver.codecForPath(path).read(path)
            read shouldBe testSchema
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }

    test("writeSchema writes JSON and round-trips") {
        val dir = Files.createTempDirectory("resolver-test")
        val path = dir.resolve("output.json")
        try {
            SchemaFileResolver.writeSchema(path, testSchema)
            val read = SchemaFileResolver.codecForPath(path).read(path)
            read shouldBe testSchema
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }

    test("writeSchema with explicit format validates extension") {
        val dir = Files.createTempDirectory("resolver-test")
        val path = dir.resolve("output.yaml")
        try {
            shouldThrow<IllegalArgumentException> {
                SchemaFileResolver.writeSchema(path, testSchema, format = "json")
            }.message shouldContain "does not match"
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    test("writeSchema with matching explicit format succeeds") {
        val dir = Files.createTempDirectory("resolver-test")
        val path = dir.resolve("output.json")
        try {
            SchemaFileResolver.writeSchema(path, testSchema, format = "json")
            val read = SchemaFileResolver.codecForPath(path).read(path)
            read shouldBe testSchema
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }
})
