package dev.dmigrate.format.json

import dev.dmigrate.core.model.*
import dev.dmigrate.format.yaml.YamlSchemaCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class JsonSchemaCodecTest : FunSpec({

    val jsonCodec = JsonSchemaCodec()
    val yamlCodec = YamlSchemaCodec()

    fun loadYamlFixture(path: String) =
        yamlCodec.read(JsonSchemaCodecTest::class.java.getResourceAsStream("/fixtures/$path")!!)

    fun loadJsonFixture(path: String) =
        jsonCodec.read(JsonSchemaCodecTest::class.java.getResourceAsStream("/fixtures/$path")!!)

    fun jsonRoundTrip(schema: SchemaDefinition): SchemaDefinition {
        val out = ByteArrayOutputStream()
        jsonCodec.write(out, schema)
        return jsonCodec.read(ByteArrayInputStream(out.toByteArray()))
    }

    // ── JSON Read ───────────────────────────────

    test("read JSON fixture produces same result as YAML fixture") {
        val fromYaml = loadYamlFixture("schemas/minimal.yaml")
        val fromJson = loadJsonFixture("schemas/minimal.json")
        fromJson shouldBe fromYaml
    }

    // ── JSON Round-Trip ─────────────────────────

    test("JSON round-trip minimal schema") {
        val original = loadYamlFixture("schemas/minimal.yaml")
        jsonRoundTrip(original) shouldBe original
    }

    test("JSON round-trip e-commerce schema") {
        val original = loadYamlFixture("schemas/e-commerce.yaml")
        jsonRoundTrip(original) shouldBe original
    }

    test("JSON round-trip full-featured schema") {
        val original = loadYamlFixture("schemas/full-featured.yaml")
        jsonRoundTrip(original) shouldBe original
    }

    test("JSON round-trip canonical-keys schema") {
        val original = loadYamlFixture("schemas/canonical-keys.yaml")
        jsonRoundTrip(original) shouldBe original
    }

    test("JSON round-trip table-metadata schema") {
        val original = loadYamlFixture("schemas/table-metadata.yaml")
        jsonRoundTrip(original) shouldBe original
    }

    test("JSON round-trip spatial schema") {
        val original = loadYamlFixture("schemas/spatial.yaml")
        jsonRoundTrip(original) shouldBe original
    }

    // ── Cross-format Round-Trip ─────────────────

    test("YAML → JSON → parse produces same schema") {
        val original = loadYamlFixture("schemas/e-commerce.yaml")
        val jsonOut = ByteArrayOutputStream()
        jsonCodec.write(jsonOut, original)
        val fromJson = jsonCodec.read(ByteArrayInputStream(jsonOut.toByteArray()))
        fromJson shouldBe original
    }

    // ── Determinism ─────────────────────────────

    test("JSON write is deterministic") {
        val schema = loadYamlFixture("schemas/e-commerce.yaml")
        val out1 = ByteArrayOutputStream()
        val out2 = ByteArrayOutputStream()
        jsonCodec.write(out1, schema)
        jsonCodec.write(out2, schema)
        out1.toByteArray().toList() shouldBe out2.toByteArray().toList()
    }

    // ── Pretty-Printing ─────────────────────────

    test("JSON output is pretty-printed") {
        val schema = SchemaDefinition(name = "Test", version = "1.0")
        val out = ByteArrayOutputStream()
        jsonCodec.write(out, schema)
        val json = out.toString(Charsets.UTF_8)
        json shouldContain "\n"
        json shouldContain "  "
    }
})
